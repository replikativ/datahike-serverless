(ns datahike-serverless.example.schema
  "The example domain: notes. Deliberately tiny.

   This repo is about the PLATFORM shape — what a cold start costs, where the
   warmth lives, who is allowed to write. The domain is here only so there is
   something real to query. Replace this namespace and `example.routes` with your
   own; nothing under `datahike-serverless.pool` / `.config` / `.app` knows about
   notes, and nothing in `platforms/` does either.

   The schema is installed by the pool's INJECTED `:ensure-schema` on tenant open,
   and only ever on a writer."
  (:require [datahike.api :as d]))

(def schema
  [{:db/ident       :note/id
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :note/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :note/body
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :note/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn ensure-schema!
  "Install the schema once per tenant database. Idempotent: transacting it again is
   harmless, but we check first so a warm open costs no PUT."
  [conn]
  (when-not (d/q '[:find ?e . :where [?e :db/ident :note/id]] @conn)
    (d/transact conn schema))
  conn)
