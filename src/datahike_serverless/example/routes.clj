(ns datahike-serverless.example.routes
  "Two endpoints over a per-tenant datahike database.

     GET  /t/:tenant/notes   list the tenant's notes
     POST /t/:tenant/notes   {\"title\": ..., \"body\": ...}

   `conn-fn` is `slug -> conn`; it comes from the pool, and it is the only thing a
   domain needs from the kernel. Route data is plain reitit, so the same vector
   serves an HTTP port and a Lambda invocation without changing."
  (:require [datahike.api :as d]))

(defn- notes
  "The newest `limit` notes, descending by id.

   PAGINATED, and the cap is the point, not a convenience. The measured
   cold-start story (doc/snapstart-crac.md) decomposes cleanly: at 50k notes
   the STORAGE side of a snapshot restore fits in ~1.5s, and what blew past
   the 2s budget was this function returning every note as one 6.2MB response
   — 1.1s of query CPU plus serialization, paid on any server however warm. A
   cold start is as fast as the first REQUEST is small; an unbounded list
   route makes it O(database) and no amount of warming gets it back.

   `rseek-datoms` on `:avet` walks `:note/id` from the TOP downward and stops
   after `limit` entities, so the read is proportional to the PAGE — it never
   materializes the index (a `seek` + `reverse` would realize everything after
   the seek point, which is the same O(database) in a paper-thin disguise).
   Page-sized reads are also what keep the GET count page-sized on a cold
   cache."
  [db limit]
  (->> (d/rseek-datoms db :avet :note/id Long/MAX_VALUE)
       (take-while #(= :note/id (:a %)))
       (take limit)
       (mapv (fn [d]
               (let [e (d/entity db (:e d))]
                 {:id (:note/id e) :title (:note/title e)
                  :body (:note/body e) :created-at (:note/created-at e)})))))

(defn- next-id
  "Max id + 1. Fine for an example; a real app would use a UUID or a squuid and
   avoid the read-before-write entirely."
  [db]
  (inc (or (d/q '[:find (max ?id) . :where [_ :note/id ?id]] db) 0)))

(defn routes [conn-fn]
  [["/t/:tenant/notes"
    {:get  (fn [req]
             (let [conn  (conn-fn (get-in req [:path-params :tenant]))
                   ;; default 100; `?limit=` caps at 1000 so one request cannot
                   ;; be made O(database) from the outside.
                   limit (-> (get-in req [:query-params "limit"])
                             (or "100") parse-long (or 100) (min 1000) (max 1))]
               {:status 200 :body {:notes (notes @conn limit)}}))
     :post (fn [req]
             (let [conn  (conn-fn (get-in req [:path-params :tenant]))
                   {:keys [title body]} (:body-params req)
                   id    (next-id @conn)
                   {:keys [db-after]}
                   (d/transact conn [{:note/id         id
                                      :note/title      (or title "untitled")
                                      :note/body       (or body "")
                                      :note/created-at (java.util.Date.)}])]
               {:status 201 :body {:id id :tx (:max-tx db-after)}}))}]])
