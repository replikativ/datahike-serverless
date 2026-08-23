(ns datahike-serverless.config
  "Load the store config (resources/config.edn) for the selected profile, and
   resolve this process's ROLE.

   Two env vars decide everything:

     DHS_STORE   file | s3        which `:store` profile to use (default: file)
     DHS_ROLE    writer | reader  what authority this process has (default: writer)

   The role is not cosmetic and not a permission check — it changes the datahike
   CONFIG in a way that decides whether the process can see new data at all:

     writer  `:writer {:backend :self ...}` — transacts here, FENCED: since
             datahike 0.8.1792 a self writer defaults to shared ownership, so it
             re-reads the branch head before each batch and publishes it with a
             conditional PUT. The config demands that with `:require-fencing`
             (see resources/config.edn), so a store that cannot fence refuses
             the connect instead of running unprotected.
     reader  `:writer {:backend :datahike-server}` — a REMOTE writer, so this
             connection is not streamed to and every `@conn` re-reads the
             branch head from the store; the reader follows the writer with no
             connection to it.

   A reader left on the default `:self` writer would claim write authority over
   a bucket someone else owns — fenced now, so it can no longer silently clobber
   the real writer, but it would still create databases and install schema. That
   is the mistake this namespace exists to make impossible to fall into.

   The `:datahike-server` writer is NOT a working write path here — it POSTs to
   datahike's own HTTP-server routes, which this app does not mount. It is
   configured because a remote writer backend is exactly \"do not transact\n   here, refresh on deref\"; `pool/create` additionally refuses to create databases or
   install schema on a reader."
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def store-profiles #{:file :s3})

(defn store-profile
  "The active store profile, from DHS_STORE. Defaults to :file — a plain directory
   on disk, so the repo runs with nothing installed."
  []
  (let [p (some-> (System/getenv "DHS_STORE") str/trim keyword)]
    (if (store-profiles p) p :file)))

(defn role
  "This process's role, from DHS_ROLE. :writer | :reader."
  []
  (if (= "reader" (some-> (System/getenv "DHS_ROLE") str/trim str/lower-case))
    :reader
    :writer))

(defn- expand-s3-endpoint
  "Turn a bare `:s3-endpoint` URL into konserve-s3's `:endpoint-override`, or drop
   it (and nil credentials) so plain AWS with the default credential chain is used."
  [store]
  (if-not (= :s3 (:backend store))
    store
    (let [ep    (:s3-endpoint store)
          store (cond-> (dissoc store :s3-endpoint)
                  (nil? (:access-key store)) (dissoc :access-key)
                  (nil? (:secret store))     (dissoc :secret))]
      (if (str/blank? (str ep))
        store
        (let [u     (io/as-url ep)
              proto (keyword (.getProtocol u))
              port  (let [p (.getPort u)]
                      (if (pos? p) p (if (= :https proto) 443 80)))]
          (assoc store :endpoint-override
                 {:protocol proto :hostname (.getHost u) :port port}))))))

(defn base-cfg
  "The shared datahike config — everything but the per-tenant `:store :id`."
  ([] (base-cfg (store-profile) (role)))
  ([profile role]
   (let [cfg (-> (aero/read-config (io/resource "config.edn") {:profile profile})
                 (update :store expand-s3-endpoint))]
     (if (= :reader role)
       (assoc cfg :writer (cond-> {:backend :datahike-server
                                   :url (or (System/getenv "DHS_WRITER_URL")
                                            "http://localhost:8080")}
                            (System/getenv "DHS_TOKEN")
                            (assoc :token (System/getenv "DHS_TOKEN"))))
       cfg))))
