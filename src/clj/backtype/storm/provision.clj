(ns backtype.storm.provision
  (:import [java.io File])
  (:use [clojure.contrib.command-line]
        [pallet.compute :exclude [admin-user]]
        [backtype.storm security]
        [pallet.core]
        [com.palletops.jclouds.compute2 :only [nodes-in-group]]
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
  (str "jclouds#" (apply str group-pieces)))

(defn- print-ips-for-tag! [aws tag-str]
  (let [running-node (filter running? (map (partial pallet.compute.jclouds/jclouds-node->node aws) (nodes-in-group aws tag-str)))]
    (info (str "TAG:     " tag-str))
    (info (str "PUBLIC:  " (clojure.string/join ", " (map primary-ip running-node))))
    (info (str "PRIVATE: " (clojure.string/join ", " (map private-ip running-node))))))

(defn print-all-ips! [aws name]
  (let [all-tags [(str "zookeeper-" name) (str "nimbus-" name) (str "supervisor-" name)]]
       (doseq [tag all-tags]
         (print-ips-for-tag! aws tag))))

(defn converge! [name branch commit aws sn zn nn]
  (converge {(node/nimbus name branch commit) nn
             (node/supervisor name branch commit) sn
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
  (info "Attaching to Available Cluster...")
  (sync-storm-conf-dir aws name)
  (authorizeme aws (jclouds-group "nimbus-" name) 80 (my-region))
  (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "nimbus.thrift.port") (my-region))
  (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "ui.port") (my-region))
  (authorizeme aws (jclouds-group "nimbus-" name) (node/storm-conf "drpc.port") (my-region))
  (info "Attaching Complete."))

(defn start-with-nodes! [aws name nimbus supervisor zookeeper]
  (let [nimbus (node/nimbus* name nimbus)
        supervisor (node/supervisor* name supervisor)
        zookeeper (node/zookeeper name zookeeper)
        sn (int (node/clusters-conf "supervisor.count" 1))
        zn (int (node/clusters-conf "zookeeper.count" 1))]
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
    (debug "Finished authorizing groups")

    (lift nimbus :compute aws :phase [:post-configure :exec])
    (lift supervisor :compute aws :phase [:post-configure :exec])
    (debug "Finished post-configure and exec phases")

    (attach! aws name)
    (info "Provisioning Complete.")
    (print-all-ips! aws name)))

(defn start! [aws name branch commit]
  (println "Starting cluster with storm branch " branch (if (empty? commit) "" (str " and commit " commit)))
  (start-with-nodes! aws name (node/nimbus-server-spec name branch commit) (node/supervisor-server-spec name branch commit) (node/zookeeper-server-spec))
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


(defn upgrade! [aws name branch commit]
  (println "Upgrading cluster with storm branch " branch (if (empty? commit) "" (str " and commit " commit)))
  (upgrade-with-nodes! aws name (node/nimbus-server-spec name branch commit) (node/supervisor-server-spec name branch commit) (node/zookeeper-server-spec))
  )

(defn stop! [aws name]
  (println "Shutting Down nodes...")
  (converge! name nil nil aws 0 0 0)
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
    (System/setProperty "jna.nosys" "true")
    (with-var-roots [node/*USER* user]
      (with-command-line args
        "Provisioning tool for Storm Clusters"
        [[start? "Start Cluster?"]
         [stop? "Shutdown Cluster?"]
         [attach? "Attach to Cluster?"]
         [upgrade? "Upgrade existing cluster"]
         [ips? "Print Cluster IP Addresses?"]
         [name "Cluster name" "dev"]
         [branch "Branch" "master"]; default branch is master.
         [commit "Commit SHA1" nil]]; default is not to pass a commit

        (cond
         stop? (stop! aws name)
         start? (start! aws name branch commit)
         upgrade? (upgrade! aws name branch commit)
         attach? (attach! aws name)
         ips? (print-all-ips! aws name)
         :else (println "Must pass --start, --stop , upgrade, --attach or --ips")))))
  (shutdown-agents)
  (println "Done.")
  (System/exit 0))

;; DEBUGGING
(comment
(use 'backtype.storm.provision)
(ns backtype.storm.provision)
(def aws (mk-aws))
(lift (node/supervisor "test" nil) :compute aws :phase [:post-configure] )
(sync-storm-conf-dir aws)
(print-all-ips! aws)
)
