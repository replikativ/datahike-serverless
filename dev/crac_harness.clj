(ns crac-harness
  "CRaC checkpoint/restore rig — the SnapStart shape, measured locally.

   The harness does exactly what Lambda SnapStart does: run INIT (pool, open
   tenants, warm), CHECKPOINT before any socket is bound, and on restore resume
   just after the checkpoint call — bind the server and serve. Everything the
   INIT phase built (loaded classes, JIT state, open connections' node caches)
   is inside the image, which is the entire point: datahike's nodes are
   immutable and content-addressed, so a warmed cache in the image never goes
   stale — a restore serves with one branch-head read plus the delta committed
   since the checkpoint.

   Timing marks are printed as CRAC-MARK lines so the driver can diff wall
   times across the restore boundary."
  (:require [clojure.string]
            [datahike-serverless.app :as app]
            [datahike-serverless.config :as config]
            [ring.adapter.jetty :as jetty])
  (:import [jdk.crac Core]))

(defn- mark! [k] (println "CRAC-MARK" k (System/currentTimeMillis)) (flush))

(defn -main [& _]
  (mark! :init-start)
  (let [{:keys [handler pool]} (app/build)]
    (mark! :built)
    (app/warm-tenants! pool)
    (mark! :warmed)
    ;; Warm the CODE, not just the data: the image carries exactly the classes
    ;; loaded before the checkpoint, and a reader that never ran a query ships
    ;; an image whose first real request loads the whole query-engine class
    ;; graph — measured as ~5s of the post-restore tail that no data warm could
    ;; touch. One synthetic request through the real handler loads it here,
    ;; where it is paid once per PUBLISH.
    (doseq [slug (some-> (System/getenv "DHS_WARM_TENANTS")
                         clojure.string/trim not-empty
                         (clojure.string/split #"\s*,\s*"))]
      (handler {:request-method :get :uri (str "/t/" slug "/notes") :headers {}}))
    (mark! :code-warmed)
    (if (System/getenv "CRAC_CHECKPOINT")
      (do (when-let [settle (some-> (System/getenv "CRAC_SETTLE_MS") parse-long)]
            ;; The S3 client keeps pooled keep-alive sockets open, and an open
            ;; socket fails the checkpoint. The SDK's idle reaper closes them
            ;; after connectionMaxIdleTime (60s default) — so settling past it
            ;; leaves nothing open, with no reach into konserve-s3 at all. On
            ;; real SnapStart this cost is paid once per PUBLISH, not per cold
            ;; start, so a settle here models it fairly.
            (mark! :settling)
            (Thread/sleep settle))
          (mark! :checkpointing)
          ;; The process exits into the image here; a restored process resumes
          ;; on the next line with everything above already done.
          (try (Core/checkpointRestore)
               (catch Exception e
                 ;; CheckpointException carries the real causes as SUPPRESSED
                 ;; exceptions — one per offending file descriptor — which
                 ;; clojure.main's reporter swallows. Print them or debug blind.
                 (println "CHECKPOINT-FAILED" (.getMessage e))
                 (doseq [s (.getSuppressed e)]
                   (println "  suppressed:" (str s)))
                 (flush)
                 (System/exit 2)))
          (mark! :restored)
          ;; THE SMART WARMUP: the image's cache is immutable-node-valid forever,
          ;; but everything committed since the checkpoint is cold — and paying
          ;; for it on the first query means serial discovery, misses x RTT. One
          ;; warm here fetches the delta in concurrent waves instead, before the
          ;; socket ever opens. Cost is proportional to the DELTA, not the db:
          ;; the image's nodes are already in cache and cost no fetch.
          (app/warm-tenants! pool)
          (mark! :rewarmed))
      (mark! :no-checkpoint))
    (jetty/run-jetty handler {:port (parse-long (or (System/getenv "PORT") "8080"))
                              :join? false})
    (mark! :listening)))
