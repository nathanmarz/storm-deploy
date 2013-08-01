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
   [backtype.storm.crate.newrelic :as newrelic]

   [pallet.crate.git :as git]
   [pallet.crate.maven :as maven]
   [pallet.crate.java :as java]
   [pallet.crate.automated-admin-user :as automated-admin-user]
   [backtype.storm.crate.zookeeper :as zookeeper]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.action.service :as action-service]
   [pallet.crate.splunk :as splunk]

   [pallet.resource.directory :as directory]
   [pallet.resource.service :as service]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.exec-script :as exec-script]

   [clojure.contrib.java-utils :as java-utils])
  (:use
   [backtype.storm config]
   [pallet compute core resource phase]
   [pallet [utils :only [make-user]]]
   [org.jclouds.compute2 :only [nodes-in-group]]
   [clojure.walk]))

(defn parse-release [release]
  (map #(Integer/parseInt %) (.split release "\\.")))

(defn release> [release1 release2]
  (->> (map - (parse-release release1) (parse-release release2))
       (take-while #(>= % 0))
       (some pos?)))

(defn conf-filename [name filename]
  (let [full-filename (str name "/" filename)]
    (if (.exists (File. (str "conf/" full-filename)))
        full-filename
        (conf-filename "default" filename)
        )
    )
  )

(defn clusters-conf [name]
  (read-yaml-config (conf-filename name "clusters.yaml"))
  )

(defn storm-yaml-path [name]
  (.getPath (ClassLoader/getSystemResource (conf-filename name "storm.yaml")))
  )

(defn storm-log-properties-path [name]
  (.getPath (ClassLoader/getSystemResource (conf-filename name "storm.log.properties")))
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

(defn base-server-spec [name]
  (server-spec
   :phases {:bootstrap (fn [req] (automated-admin-user/automated-admin-user
                                  req
                                  (:username *USER*)
                                  (:public-key-path *USER*)))
            :configure (phase-fn
                         (java/java :openjdk)
                         (newrelic/install)
                         (newrelic/configure ((clusters-conf name) "newrelic.licensekey"))
                         (newrelic/init))}))

(defn zookeeper-server-spec [name]
     (server-spec
      :extends (base-server-spec name)
      :phases {:configure (phase-fn
                           (zookeeper/install :version "3.3.5")
                           (zookeeper/configure
                            :clientPort (storm-conf "storm.zookeeper.port")
                            :maxClientCnxns 0)
                           (zookeeper/init))
                           }))

(defn storm-base-server-spec [name]
     (server-spec
      :extends (base-server-spec name)
      :phases {:post-configure (phase-fn
                                (storm/write-storm-yaml
                                 name
                                 (storm-yaml-path name)
                                 (clusters-conf name)))
               :configure (phase-fn
                           (configure-ssh-client :host-key-checking false)
                           ((fn [session]
                               (if (.exists (java-utils/file "conf/credentials.spl"))
                                   (splunk/splunk session
                                                  :forwarder true
                                                  :inputs {"monitor:///home/storm/storm/logs/worker*.log" {:sourcetype "worker"}
                                                           "monitor:///home/storm/storm/logs/supervisor*.log" {:sourcetype "supervisor"}
                                                           "monitor:///home/storm/storm/logs/nimbus*.log" {:sourcetype "nimbus"}
                                                           "monitor:///home/storm/storm/logs/drpc*.log" {:sourcetype "drpc"}
                                                           "monitor:///home/storm/storm/logs/ui*.log" {:sourcetype "ui"} }
                                                  :credentials "conf/credentials.spl")
                                   session))))
               :exec (phase-fn
                      (storm/exec-daemon)
                      (ganglia/ganglia-finish))}))

(defn supervisor-server-spec [name release]
     (server-spec
      :extends (storm-base-server-spec name)
      :phases {:configure (phase-fn
                           (ganglia/ganglia-node (nimbus-name name))
                           (storm/install-supervisor
                            release
                            "/mnt/storm")
                           (storm/write-storm-log-properties 
                            (storm-log-properties-path name)))
               :post-configure (phase-fn
                                (ganglia/ganglia-finish)
                                (storm/write-storm-exec
                                 "supervisor"))}))

(defn maybe-install-drpc [req release]
  (if (or (not release) (release> release "0.5.3"))
    (storm/install-drpc req)
    req
    ))

(defn maybe-exec-drpc [req release]
  (if (or (not release) (release> release "0.5.3"))
    (storm/exec-drpc req)
    req
    ))

(defn nimbus-server-spec [name release]
     (server-spec
      :extends (storm-base-server-spec name)
      :phases {:configure (phase-fn
                           (ganglia/ganglia-master (nimbus-name name))
                           (storm/install-nimbus
                            release
                            "/mnt/storm")
                           (storm/write-storm-log-properties 
                            (storm-log-properties-path name))
                           (storm/install-ui)
                           (maybe-install-drpc release))
               :post-configure (phase-fn
                                (ganglia/ganglia-finish)
                                (storm/write-storm-exec
                                 "nimbus")
                                 )
               :exec (phase-fn
                        (storm/exec-ui)
                        (maybe-exec-drpc release))}))

(defn node-spec-from-config [group-name name inbound-ports]
  (letfn [(assoc-with-conf-key [image image-key conf-key & {:keys [f] :or {f identity}}]
            (if-let [val ((clusters-conf name) (str group-name "." conf-key))]
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
                                        name
                                        ;[(storm-conf "storm.zookeeper.port")])
                                        [])
      :extends server-spec))
  ([name]
     (zookeeper name (zookeeper-server-spec name))
    ))

(defn nimbus* [name server-spec]
  (group-spec
    (nimbus-name name)
    :node-spec (node-spec-from-config "nimbus"
                                      name
                                      ;[(storm-conf "nimbus.thrift.port")])
                                      [])
    :extends server-spec))

(defn nimbus [name release]
  (nimbus* name (nimbus-server-spec name release)))

(defn supervisor* [name server-spec]
  (group-spec
    (str "supervisor-" name)
    :node-spec (node-spec-from-config "supervisor"
                                      name
                                      ;(storm-conf "supervisor.slots.ports"))
                                      [])
    :extends server-spec))

(defn supervisor [name release]
  (supervisor* name (supervisor-server-spec name release)))


