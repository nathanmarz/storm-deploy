(ns backtype.storm.node
  (:import
   [java.util Map List]
   [org.jvyaml YAML]
   [java.io FileReader File])
  (:require
   [backtype.storm.crate.ganglia :as ganglia]
   [backtype.storm.crate.storm :as storm]
   [backtype.storm.crate.zookeeper :as zookeeper]
   [backtype.storm.defaults :as defaults]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [pallet.crate.java :as java]
   [pallet.resource.remote-file :as remote-file])
  (:use
   [backtype.storm config]
   [backtype.storm.branch :only [branch>]]
   [org.jclouds.compute2 :only [nodes-in-group]]
   [pallet compute core resource phase]
   [pallet [utils :only [make-user]]]
   [clojure.walk]))

;; CONSTANTS

(def clusters-conf
  (read-yaml-config "clusters.yaml"))

(def storm-yaml-path
  (.getPath (ClassLoader/getSystemResource "storm.yaml")))

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

(defn base-server-spec [method]
  (server-spec
   :phases {:bootstrap (fn [req] (automated-admin-user/automated-admin-user
                                  req
                                  (:username *USER*)
                                  (:public-key-path *USER*)))
            :configure (phase-fn
                        (java/java :openjdk))}))

(defn zookeeper-server-spec [method]
  (let [zookeeper-version (defaults/default [:zookeeper :version])]
    (server-spec
     :extends (base-server-spec method)
     :phases {:configure (phase-fn
                          (zookeeper/install :version zookeeper-version)
                          (zookeeper/configure
                           :clientPort (storm-conf "storm.zookeeper.port")
                           :maxClientCnxns 0)
                          (zookeeper/init))})))

(defn storm-base-server-spec [name method]
     (server-spec
      :extends (base-server-spec method)
      :phases {:post-configure (phase-fn
                                (storm/write-storm-yaml
                                 name
                                 storm-yaml-path))
               :configure (phase-fn
                           (configure-ssh-client :host-key-checking false))
               :exec (phase-fn
                      (storm/exec-daemon)
                      (ganglia/ganglia-finish))}))

(defn supervisor-server-spec [name branch commit method]
     (server-spec
      :extends (storm-base-server-spec name method)
      :phases {:configure (phase-fn
                           (ganglia/ganglia-node (nimbus-name name))
                           (storm/install-supervisor
                            branch commit method
                            "/mnt/storm"))
               :post-configure (phase-fn
                                (ganglia/ganglia-finish)
                                (storm/write-storm-exec
                                 "supervisor"))}))

(defn maybe-install-drpc [req branch]
  (if (or (not branch) (= branch "master") (branch> branch "0.5.3"))
    (storm/install-drpc req)
    req))

(defn maybe-exec-drpc [req branch]
  (if (or (not branch) (= branch "master") (branch> branch "0.5.3"))
    (storm/exec-drpc req)
    req))

(defn nimbus-server-spec [name branch commit method]
     (server-spec
      :extends (storm-base-server-spec name method)
      :phases {:configure (phase-fn
                           (ganglia/ganglia-master (nimbus-name name))
                           (storm/install-nimbus
                            branch commit method
                            "/mnt/storm")
                           (storm/install-ui)
                           (maybe-install-drpc branch))
               :post-configure (phase-fn
                                (ganglia/ganglia-finish)
                                (storm/write-storm-exec
                                 "nimbus"))
               :exec (phase-fn
                        (storm/exec-ui)
                        (maybe-exec-drpc branch))}))

(defn node-spec-from-config [group-name inbound-ports]
  (letfn [(assoc-with-conf-key
            [image image-key conf-key & {:keys [f] :or {f identity}}]
            (if-let [val (clusters-conf (str group-name "." conf-key))]
              (assoc image image-key (f val))
              image))]
    (node-spec
     :image (-> {:inbound-ports (concat inbound-ports [22])
                 ;; :security-groups ["backend"]
                 }
                (assoc-with-conf-key :image-id "image")
                (assoc-with-conf-key :spot-price "spot.price" :f float))
     :hardware (-> {}
                (assoc-with-conf-key :hardware-id "hardware")
))))

(defn zookeeper
  ([name server-spec method]
     (group-spec
         (str "zookeeper-" name)
       :node-spec (node-spec-from-config
                   "zookeeper"
                   [(storm-conf "storm.zookeeper.port")])
       :extends server-spec))
  ([name method]
     (zookeeper name (zookeeper-server-spec method) method )))

(defn nimbus* [name server-spec]
  (group-spec
    (nimbus-name name)
    :node-spec (node-spec-from-config
                "nimbus"
                [(storm-conf "nimbus.thrift.port")])
    :extends server-spec))

(defn nimbus [name branch commit method]
  (nimbus* name (nimbus-server-spec name branch commit method)))

(defn supervisor* [name server-spec]
  (group-spec
    (str "supervisor-" name)
    :node-spec (node-spec-from-config
                "supervisor"
                (storm-conf "supervisor.slots.ports"))
    :extends server-spec))

(defn supervisor [name branch commit method]
  (supervisor* name (supervisor-server-spec name branch commit method)))


