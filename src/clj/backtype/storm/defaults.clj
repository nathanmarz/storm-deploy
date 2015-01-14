(ns backtype.storm.defaults
  (:use [clojure.string :only [join]]))

(def defaults
  {:maven3
   {:url-template
    "http://mirrors.ibiblio.org/apache/maven/maven-3/%s/binaries/apache-maven-%s-bin.tar.gz"
    :md5-url-template
    "http://www.apache.org/dist/maven/maven-3/%s/binaries/apache-maven-%s-bin.tar.gz.md5"
    :version "3.2.5"
    :install-dir "/usr/local/maven3"
    :bin-destination "/usr/bin/mvn"}
   :zookeeper {:version "3.3.6"}
   :zeromq {:version "2.1.4"}
   :jzmq {:version "2.1.0"}}
  )

(defn default [key-seq]
  (let [key-path (join "_" (map name key-seq))
        not-found-key (keyword (str "default-" key-path "-not-found"))]
    (get-in defaults key-seq not-found-key)))

(defn sub-default [sub-key-seq]
  (fn [key-seq]
    (default (vec (concat sub-key-seq key-seq)))))
