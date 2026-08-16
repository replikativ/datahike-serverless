(ns datahike-serverless.platform.aws-lambda
  "AWS Lambda adapter — the same app, served from a function instead of a port.

   > ⚠️ EXPERIMENTAL. Reads are safe today: datahike readers are unconstrained, any
   > number, in any number of processes. WRITES ARE NOT, until datahike fences the
   > branch head with a conditional PUT (issue #878). A writer function must run at
   > reserved concurrency 1, which NARROWS the two-writer window without closing it
   > — deploys and container replacement still overlap. See the README.

   Topology borrowed from [viesti/clj-lambda-datahike](https://github.com/viesti/clj-lambda-datahike),
   which got it right years ago: a reader function with no concurrency limit, a
   writer function pinned to 1, Function URLs rather than API Gateway (API Gateway
   is a large share of a serverless bill and buys nothing this app needs).

   ## The three entry points, and why they are measured separately

   A Lambda has more than one kind of \"start\", they cost very different amounts,
   and only one of them is what people mean by \"cold start\":

     INIT   the container boots: JVM, namespaces, pool, optional warm. Once per
            execution environment — and under SnapStart, once per PUBLISHED VERSION,
            because the snapshot is taken after INIT.
     FIRST  first invocation for a tenant in this container: the connection opens
            and the node cache is empty.
     WARM   every invocation after that: nothing touches the object store except the
            branch-head re-read.

   `DHS_LAMBDA_STATS=1` logs each phase with its konserve-s3 GET/PUT counts. Demo
   grade only: the io-stats accumulator is a global and is not reentrant, so do not
   enable it under concurrency."
  (:require [datahike-serverless.app :as app]
            [jsonista.core :as json]
            [clojure.string :as str]
            [replikativ.logging :as log])
  (:import [java.io InputStream OutputStream ByteArrayInputStream]
           [java.nio.charset StandardCharsets]
           [java.util Base64])
  (:gen-class
   :name datahike_serverless.platform.aws_lambda
   :implements [com.amazonaws.services.lambda.runtime.RequestStreamHandler]))

(def ^:private mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- stats-on? [] (= "1" (System/getenv "DHS_LAMBDA_STATS")))

(defn- io-stats
  "konserve-s3 op counts for `f`, or nil when instrumentation is off or the store is
   not S3. Resolved dynamically so the :file store needs no S3 code path at all."
  [f]
  (if-not (stats-on?)
    (do (f) nil)
    (if-let [with-stats (try (requiring-resolve 'konserve-s3.core/set-global-io-stats!)
                             (catch Throwable _ nil))]
      (let [acc     (atom {})
            summary (requiring-resolve 'konserve-s3.core/io-stats-summary)]
        (with-stats acc)
        (try (f) (finally (with-stats nil)))
        (summary @acc))
      (do (f) nil))))

(defn- phase! [phase f]
  (let [t0 (System/nanoTime)
        st (io-stats f)
        ms (/ (- (System/nanoTime) t0) 1e6)]
    (log/info :lambda/phase (cond-> {:phase phase :ms ms}
                              st (assoc :gets (get-in st [:get :n] 0)
                                        :puts (get-in st [:put :n] 0))))
    ms))

;; ── INIT ────────────────────────────────────────────────────────────────────
;; A `defonce` at namespace load: this runs during the Lambda INIT phase, which is
;; exactly where SnapStart takes its snapshot. Everything established here — the
;; pool, open connections, warmed node caches — is what a restored environment
;; starts with. That is the argument for priming here rather than baking a database
;; into the image: a snapshot holds DESERIALIZED nodes in the heap, costs no image
;; size, and refreshing it is publishing a version rather than a rebuild.

(defonce ^{:doc "Built once per execution environment, during INIT."} runtime
  (delay
    (let [ctx (atom nil)]
      (phase! :init
              (fn []
                (let [{:keys [handler pool stop role]} (app/build)]
                  (app/warm-tenants! pool)
                  (reset! ctx {:handler handler :pool pool :stop stop :role role}))))
      @ctx)))

;; ── event <-> ring ──────────────────────────────────────────────────────────
;; Lambda Function URL / API Gateway HTTP API "payload format 2.0".

(defn- event->ring
  [{:keys [rawPath rawQueryString headers body isBase64Encoded requestContext]}]
  (let [method (some-> requestContext :http :method str/lower-case keyword)
        raw    (when body
                 (if isBase64Encoded
                   (.decode (Base64/getDecoder) ^String body)
                   (.getBytes ^String body StandardCharsets/UTF_8)))]
    {:request-method (or method :get)
     :uri            (or rawPath "/")
     :query-string   (not-empty rawQueryString)
     ;; Function URLs already lower-case header names; normalise anyway so a
     ;; hand-made test event behaves like a real one.
     :headers        (into {} (map (fn [[k v]] [(str/lower-case (name k)) v])) headers)
     :body           (when raw (ByteArrayInputStream. raw))
     :scheme         :https
     :server-name    (get headers :host "localhost")
     :server-port    443
     :protocol       "HTTP/1.1"}))

(defn- ring->response [{:keys [status headers body]}]
  {:statusCode      (or status 200)
   :headers         (or headers {})
   :isBase64Encoded false
   :body            (cond
                      (nil? body)                  ""
                      (string? body)               body
                      (instance? InputStream body) (slurp body)
                      :else                        (str body))})

;; ── handler ─────────────────────────────────────────────────────────────────

(defn handle
  "Event map -> response map. Exposed so the Lambda path is exercisable from a REPL
   or a test without a container."
  [event]
  (let [{:keys [handler]} @runtime
        t0   (System/nanoTime)
        resp (atom nil)
        st   (io-stats #(reset! resp (handler (event->ring event))))
        ms   (/ (- (System/nanoTime) t0) 1e6)]
    (log/info :lambda/phase (cond-> {:phase :invoke :ms ms :path (:rawPath event)}
                              st (assoc :gets (get-in st [:get :n] 0)
                                        :puts (get-in st [:put :n] 0))))
    (ring->response @resp)))

(defn -handleRequest [_this ^InputStream in ^OutputStream out _ctx]
  (let [event (json/read-value in mapper)
        resp  (try
                (handle event)
                (catch Throwable e
                  (log/warn :lambda/error {:error (ex-message e)})
                  {:statusCode 500
                   :headers {"content-type" "application/json"}
                   :isBase64Encoded false
                   :body (json/write-value-as-string {:error (ex-message e)})}))]
    (with-open [w (java.io.OutputStreamWriter. out StandardCharsets/UTF_8)]
      (.write w ^String (json/write-value-as-string resp)))))
