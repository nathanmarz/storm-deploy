(ns backtype.storm.crate.storm
  (:use [pallet.compute :only [running? primary-ip private-ip]]
        [pallet.compute.jclouds]
        [org.jclouds.compute2 :only [nodes-in-group]]
        [pallet.configure :only [compute-service-properties pallet-config]]
        [backtype.storm.branch :only [branch>]])
  (:require
   [backtype.storm.crate.zeromq :as zeromq]
   [backtype.storm.crate.leiningen :as leiningen]
   [pallet.crate.git :as git]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.java :as java]
   [pallet.resource.package :as package]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.action.exec-script :as exec-script]))

(defn storm-config
  ([] (storm-config "default"))
  ([conf-name] (compute-service-properties (pallet-config) [conf-name])))

(defn nimbus-ip [compute name]
  (let [running-nodes (filter running? (map (partial jclouds-node->node compute) (nodes-in-group compute (str "nimbus-" name))))]
    (assert (= (count running-nodes) 1))
    (primary-ip (first running-nodes))))

(defn nimbus-private-ip [compute name]
  (let [running-nodes (filter running? (map (partial jclouds-node->node compute) (nodes-in-group compute (str "nimbus-" name))))]
    (assert (= (count running-nodes) 1))
    (private-ip (first running-nodes))))


(defn zookeeper-ips [compute name]
  (let [running-nodes (filter running?
    (map (partial jclouds-node->node compute) (nodes-in-group compute (str "zookeeper-" name))))]
    (map primary-ip running-nodes)))

(defn supervisor-ips [compute name]
  (let [running-nodes (filter running?
    (map (partial jclouds-node->node compute) (nodes-in-group compute (str "supervisor-" name))))]
    (map primary-ip running-nodes)))

(defn- install-dependencies [request branch]
  (->
   request
   (java/java :openjdk)
   (git/git)
   (leiningen/install (if (or (not branch) (= branch "master") (branch> branch "0.9.0")) 2 1))
   (zeromq/install :version "2.1.4")
   (zeromq/install-jzmq :version "2.1.0")
   (package/package "daemontools")
   (package/package "unzip")
   (package/package "zip")
   ))

(defn get-release [request branch commit]
  (let [url "git://github.com/nathanmarz/storm.git"
       sha1 (if (empty? commit) "" commit)] ; empty string for pallet

    (-> request
      (exec-script/exec-checked-script
        "Build storm"
        (cd "$HOME")
        (mkdir -p "build")
        (cd "$HOME/build")

        (when-not (directory? "storm")
          (git clone -b ~branch ~url ))

        (cd storm)
        (git pull)
        (if (not (empty? ~sha1)) 
          (git checkout -b newbranch ~sha1)
          )
        (bash "bin/build_release.sh")
        (cp "*.zip $HOME/")))))

(defn make [request branch commit]
  (->
   request
   (exec-script/exec-checked-script
     "clean up home"
     (cd "$HOME")
     (rm "-f *.zip"))
   (get-release branch commit)
   (exec-script/exec-checked-script
     "prepare daemon"
     (cd "$HOME")
     (unzip "-o *.zip")
     (rm "-f storm")
     (ln "-s $HOME/`ls | grep zip | sed s/.zip//` storm")

     (mkdir -p "daemon")
     (mkdir -p "$HOME/storm/log4j")
     (chmod "755" "$HOME/storm/log4j")
     (touch "$HOME/storm/log4j/storm.log.properties")
     (touch "$HOME/storm/log4j/log4j.properties")
     (chmod "755" "$HOME/storm/log4j/storm.log.properties")
     (chmod "755" "$HOME/storm/log4j/log4j.properties")
     )
    (directory/directory "$HOME/daemon/supervise" :owner "storm" :mode "700")
    (directory/directory "$HOME/storm/logs" :owner "storm" :mode "700")
    (directory/directory "$HOME/storm/bin" :mode "755")
    ))

