(ns backtype.storm.provision
  (:import [java.io File])
  (:use [clojure.contrib.command-line]
        [pallet.compute :exclude [running?]]
        [pallet.configure :only [pallet-config]]
        [backtype.storm security]
        [pallet.core]
        [org.jclouds.compute :only [running? public-ips private-ips nodes-with-tag]]
        [backtype.storm.util :only [with-var-roots]])
  (:require [backtype.storm.node :as node])
  (:require [backtype.storm.crate.storm :as storm])
  (:require [backtype.storm.deploy-util :as util]))

;; memoize this
(defn my-region []
  (-> (pallet-config) :services :default :jclouds.regions)
  )

(defn jclouds-group [& group-pieces]
  (str "jclouds#"
       (apply str group-pieces)
       "#"
       (my-region)
       ))

(defn- print-ips-for-tag! [aws tag-str]
  (let [running-node (filter running? (nodes-with-tag tag-str aws))]
    (println "TAG:     " tag-str)
    (println "PUBLIC:  " (map public-ips running-node))
    (println "PRIVATE: " (map private-ips running-node))))

(defn print-all-ips! [aws name]
  (let [all-tags [(str "zookeeper-" name) (str "nimbus-" name) (str "supervisor-" name)]]
       (doseq [tag all-tags]
         (print-ips-for-tag! aws tag))))

(defn converge! [name release aws sn zn nn]
  (converge {(node/nimbus name release) nn
             (node/supervisor name release) sn
             (node/zookeeper name) zn
             }
            :compute aws))

(defn sync-storm-conf-dir [aws name]
  (let [conf-dir (str (System/getProperty "user.home") "/.storm")
        storm-yaml (storm/mk-storm-yaml name node/storm-yaml-path aws)
        supervisor-yaml (storm/mk-supervisor-yaml aws name)]
    (.mkdirs (File. conf-dir))
    (spit (str conf-dir "/storm.yaml") storm-yaml)
    (spit (str conf-dir "/supervisor.yaml") supervisor-yaml)))

(defn attach! [aws name]
  (println "Attaching to Available Cluster...")
  (sync-storm-conf-dir aws name)
  (authorizeme aws (jclouds-group "nimbus-" name) 80 (my-region))
  (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "nimbus.thrift.port") (my-region))
  (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "ui.port") (my-region))
  (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "drpc.port") (my-region))
  (println "Attaching Complete."))

(defn start-with-nodes! [aws name nimbus supervisor zookeeper]
  (let [nimbus (node/nimbus* name nimbus)
        supervisor (node/supervisor* name supervisor)
        zookeeper (node/zookeeper name zookeeper)
        sn (int (node/clusters-conf "supervisor.count" 1))
        zn (int (node/clusters-conf "zookeeper.count" 1))]
    (println (format "Provisioning nodes [nn=1, sn=%d, zn=%d]" sn zn))
    (converge {nimbus 1
              supervisor sn
              zookeeper zn
              }
            :compute aws
            )
    (authorize-group aws (my-region) (jclouds-group "nimbus-" name) (jclouds-group "supervisor-" name))
    (authorize-group aws (my-region) (jclouds-group "supervisor-" name) (jclouds-group "nimbus-" name))

    (lift nimbus :compute aws :phase [:post-configure :exec])
    (lift supervisor :compute aws :phase [:post-configure :exec])
    (attach! aws name)
    (println "Provisioning Complete.")
    (print-all-ips! aws name)))

(defn start! [aws name release]
  (println "Starting cluster with release" release)
  (start-with-nodes! aws name (node/nimbus-server-spec name release) (node/supervisor-server-spec name release) (node/zookeeper-server-spec))
  )

(defn upgrade-with-nodes! [aws name nimbus supervisor zookeeper]
  (let [nimbus (node/nimbus* name nimbus)
        supervisor (node/supervisor* name supervisor)
        zookeeper (node/zookeeper name zookeeper)]
;    (authorize-group aws (my-region) (jclouds-group "nimbus-" name) (jclouds-group "supervisor-" name))
;    (authorize-group aws (my-region) (jclouds-group "supervisor-" name) (jclouds-group "nimbus-" name))

;    (lift zookeeper :compute aws :phase [:configure])
;    (lift nimbus :compute aws :phase [:configure :post-configure :exec])
    (lift supervisor :compute aws :phase [:configure :post-configure :exec])
    (println "Upgrade Complete.")))


(defn upgrade! [aws name release]
  (println "Upgrading cluster with release" release)
  (upgrade-with-nodes! aws name (node/nimbus-server-spec name release) (node/supervisor-server-spec name release) (node/zookeeper-server-spec))
  )

(defn stop! [aws name]
  (println "Shutting Down nodes...")
  (converge! name nil aws 0 0 0)
  (println "Shutdown Finished."))

(defn mk-aws []
  (let [storm-conf (-> (storm/storm-config "default")
                       (update-in [:environment :user] util/resolve-keypaths))]
    (compute-service-from-map storm-conf)))

(defn -main [& args]
  (let [aws (mk-aws)
        user (-> (storm/storm-config "default")
                 :environment
                 :user
                 (util/resolve-keypaths))
        ]
    (with-var-roots [node/*USER* user]
      (with-command-line args
        "Provisioning tool for Storm Clusters"
        [[start? "Start Cluster?"]
         [stop? "Shutdown Cluster?"]
         [attach? "Attach to Cluster?"]
         [upgrade? "Upgrade existing cluster"]
         [ips? "Print Cluster IP Addresses?"]
         [name "Cluster name" "dev"]
         [release "Release version" nil]]

        (cond 
         stop? (stop! aws name)
         start? (start! aws name release)
         upgrade? (upgrade! aws name release)
         attach? (attach! aws name)
         ips? (print-all-ips! aws name)
         :else (println "Must pass --start or --stop or --attach"))))))

;; DEBUGGING
(comment
(use 'backtype.storm.provision)
(ns backtype.storm.provision)
(def aws (mk-aws))
(lift (node/supervisor "test" nil) :compute aws :phase [:post-configure] )
(sync-storm-conf-dir aws)
(print-all-ips! aws)
)
