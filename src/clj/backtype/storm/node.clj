(ns backtype.storm.node
  (:import
   [java.io File])
  (:require
   [clojure.java.io :as io]
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

   [clj-yaml.core :as clj-yaml])
  (:use
   [backtype.storm config]
   [pallet compute core resource phase]
   [pallet [utils :only [make-user]]]
   [org.jclouds.compute2 :only [nodes-in-group]]
   [clojure.walk]))

(defn conf-filename [context filename]
  (let [name (context :name)
        confdir (context :confdir)
        full-filename (.getPath (io/file confdir name filename))]
    (if (.exists (io/file full-filename))
        full-filename
        (conf-filename (merge context {:name "default"}) filename))))

(defn generate-config-from-yaml [context filename]
  "Get default version of config, apply named conf if it exists, and apply commandline hash if supplied"
  (let [conf-name (first (clojure.string/split filename #"[.]"))
        conf-hash (context (keyword (str conf-name "-config")))]
    (merge 
      (clj-yaml/parse-string
        (slurp (conf-filename (merge context {:name "default"}) filename)))
      (clj-yaml/parse-string
        (slurp (conf-filename context filename)))
      conf-hash)))

(defn clusters-conf [context]
  (let [conf (generate-config-from-yaml context "clusters.yaml")]
    (println "clusters.yaml")
    (println conf)
    conf))

(defn storm-yaml [context]
  (generate-config-from-yaml context "storm.yaml"))

(defn storm-log-properties-path [context]
  (conf-filename context "storm.log.properties"))

(def storm-conf (read-storm-config))

(defn nimbus-name [context]
  (str "nimbus-" (context :name)))

(defn zookeeper-name [context]
  (str "zookeeper-" (context :name)))

(defn supervisor-name [context]
  (str "supervisor-" (context :name)))

(defn configure-ssh-client [request & {:keys [host-key-checking]}]
  (let [yes-or-no #(if % "yes" "no")]
    (-> request
        (remote-file/remote-file
         "/root/.ssh/config"
         :content (str "StrictHostKeyChecking=" (yes-or-no host-key-checking))
         :mode 600))))

(def *USER* nil)

(defn base-server-spec [context]
  (server-spec
   :phases {:bootstrap (fn [req] (automated-admin-user/automated-admin-user
                                  req
                                  (:username *USER*)
                                  (:public-key-path *USER*)))
            :configure (phase-fn
                         (java/java :openjdk)
                         (newrelic/install)
                         (newrelic/configure ((clusters-conf context) :newrelic.licensekey))
                         (newrelic/init))}))

(defn zookeeper-server-spec [context]
  (server-spec
   :extends (base-server-spec context)
   :phases {:configure (phase-fn
                        (zookeeper/install :version "3.3.6")
                        (zookeeper/configure
                         :clientPort (storm-conf "storm.zookeeper.port")
                         :maxClientCnxns 0)
                        (zookeeper/init))
                        }))

(defn storm-base-server-spec [context]
  (let [name (context :name)
        confdir (context :confdir)
        ]
    (server-spec
     :extends (base-server-spec context)
     :phases {:post-configure (phase-fn
                               (storm/write-storm-yaml
                                name
                                (storm-yaml context)
                                (clusters-conf context)))
              :configure (phase-fn
                          (configure-ssh-client :host-key-checking false)
                          ((fn [session]
                              (if (.exists (io/file confdir "credentials.spl"))
                                  (splunk/splunk session
                                                 :forwarder true
                                                 :inputs {"monitor:///home/storm/storm/logs/worker*.log" {:sourcetype "worker"}
                                                          "monitor:///home/storm/storm/logs/supervisor*.log" {:sourcetype "supervisor"}
                                                          "monitor:///home/storm/storm/logs/nimbus*.log" {:sourcetype "nimbus"}
                                                          "monitor:///home/storm/storm/logs/drpc*.log" {:sourcetype "drpc"}
                                                          "monitor:///home/storm/storm/logs/ui*.log" {:sourcetype "ui"} }
                                                 :credentials (.getPath (io/file confdir "credentials.spl")))
                                  session))))
              :exec (phase-fn
                     (storm/exec-daemon)
                     (ganglia/ganglia-finish))})))

(defn supervisor-server-spec [context]
  (server-spec
   :extends (storm-base-server-spec context)
   :phases {:configure (phase-fn
                        (ganglia/ganglia-node (nimbus-name context))
                        (storm/install-supervisor
                         (context :release)
                         "/mnt/storm")
                        (storm/write-storm-log-properties 
                         (storm-log-properties-path context)))
            :post-configure (phase-fn
                             (ganglia/ganglia-finish)
                             (storm/write-storm-exec
                              "supervisor"))}))

(defn nimbus-server-spec [context]
  (server-spec
   :extends (storm-base-server-spec context)
   :phases {:configure (phase-fn
                        (ganglia/ganglia-master (nimbus-name context))
                        (storm/install-nimbus
                         (context :release)
                         "/mnt/storm")
                        (storm/write-storm-log-properties 
                         (storm-log-properties-path context))
                        (storm/install-ui)
                        (storm/install-drpc))
            :post-configure (phase-fn
                             (ganglia/ganglia-finish)
                             (storm/write-storm-exec
                              "nimbus")
                              )
            :exec (phase-fn
                     (storm/exec-ui)
                     (storm/exec-drpc))}))

(defn node-spec-from-config [context group-name inbound-ports]
  (letfn [(assoc-with-conf-key [image image-key conf-key & {:keys [f] :or {f identity}}]
            (if-let [val ((clusters-conf context) (keyword (str group-name "." conf-key)))]
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

(defn zookeeper* [context server-spec]
  (group-spec
    (zookeeper-name context)
    :node-spec (node-spec-from-config context
                                      "zookeeper"
                                      [])
    :extends server-spec))

(defn zookeeper [context]
  (zookeeper* context (zookeeper-server-spec context)))

(defn nimbus* [context server-spec]
  (group-spec
    (nimbus-name context)
    :node-spec (node-spec-from-config context
                                      "nimbus"
                                      [])
    :extends server-spec))

(defn nimbus [context]
  (nimbus* context (nimbus-server-spec context)))

(defn supervisor* [context server-spec]
  (group-spec
    (supervisor-name context)
    :node-spec (node-spec-from-config context
                                      "supervisor"
                                      [])
    :extends server-spec))

(defn supervisor [context]
  (supervisor* context (supervisor-server-spec context)))