(defn install-supervisor [request branch commit local-dir-path]
  (->
   request
   (install-dependencies branch)
   (directory/directory local-dir-path :owner "storm" :mode "700")

   (make branch commit)))

(defn write-ui-exec [request path]
  (-> request
      (remote-file/remote-file
       path
       ;;TODO: need to replace $HOME with the hardcoded absolute path
       :content (str
                 "#!/bin/bash\n\n
              cd $HOME/storm\n\n
              python bin/storm ui")
       :overwrite-changes true
       :literal true
       :mode 755)
      ))

(defn write-drpc-exec [request path]
  (-> request
      (remote-file/remote-file
       path
       ;;TODO: need to replace $HOME with the hardcoded absolute path
       :content (str
                 "#!/bin/bash\n\n
              cd $HOME/storm\n\n
              python bin/storm drpc")
       :overwrite-changes true
       :literal true
       :mode 755)
      ))

(defn install-ui [request]
  (-> request
    (directory/directory "$HOME/ui" :owner "storm" :mode "700")
    (directory/directory "$HOME/ui/logs" :owner "storm" :mode "700")
    (directory/directory "$HOME/ui/supervise" :owner "storm" :mode "700")
    (write-ui-exec "$HOME/ui/run")
    ))

(defn install-drpc [request]
  (-> request
    (directory/directory "$HOME/drpc" :owner "storm" :mode "700")
    (directory/directory "$HOME/drpc/logs" :owner "storm" :mode "700")
    (directory/directory "$HOME/drpc/supervise" :owner "storm" :mode "700")
    (write-drpc-exec "$HOME/drpc/run")
    ))

(defn install-nimbus [request branch commit local-dir-path]
  (->
   request
   (directory/directory local-dir-path :owner "storm" :mode "700")
   (install-dependencies branch)
   (make branch commit)))

(defn exec-daemon [request]
  (->
   request
   (exec-script/exec-script
    (cd "$HOME/daemon")
    "ps ax | grep backtype.storm | grep -v grep | awk '{print $1}' | xargs kill -9\n\n"
    "sudo -u storm -H nohup supervise . &"
    )))

(defn exec-ui [request]
  (exec-script/exec-script request
    "sudo -u storm -H nohup supervise ~storm/ui > nohup-ui.log &"
    ))

(defn exec-drpc [request]
  (exec-script/exec-script request
    "sudo -u storm -H nohup supervise ~storm/drpc > nohup-drpc.log &"
    ))

(defn write-storm-exec [request name]
  (-> request
    (remote-file/remote-file
      "$HOME/daemon/run"
      :content (str
                "#!/bin/bash\n\n
                cd $HOME/storm\n\n
                python bin/storm " name)
      :overwrite-changes true
      :literal true
      :mode 755)
      ))

(defn mk-storm-yaml [name local-storm-file compute]
  (let [newline-join #(apply str (interpose "\n" (apply concat %&)))]
    (str
      (slurp local-storm-file)
      "\n"
      (newline-join
       ["storm.zookeeper.servers:"]
       (concat (map #(str "  - \"" % "\"") (zookeeper-ips compute name)))
       []
       [(str "nimbus.host: \"" (nimbus-ip compute name) "\"")]
       ["drpc.servers:"]
       [(str "  - \"" (nimbus-private-ip compute name) "\"")]
       [(str "storm.local.dir: \"/mnt/storm\"")]))))

(defn mk-supervisor-yaml [compute name]
  (let [newline-join #(apply str (interpose "\n" (apply concat %&)))]
    (str
      (newline-join
       ["storm.supervisor.servers:"]
       (concat (map #(str "  - \"" % "\"") (supervisor-ips compute name)))
       []))))

(defn write-storm-yaml [request name local-storm-file]
      (-> request
          (remote-file/remote-file
           "$HOME/storm/conf/storm.yaml"
           :content (mk-storm-yaml name local-storm-file (:compute request))
           :overwrite-changes true)))

