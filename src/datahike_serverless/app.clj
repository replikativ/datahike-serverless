(ns datahike-serverless.app
  "Composition root: pool + routes -> one ring handler, plus the INIT-time warm.

   `build` returns the handler WITHOUT binding a port. That is the seam every
   platform adapter uses: `platforms/aws-lambda` calls `build` during Lambda INIT
   and translates Function-URL events into ring requests; a Cloud Run / Fly adapter
   would call `-main` and get the same handler over HTTP. There is one wiring, not
   one per platform."
  (:require [datahike-serverless.config :as config]
            [datahike-serverless.pool :as pool]
            [datahike-serverless.example.schema :as schema]
            [datahike-serverless.example.routes :as example]
            [datahike.api :as d]
            [clojure.string :as str]
            [muuntaja.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja-mw]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.adapter.jetty :as jetty]
            [replikativ.logging :as log]))

(def mtj
  (m/create
   (assoc-in m/default-options
             [:formats "application/json" :encoder-opts :date-format]
             "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")))

(defn- wrap-tenant-pin
  "Pin the request's tenant for the life of the request so the LRU pool cannot evict
   a connection that is being read. Matters as soon as an instance serves more than
   one request at a time — which is Cloud Run's default and Lambda's exception."
  [pool]
  (fn [handler]
    (fn [req]
      (if-let [slug (get-in req [:path-params :tenant])]
        (do (pool/pin! pool slug)
            (try (handler req)
                 (finally (pool/unpin! pool slug))))
        (handler req)))))

(defn- wrap-read-only
  "Refuse writes on a reader, with a 405 that says where to send them. Without this
   a write to a reader fails somewhere deep and confusing; more importantly, a
   reader that writes IS a second writer."
  [handler]
  (fn [req]
    (if (#{:post :put :patch :delete} (:request-method req))
      {:status 405
       :headers {"allow" "GET"}
       :body {:error  "reader — writes are not served here"
              :writer (System/getenv "DHS_WRITER_URL")
              :hint   "This process reads the shared store and follows the writer on every deref."}}
      (handler req))))

(defn- kernel-routes [pool]
  [["/health" {:get (fn [_] {:status 200 :body {:status "ok"
                                                :role (name (config/role))
                                                :store (name (config/store-profile))
                                                :hot (pool/hot-count pool)}})}]
   ["/t/:tenant"
    {:delete (fn [req]
               (let [slug (get-in req [:path-params :tenant])]
                 (if (pool/delete-tenant! pool slug)
                   {:status 200 :body {:deleted slug}}
                   {:status 404 :body {:error "not found"}})))}]])

(defn build
  "Wire a pool and the example routes into a ring handler.

   Returns `{:handler :pool :role :read-only? :stop}`. The caller owns `:stop`."
  ([] (build {}))
  ([{:keys [ensure-schema routes-fn]
     :or   {ensure-schema schema/ensure-schema! routes-fn example/routes}}]
   (let [pool    (pool/create {:ensure-schema ensure-schema})
         ro?     (:read-only? pool)
         conn-fn (fn [slug] (pool/borrow pool slug))
         handler (ring/ring-handler
                  (ring/router
                   (into (kernel-routes pool) (routes-fn conn-fn))
                   {:data {:muuntaja   mtj
                           :middleware (cond-> [parameters/parameters-middleware
                                                muuntaja-mw/format-middleware
                                                (wrap-tenant-pin pool)]
                                         ro? (conj wrap-read-only))}})
                  (ring/create-default-handler))]
     {:handler handler :pool pool :role (config/role) :read-only? ro?
      :stop    #(pool/close-all! pool)})))

;; ── INIT-time warm ──────────────────────────────────────────────────────────

(defn warm-tenants!
  "Open and warm the tenants named in DHS_WARM_TENANTS, using datahike's
   `d/warm-db`: a budget-bounded breadth-first walk that fetches each level of every
   index CONCURRENTLY, instead of discovering it one blocking round trip at a time.

   Call it where the platform gives you free time before requests arrive — Lambda
   INIT, a Cloud Run startup probe, a container entrypoint.

   READ THE CAVEAT before enabling it on Lambda. Measured on the source prototype
   (Clojure-only, RIE + MinIO, 201-note tenant):

     without warm:  INIT 238 ms + first invoke 3218 ms (18 GETs) = 3456 ms
     with warm:     INIT 3495 ms + first invoke  205 ms (1 GET)  = 3700 ms

   Moving the work to INIT makes the first REQUEST 15.7x faster and it makes the
   whole cold path slightly SLOWER, because the warm fetches more (24) than the
   query needed (18). It pays only when INIT is amortised over more than one
   invocation — under SnapStart (paid once per published version) or in a container
   that serves many requests. Do not enable it blind.

   `:depth :interior` warms only the spine (cheap, ~2 waves); `:with-leaves` warms
   everything the budget allows. The budget is clamped to 0.8x :store-cache-size,
   because warming past an entry-counted cache fetches nodes only to evict them."
  [pool]
  (when-let [slugs (some-> (System/getenv "DHS_WARM_TENANTS") str/trim not-empty
                           (str/split #"\s*,\s*"))]
    (let [depth  (keyword (or (System/getenv "DHS_WARM_DEPTH") "with-leaves"))
          budget (parse-long (or (System/getenv "DHS_WARM_BUDGET") "2000"))]
      (doseq [slug slugs]
        (let [conn (pool/borrow pool slug)
              r    (d/warm-db @conn {:depth depth :budget budget})]
          (log/info :warm/tenant {:tenant slug :depth depth :budget budget
                                  :fetched (:fetched r)
                                  :rounds (:rounds r)
                                  :by-index (:by-index r)
                                  :budget-clamped? (:budget-clamped? r)}))))))

;; ── plain HTTP (local dev; the Cloud Run / Fly shape) ───────────────────────

(defn -main [& _]
  (let [{:keys [handler pool role]} (build)
        port (parse-long (or (System/getenv "PORT") "8080"))]
    (warm-tenants! pool)
    (jetty/run-jetty handler {:port port :join? false})
    (log/info :app/started {:port port :role role :store (config/store-profile)})))
