(ns backtype.storm.provision
  (:import [java.io File])
  (:use [clojure.tools.cli :only [cli]]
        [pallet.compute :exclude [admin-user]]
        [backtype.storm security]
        [pallet.core]
        [org.jclouds.compute2 :only [nodes-in-group]]
        [backtype.storm.util :only [with-var-roots]]
        [clojure.tools.logging]
        )
  (:require [pallet.configure])
  (:require [pallet.compute.jclouds])
  (:require [backtype.storm.node :as node])
  (:require [backtype.storm.crate.storm :as storm])
  (:require [backtype.storm.deploy-util :as util]))

(log-capture! "java.logging")

;; memoize this
(defn my-region []
  (-> (pallet.configure/pallet-config) :services :default :jclouds.regions)
  )

(defn jclouds-group [& group-pieces]
  (str "jclouds#"
       (apply str group-pieces)
       ;"#"
       ;(my-region)
       ))

(defn- print-ips-for-tag! [aws tag-str]
  (let [running-node (filter running? (map (partial pallet.compute.jclouds/jclouds-node->node aws) (nodes-in-group aws tag-str)))]
    (info (str "TAG:     " tag-str))
    (info (str "PUBLIC:  " (clojure.string/join ", " (map primary-ip running-node))))
    (info (str "PRIVATE: " (clojure.string/join ", " (map private-ip running-node))))))

(defn print-all-ips! [context]
  (let [name (context :name)
        aws (context :aws)
        all-tags [(str "zookeeper-" name) (str "nimbus-" name) (str "supervisor-" name)]
       ]
    (doseq [tag all-tags]
      (print-ips-for-tag! aws tag)
      )
    )
  )

(defn converge! [context sn zn nn]
  (let [name (context :name)
        aws (context :aws)
        release (context :release)
        ]
    (converge {(node/nimbus context) nn
               (node/supervisor context) sn
               (node/zookeeper context) zn
               }
              :compute aws
              )
    )
  )

(defn sync-storm-conf-dir [context]
  (let [name (context :name)
        aws (context :aws)
        storm-conf-dir (str (System/getProperty "user.home") "/.storm")
        storm-yaml (storm/mk-storm-yaml name (node/storm-yaml context) (node/clusters-conf context) aws :on-server false)
        supervisor-yaml (storm/mk-supervisor-yaml aws name :on-server false)
        ]
    (.mkdirs (File. storm-conf-dir))
    (spit (str storm-conf-dir "/storm.yaml") storm-yaml)
    (spit (str storm-conf-dir "/supervisor.yaml") supervisor-yaml)
    )
  )

(defn attach! [context]
  (let [name (context :name)
        aws (context :aws)]
    (info "Attaching to Available Cluster...")
    (sync-storm-conf-dir context)
    (authorizeme aws (jclouds-group "nimbus-" name) 80 (my-region))
    (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "nimbus.thrift.port") (my-region))
    (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "ui.port") (my-region))
    (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "drpc.port") (my-region))
    (authorizeme aws (jclouds-group "zookeeper-" name) (node/storm-conf "storm.zookeeper.port") (my-region))
    (authorizeme aws (jclouds-group "nimbus-" name) 22 (my-region))
    (authorizeme aws (jclouds-group "zookeeper-" name) 22 (my-region))
    (authorizeme aws (jclouds-group "supervisor-" name) 22 (my-region))
    (info "Attaching Complete.")))

(defn start-with-nodes! [context nimbus supervisor zookeeper]
  (let [name (context :name)
        aws (context :aws)
        nimbus (node/nimbus* context nimbus)
        supervisor (node/supervisor* context supervisor)
        zookeeper (node/zookeeper* context zookeeper)
        sn (int ((node/clusters-conf context) :supervisor.count 1))
        zn (int ((node/clusters-conf context) :zookeeper.count 1))]
    (info (format "Provisioning nodes [nn=1, sn=%d, zn=%d]" sn zn))
    (converge {nimbus 1
              supervisor sn
              zookeeper zn
              }
            :compute aws
            )
    (debug "Finished converge")

    (authorize-group aws (my-region) (jclouds-group "nimbus-" name) (jclouds-group "supervisor-" name))
    (authorize-group aws (my-region) (jclouds-group "supervisor-" name) (jclouds-group "nimbus-" name))
    (authorize-group aws (my-region) (jclouds-group "zookeeper-" name) (jclouds-group "nimbus-" name))
    (authorize-group aws (my-region) (jclouds-group "zookeeper-" name) (jclouds-group "supervisor-" name))
    (authorize-group aws (my-region) (jclouds-group "supervisor-" name) (jclouds-group "zookeeper-" name))
    (authorize-group aws (my-region) (jclouds-group "nimbus-" name) (jclouds-group "zookeeper-" name))
    (debug "Finished authorizing groups")

    (lift nimbus :compute aws :phase [:post-configure :exec])
    (lift supervisor :compute aws :phase [:post-configure :exec])
    (debug "Finished post-configure and exec phases")

    (revoke aws (jclouds-group "nimbus-" name) 22 :region (my-region))
    (revoke aws (jclouds-group "zookeeper-" name) 22 :region (my-region))
    (revoke aws (jclouds-group "supervisor-" name) 22 :region (my-region))
    (attach! context)
    (info "Provisioning Complete.")
    (print-all-ips! context)))

(defn start! [context]
  (let [release (context :release)]
    (println "Starting cluster with release" release)
    (start-with-nodes! context (node/nimbus-server-spec context) (node/supervisor-server-spec context) (node/zookeeper-server-spec context))))

(defn stop! [context]
  (println "Shutting Down nodes...")
  (converge! context 0 0 0)
  (println "Shutdown Finished."))

(defn mk-aws []
  (let [storm-conf (-> (storm/storm-config "default")
                       (update-in [:environment :user] util/resolve-keypaths)
                       )
                   ]
    (compute-service-from-map storm-conf)))

(defn -main [& args]
  (let [aws (mk-aws)
        user (-> (storm/storm-config "default")
                 :environment
                 :user
                 (util/resolve-keypaths))
        ]
    (System/setProperty "jna.nosys" "true")
    (with-var-roots [node/*USER* user]
      (let [[opts args banner] (cli args
                                  "Provisioning tool for Storm clusters"
                                  ["--start" "Start cluster" :flag true :default false]
                                  ["--stop" "Stop cluster" :flag true :default false]
                                  ["--attach" "Attach to cluster" :flag true :default false]
                                  ["--show-ips" "Print cluster IP addresses" :flag true :default false]
                                  ["--name" "Cluster name" :default "dev"]
                                  ["--confdir" "Conf directory location" :default "conf"]
                                  ["--clusters-config" "Hashmap of custom cluster config options"
                                    :parse-fn read-string]
                                  ["--storm-config" "Hashmap of custom storm config options"
                                    :parse-fn read-string])
            context (merge opts {:release "0.9.0" ;current version we're set up to work with
                                    :aws aws})]
        (cond
          (opts :stop) (stop! context)
          (opts :start) (start! context)
          (opts :attach) (attach! context)
          (opts :show-ips) (print-all-ips! context)
          :else (println args (context :clusters-config) banner))))
  (shutdown-agents)
  (println "Done.")
  (System/exit 0)))

;; DEBUGGING
(comment
(use 'backtype.storm.provision)
(ns backtype.storm.provision)
(def aws (mk-aws))
(lift (node/supervisor "test" nil) :compute aws :phase [:post-configure] )
(sync-storm-conf-dir aws)
(print-all-ips! aws)
)
