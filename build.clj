(ns build
  "Uberjars for the AWS Lambda java runtime.

   AOT of the adapter namespace is REQUIRED rather than optional: the Lambda java
   runtime loads a handler CLASS by name, and `:gen-class` is what produces one.

   Only the adapter namespaces are compiled. AOT-compiling the world drags in
   everything transitively and is how Clojure builds get slow and brittle.

     clj -T:build uber       # Clojure-only handler        -> target/datahike-serverless-lambda.jar
     clj -T:build uber-pg    # + pg-datahike extension JVM -> target/datahike-serverless-pg.jar"
  (:require [clojure.tools.build.api :as b]))

(def ^:private class-dir "target/classes")
(def ^:private src-dirs ["src" "resources" "platforms/aws-lambda/src"])

(defn clean [_] (b/delete {:path "target"}))

(defn- build-uber [aliases nses out]
  (clean nil)
  (let [basis (b/create-basis {:project "deps.edn" :aliases aliases})]
    (b/copy-dir {:src-dirs src-dirs :target-dir class-dir})
    (b/compile-clj {:basis basis :ns-compile nses :class-dir class-dir})
    (b/uber {:class-dir class-dir :uber-file out :basis basis})
    (println "built" out)))

(defn uber [_]
  (build-uber [:build :aws-lambda]
              '[datahike-serverless.platform.aws-lambda]
              "target/datahike-serverless-lambda.jar"))

(defn uber-pg
  "Uberjar carrying pg-datahike too — the JVM half of the Lambda extension."
  [_]
  (build-uber [:build :aws-lambda :pg]
              '[datahike-serverless.platform.aws-lambda
                datahike-serverless.platform.aws-lambda.pg-extension]
              "target/datahike-serverless-pg.jar"))
