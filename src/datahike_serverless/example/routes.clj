(ns datahike-serverless.example.routes
  "Two endpoints over a per-tenant datahike database.

     GET  /t/:tenant/notes   list the tenant's notes
     POST /t/:tenant/notes   {\"title\": ..., \"body\": ...}

   `conn-fn` is `slug -> conn`; it comes from the pool, and it is the only thing a
   domain needs from the kernel. Route data is plain reitit, so the same vector
   serves an HTTP port and a Lambda invocation without changing."
  (:require [datahike.api :as d]))

(defn- notes [db]
  (->> (d/q '[:find ?id ?title ?body ?at
              :where
              [?e :note/id ?id]
              [?e :note/title ?title]
              [?e :note/body ?body]
              [?e :note/created-at ?at]]
            db)
       (sort-by first)
       (mapv (fn [[id title body at]]
               {:id id :title title :body body :created-at at}))))

(defn- next-id
  "Max id + 1. Fine for an example; a real app would use a UUID or a squuid and
   avoid the read-before-write entirely."
  [db]
  (inc (or (d/q '[:find (max ?id) . :where [_ :note/id ?id]] db) 0)))

(defn routes [conn-fn]
  [["/t/:tenant/notes"
    {:get  (fn [req]
             (let [conn (conn-fn (get-in req [:path-params :tenant]))]
               {:status 200 :body {:notes (notes @conn)}}))
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
