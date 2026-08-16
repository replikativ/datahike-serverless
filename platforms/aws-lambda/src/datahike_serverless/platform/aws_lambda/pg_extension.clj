(ns datahike-serverless.platform.aws-lambda.pg-extension
  "The JVM half of the Lambda pg-datahike extension: the tenant pool exposed as a
   PostgreSQL server on loopback.

   > ⚠️ EXPERIMENTAL, and less verified than the Clojure-only path. Same write
   > caveat: safe for reads, single-writer for writes until the branch head is
   > CAS-fenced (datahike #878).

   Extensions run in the SAME network namespace as the handler, so a handler in any
   language reaches this on 127.0.0.1:5432 with a stock driver. `start-server` takes
   a `{name -> conn}` registry and routes on the StartupMessage's `database`
   parameter, so A TENANT IS A PG DATABASE NAME — `postgresql://localhost/acme`
   lands on that tenant's connection, with no `WHERE tenant_id` anywhere.

   Prints one parseable `PG-READY …` line once the socket accepts. The extension
   wrapper does not read it (Lambda does not forward agent stdout — see
   `extension/pg-datahike`); it probes the port. The line exists so the breakdown is
   recoverable from a local run.

   Env: DHS_PG_TENANTS (comma-separated, default \"acme\"), DHS_PG_PORT (5432),
        DHS_WARM_DEPTH / DHS_WARM_BUDGET as in the Lambda adapter."
  (:require [datahike-serverless.pool :as pool]
            [datahike-serverless.example.schema :as schema]
            [datahike.api :as d]
            [clojure.string :as str])
  (:gen-class))

(defn -main [& _]
  (let [t0     (System/nanoTime)
        slugs  (-> (or (System/getenv "DHS_PG_TENANTS") "acme")
                   (str/split #"\s*,\s*"))
        port   (parse-long (or (System/getenv "DHS_PG_PORT") "5432"))
        p      (pool/create {:ensure-schema schema/ensure-schema!})
        t-pool (/ (- (System/nanoTime) t0) 1e6)
        t1     (System/nanoTime)
        reg    (into {} (map (fn [s] [s (pool/borrow p s)])) slugs)
        t-open (/ (- (System/nanoTime) t1) 1e6)
        t2     (System/nanoTime)
        warmed (when-let [depth (System/getenv "DHS_WARM_DEPTH")]
                 (into {} (map (fn [[s conn]]
                                 [s (:fetched (d/warm-db @conn
                                                         {:depth (keyword depth)
                                                          :budget (parse-long (or (System/getenv "DHS_WARM_BUDGET")
                                                                                  "2000"))}))]))
                       reg))
        t-warm (/ (- (System/nanoTime) t2) 1e6)
        ;; load= and bind= are SPLIT deliberately. An earlier version of this timed
        ;; them together and reported "bind=4190ms", which reads as pg-datahike doing
        ;; expensive work at socket-bind time. It is not: `start-server` measures
        ;; 23 ms on a cold JVM and 1 ms thereafter. The ~4.2 s is `requiring-resolve`
        ;; LOADING the pg-datahike namespace tree.
        ;;
        ;; That distinction decides the fix. Class loading is not lazy-able — you
        ;; need the code to serve SQL — but it is exactly what a heap snapshot
        ;; removes. So it is a SnapStart / native-image / long-lived-container
        ;; target, not a restructuring target. It is also why the Clojure-only
        ;; Lambda path is 238 ms: no pg-datahike to load.
        t3     (System/nanoTime)
        start! (requiring-resolve 'datahike.pg.server/start-server)
        t-load (/ (- (System/nanoTime) t3) 1e6)
        t4     (System/nanoTime)
        _      (start! reg {:port port :host "127.0.0.1"})
        t-bind (/ (- (System/nanoTime) t4) 1e6)]
    (println (format "PG-READY pool=%.0fms open=%.0fms warm=%.0fms load=%.0fms bind=%.0fms total=%.0fms tenants=%s warmed=%s"
                     t-pool t-open t-warm t-load t-bind
                     (/ (- (System/nanoTime) t0) 1e6)
                     (pr-str (vec slugs)) (pr-str warmed)))
    (flush)
    (.join (Thread/currentThread))))
