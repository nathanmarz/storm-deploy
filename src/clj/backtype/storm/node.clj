(ns backtype.storm.node
  (:import
   [java.util Map List]
   [org.jvyaml YAML]
   [java.io FileReader File])
  (:require
   [backtype.storm.crate.storm :as storm]
   [backtype.storm.crate.leiningen :as leiningen]
   [backtype.storm.crate.zeromq :as zeromq]
   [backtype.storm.crate.ganglia :as ganglia]

   [pallet.crate.git :as git]
   [pallet.crate.maven :as maven]
   [pallet.crate.java :as java]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [backtype.storm.crate.zookeeper :as zookeeper]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.action.service :as action-service]

   [pallet.resource.directory :as directory]
   [pallet.resource.service :as service]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.exec-script :as exec-script])
  (:use
   [backtype.storm config]
   [pallet compute core resource]
   [pallet [utils :only [make-user]]]
   [org.jclouds.compute :only [nodes-with-tag]]
   [clojure.walk]))

;; CONSTANTS

(def clusters-conf
  (read-yaml-config "clusters.yaml"))

(def storm-yaml-path
  (.getPath (ClassLoader/getSystemResource "storm.yaml"))
  )

(def storm-conf (read-storm-config))

(defn nimbus-name [name]
  (str "nimbus-" name))

(defn configure-ssh-client [request & {:keys [host-key-checking]}]
  (let [yes-or-no #(if % "yes" "no")]
    (-> request
        (remote-file/remote-file
         "/root/.ssh/config"
         :content (str "StrictHostKeyChecking=" (yes-or-no host-key-checking))
         :mode 600))))

(def *USER* nil)

(defn base-server-spec []
  (server-spec
   :phases {:bootstrap (fn [req] (automated-admin-user/automated-admin-user
                                  req
                                  (:username *USER*)
                                  (:public-key-path *USER*)))
            :configure (phase
                        (java/java :sun :jdk))}))

(defn zookeeper-server-spec []
     (server-spec
      :extends (base-server-spec)
      :phases {:configure (phase
                           (zookeeper/install :version "3.3.3")
                           (zookeeper/configure
                            :clientPort (storm-conf "storm.zookeeper.port")
                            :maxClientCnxns 0)
                           (zookeeper/init))
                           }))

(defn storm-base-server-spec [name]
     (server-spec
      :extends (base-server-spec)
      :phases {:post-configure (phase
                                (storm/write-storm-yaml
                                 name
                                 storm-yaml-path))
               :configure (phase
                           (configure-ssh-client :host-key-checking false))
               :exec (phase
                      (storm/exec-daemon)
                      (ganglia/ganglia-finish))}))

(defn supervisor-server-spec [name]
     (server-spec
      :extends (storm-base-server-spec name)
      :phases {:configure (phase
                           (ganglia/ganglia-node (nimbus-name name))
                           (storm/install-supervisor
                            nil
                            "/mnt/storm"))
               :post-configure (phase
                                (ganglia/ganglia-finish)
                                (storm/write-storm-exec
                                 "supervisor"))}))

(defn nimbus-server-spec [name]
     (server-spec
      :extends (storm-base-server-spec name)
      :phases {:configure (phase
                           (ganglia/ganglia-master (nimbus-name name))
                           (storm/install-nimbus
                            nil
                            "/mnt/storm")
                           (storm/install-ui))
               :post-configure (phase
                                (ganglia/ganglia-finish)
                                (storm/write-storm-exec
                                 "nimbus")
                                 )}))

(defn node-spec-from-config [group-name inbound-ports]
  (letfn [(assoc-with-conf-key [image image-key conf-key & {:keys [f] :or {f identity}}]
            (if-let [val (clusters-conf (str group-name "." conf-key))]
              (assoc image image-key (f val))
              image))]
       (node-spec
        :image (-> {:inbound-ports (concat inbound-ports [22])
                    ;; :security-groups ["backend"]
                    }
                   (assoc-with-conf-key :image-id "image")
                   (assoc-with-conf-key :hardware-id "hardware")
                   (assoc-with-conf-key :spot-price "spot.price" :f float)
                   ))))

(defn zookeeper
  ([name server-spec]
     (group-spec
      (str "zookeeper-" name)
      :node-spec (node-spec-from-config "zookeeper"
                                        [(storm-conf "storm.zookeeper.port")])
      :extends server-spec))
  ([name]
     (zookeeper name (zookeeper-server-spec))
    ))

(defn nimbus
  ([name server-spec]
     (group-spec
      (nimbus-name name)
      :node-spec (node-spec-from-config "nimbus"
                                        [(storm-conf "nimbus.thrift.port")])
      :extends server-spec))
  ([name]
    (nimbus name (nimbus-server-spec name))
    ))

(defn supervisor
  ([name server-spec]
     (group-spec
      (str "supervisor-" name)
      :node-spec (node-spec-from-config "supervisor"
                                        (storm-conf "supervisor.slots.ports"))
      :extends server-spec))
 ([name]
   (supervisor name (supervisor-server-spec name))
   ))


