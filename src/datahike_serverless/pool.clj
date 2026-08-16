(ns datahike-serverless.pool
  "Lazy per-tenant connection registry — one datahike database per tenant, opened on
   first use and held for as long as the execution environment lives.

   This is the part of a serverless deployment that decides your numbers. The whole
   measured difference between a 3218 ms first invocation and an 18.7 ms warm one is
   whether this map already holds the tenant's connection and its node cache.

   BOUNDED, because a hot connection is not free: it holds that tenant's in-memory
   index, which scales with the tenant's DATA, not the tenant count. `:max-hot`
   (env DHS_MAX_HOT, default 64 — smaller than a long-lived server would use,
   because function memory is the thing you pay for) caps how many stay open and
   evicts the least-recently-used beyond it.

   Eviction is the only hard part. A connection must never be closed while a request
   is reading it, so each request PINS its tenant (`pin!`/`unpin!`, done for you by
   the ring middleware) and eviction skips anything pinned. If everything is pinned
   we exceed :max-hot rather than break a live request.

   On classic Lambda one execution environment serves one invocation at a time, so
   pinning is close to free there. It is NOT redundant: on Cloud Run (and on Lambda
   Managed Instances) one instance serves many concurrent requests, which is exactly
   the property that makes those platforms cheaper for this workload — one warm cache
   amortised over many requests — and it is what makes eviction racy.

   READ-ONLY is enforced here, not by convention. A reader that creates databases or
   installs schema is a second WRITER, which is the failure mode the single-writer
   caveat in the README is about. Measured in the source prototype: a reader
   performed 2 PUTs on first tenant open before this guard existed."
  (:require [datahike.api :as d]
            [datahike-serverless.config :as config]
            [konserve-s3.core]                     ;; registers the :s3 backend
            [replikativ.logging :as log])
  (:import [java.security MessageDigest]
           [java.util UUID]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.atomic AtomicLong]
           [java.util.function Function]))

;; ── tenant slug -> stable store id ──────────────────────────────────────────

