(ns backtype.storm.provision
  (:import [java.io File])
  (:use [clojure.contrib.command-line]
        [pallet.compute]
        [pallet.configure]
        [backtype.storm security]
        [pallet.core]
        [org.jclouds.compute :only [nodes-with-tag]]
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
    (println "PUBLIC:  " (map primary-ip running-node))
    (println "PRIVATE: " (map private-ip running-node))))

(defn print-all-ips! [aws name]
  (let [all-tags [(str "zookeeper-" name) (str "nimbus-" name) (str "supervisor-" name)]]
       (doseq [tag all-tags]
         (print-ips-for-tag! aws tag))))

(defn converge! [name aws sn zn nn]
  (converge {(node/nimbus name) nn
             (node/supervisor name) sn
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
  (authorizeme aws (jclouds-group "nimbus-" name) 80)
  (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "nimbus.thrift.port"))
  (authorizeme aws (jclouds-group "nimbus-" name) 8080)


  ;; TODO: should probably move this out of this deploy
  (authorizeme aws (jclouds-group "nimbus-" name) 3772)  ;; drpc 
  (println "Attaching Complete."))

(defn start-with-nodes! [aws name nimbus supervisor zookeeper]
  (let [nimbus (node/nimbus name nimbus)
        supervisor (node/supervisor name supervisor)
        zookeeper (node/zookeeper name zookeeper)
        sn (int (node/clusters-conf "supervisor.count" 1))
        zn (int (node/clusters-conf "zookeeper.count" 1))]
    (println (format "Provisioning nodes [nn=1, sn=%d, zn=%d]..." sn zn))
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

(defn start! [aws name]
  (start-with-nodes! aws name (node/nimbus-server-spec name) (node/supervisor-server-spec name) (node/zookeeper-server-spec))
  )


(defn stop! [aws name]
  (println "Shutting Down nodes...")
  (converge! name aws 0 0 0)
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
         [ips? "Print Cluster IP Addresses?"]
         [name "Cluster name" "dev"]]

        (cond 
         stop? (stop! aws name)
         start? (start! aws name)
         attach? (attach! aws name)
         ips? (print-all-ips! aws name)
         :else (println "Must pass --start or --stop or --attach"))))))

;; DEBUGGING
(comment
(use 'backtype.storm.provision)
(ns backtype.storm.provision)
(def aws (mk-aws))
(lift (node/zookeeper "test") :compute aws :phase [:configure] )
(sync-storm-conf-dir aws)
(print-all-ips! aws)
)