(def ^:private namespace-uuid #uuid "00000000-0000-0000-0000-000000000001")

(defn tenant-id->uuid
  "Deterministic UUID from a tenant slug, so a slug maps to the same store id across
   process restarts — which, on a platform that throws the process away constantly,
   is the entire addressing scheme."
  [tenant-slug]
  (let [bytes (.getBytes (str namespace-uuid tenant-slug) "UTF-8")
        md5   (.digest (MessageDigest/getInstance "MD5") bytes)]
    (aset-byte md5 6 (unchecked-byte (bit-or 0x50 (bit-and (aget md5 6) 0x0F))))
    (aset-byte md5 8 (unchecked-byte (bit-or 0x80 (bit-and (aget md5 8) 0x3F))))
    (let [msb (reduce (fn [a i] (bit-or (bit-shift-left a 8) (bit-and (aget md5 i) 0xFF))) 0 (range 0 8))
          lsb (reduce (fn [a i] (bit-or (bit-shift-left a 8) (bit-and (aget md5 i) 0xFF))) 0 (range 8 16))]
      (UUID. msb lsb))))

(defn tenant-cfg
  "Per-tenant datahike config: the shared base config with this tenant's store id.

   For `:s3` the id is the key prefix, so every tenant shares one bucket and one set
   of credentials — that is the economic argument. For `:file` a konserve store IS a
   directory and its keys carry no store-id prefix, so tenants get their own path."
  [{:keys [base-cfg]} tenant-slug]
  (let [sid   (tenant-id->uuid tenant-slug)
        store (:store base-cfg)]
    (assoc base-cfg :store
           (if (= :file (:backend store))
             (-> store (assoc :id sid) (update :path str "/" sid))
             (assoc store :id sid)))))

;; ── open ────────────────────────────────────────────────────────────────────

(defn- open-tenant!
  "Create (if missing), connect, and install the injected schema. Returns an entry."
  [pool tenant-slug]
  (let [cfg (tenant-cfg pool tenant-slug)
        t0  (System/nanoTime)
        ro? (:read-only? pool)]
    (when-not ro?
      (when-not (d/database-exists? cfg)
        (d/create-database cfg)))
    (when (and ro? (not (d/database-exists? cfg)))
      (throw (ex-info (str "tenant " tenant-slug " does not exist, and this process is a READER. "
                           "Readers never create databases — send the first write to the writer.")
                      {:tenant tenant-slug :read-only? true})))
    (let [conn (d/connect cfg)]
      (when-not ro?
        ((:ensure-schema pool) conn))
      {:conn conn :cfg cfg :open-cost-ns (- (System/nanoTime) t0)})))

;; ── registry ────────────────────────────────────────────────────────────────

(defn- env-max-hot []
  (when-let [v (System/getenv "DHS_MAX_HOT")]
    (let [n (Long/parseLong v)]
      (when (pos? n) n))))

(defn create
  "A tenant pool.

   Options:
   - `:base-cfg`      defaults to `config/base-cfg`.
   - `:ensure-schema` fn `conn -> _`, run once per tenant open. Default `identity`.
   - `:read-only?`    defaults to `(= :reader (config/role))`.
   - `:max-hot`       open connections to keep (env DHS_MAX_HOT, default 64).
                      `nil` (or DHS_MAX_HOT=0) means unbounded."
  ([] (create {}))
  ([{:keys [base-cfg max-hot ensure-schema read-only?]
     :or   {max-hot :default ensure-schema identity}}]
   {:base-cfg      (or base-cfg (config/base-cfg))
    :read-only?    (if (some? read-only?) (boolean read-only?) (= :reader (config/role)))
    :ensure-schema ensure-schema
    :tenants       (ConcurrentHashMap.)
    ;; Pins are keyed by SLUG, not by entry: a tenant is pinned for the life of the
    ;; request, which begins BEFORE its connection exists.
    :pins          (ConcurrentHashMap.)
    :max-hot       (if (= max-hot :default) (or (env-max-hot) 64) max-hot)
    :clock         (AtomicLong. 0)}))

(defn- touch! [pool entry]
  (.set ^AtomicLong (:used entry) (.incrementAndGet ^AtomicLong (:clock pool))))

(defn pin!
  "Mark a tenant as IN USE; a pinned tenant is never evicted. Pair with `unpin!` in a
   finally — `datahike-serverless.app/wrap-tenant-pin` does that for every request."
  [pool tenant-slug]
  (.incrementAndGet ^AtomicLong
                    (.computeIfAbsent ^ConcurrentHashMap (:pins pool) tenant-slug
                                      (reify Function (apply [_ _] (AtomicLong. 0))))))

(defn unpin! [pool tenant-slug]
  (let [^ConcurrentHashMap pins (:pins pool)]
    (when-let [^AtomicLong n (.get pins tenant-slug)]
      (when (<= (.decrementAndGet n) 0)
        (.remove pins tenant-slug n)))))          ;; CAS-remove; a racing pin! re-adds

(defn- pinned? [pool tenant-slug]
  (when-let [^AtomicLong n (.get ^ConcurrentHashMap (:pins pool) tenant-slug)]
    (pos? (.get n))))

(defn- evict-lru!
  "Close least-recently-used connections until the pool is within :max-hot. Never
   evicts a pinned tenant, and never evicts `keep` — the tenant being borrowed."
  [pool keep]
  (when-let [max-hot (:max-hot pool)]
    (let [^ConcurrentHashMap m (:tenants pool)]
      (loop []
        (when (> (.size m) max-hot)
          (when-let [[slug e] (->> (into [] m)
                                   (remove (fn [[slug _]] (or (= slug keep) (pinned? pool slug))))
                                   (sort-by (fn [[_ e]] (.get ^AtomicLong (:used e))))
                                   first)]
            ;; .remove(k,v) is a CAS: only evict if this is still the entry we chose.
            (when (.remove m slug e)
              (try (d/release (:conn e))
                   (catch Exception ex
                     (log/warn :tenant/evict-failed {:tenant slug :error (.getMessage ex)})))
              (log/debug :tenant/evicted {:tenant slug :hot (.size m) :max-hot max-hot}))
            (recur)))))))

(defn borrow
  "The connection for a tenant, opening it on first use."
  [pool tenant-slug]
  (let [^ConcurrentHashMap m (:tenants pool)
        entry (.computeIfAbsent
               m tenant-slug
               (reify Function
                 (apply [_ slug]
                   (let [e (open-tenant! pool slug)]
                     (log/info :tenant/opened {:tenant slug :cost-ms (/ (:open-cost-ns e) 1e6)})
                     (assoc e :used (AtomicLong. 0))))))]
    (touch! pool entry)
    (evict-lru! pool tenant-slug)
    (:conn entry)))

(defn hot-count [pool] (.size ^ConcurrentHashMap (:tenants pool)))

(defn close-all!
  "Release every open tenant connection."
  [pool]
  (let [^ConcurrentHashMap m (:tenants pool)]
    (doseq [[slug {:keys [conn]}] (into {} m)]
      (try (d/release conn)
           (catch Exception e (log/warn :tenant/close-failed {:tenant slug :error (.getMessage e)}))))
    (.clear m)))

(defn delete-tenant!
  "Delete this tenant's database — the objects, not just the rows. Offboarding and
   GDPR erasure in one call, complete by construction because the tenant's data
   exists in no other database. Refuses on a reader."
  [pool tenant-slug]
  (when (:read-only? pool)
    (throw (ex-info "Refusing to delete a tenant from a READER." {:tenant tenant-slug})))
  (let [^ConcurrentHashMap m (:tenants pool)
        cfg (tenant-cfg pool tenant-slug)]
    (when-let [{:keys [conn]} (.remove m tenant-slug)]
      (try (d/release conn) (catch Exception _ nil)))
    (if (d/database-exists? cfg)
      (do (d/delete-database cfg)
          (log/info :tenant/deleted {:tenant tenant-slug})
          true)
      false)))
