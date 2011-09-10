(ns backtype.storm.crate.storm
  (:use [clojure.contrib.def :only [defnk]]
        [pallet.compute :only [running? primary-ip]]
        [org.jclouds.compute :only [nodes-with-tag]]
        [pallet.configure :only [compute-service-properties pallet-config]])
  (:require
   [backtype.storm.crate.zeromq :as zeromq]
   [backtype.storm.crate.leiningen :as leiningen]
   [pallet.crate.git :as git]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.java :as java]
   [pallet.resource.package :as package]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.exec-script :as exec-script]))

(defn storm-config
  ([] (storm-config "default"))
  ([conf-name] (compute-service-properties (pallet-config) [conf-name])))

(defn nimbus-ip [compute name]
  (let [running-nodes (filter running? (nodes-with-tag (str "nimbus-" name) compute))]
    (assert (= (count running-nodes) 1))
    (primary-ip (first running-nodes))))

(defn zookeeper-ips [compute name]
  (let [running-nodes (filter running?
                              (nodes-with-tag (str "zookeeper-" name) compute))]
    (map primary-ip running-nodes)))

(defn supervisor-ips [compute name]
  (let [running-nodes (filter running?
                              (nodes-with-tag (str "supervisor-" name) compute))]
    (map primary-ip running-nodes)))

(defn- install-github-key [request user private-key]
  (-> request
      (ssh-key/install-key
       user "id_rsa"
       (slurp private-key)
       "!superfluous!")))

(defn- install-dependencies [request]
  (->
   request
   (java/java :sun :jdk)
   (git/git)
   (leiningen/install)
   (zeromq/install :version "2.1.4")
   (zeromq/install-jzmq :version "2.1.0")
   (package/package "daemontools")
   (package/package "unzip")
   (package/package "zip")
   ))

(defn download-release [request release]
  (-> request
    ;; TODO: fill in
    ))

(defn build-release-from-head [request]
  (-> request
    (exec-script/exec-checked-script
      "Build storm"

      (cd "$HOME")
      (mkdir "build")
      (cd "$HOME/build")
      (if-not (directory? "storm")
        (git clone "git@github.com:nathanmarz/storm"))

      (cd storm)
      (git pull)
      (sh "bin/build_release.sh")
      (cp "*.zip $HOME/")
      )
    ))

(defn get-release [request release]
  (if release
    (download-release request release)
    (build-release-from-head request)
    ))

(defn make [request release github-private-key]
  (->
   request
   (install-github-key "root" github-private-key)
   (exec-script/exec-checked-script
     "clean up home"
     (cd "$HOME")
     (rm "-f *.zip"))
   (get-release release)
   (exec-script/exec-checked-script
     "prepare daemon"
     (cd "$HOME")
     (unzip "-o *.zip")
     (rm "-f storm")
     (ln "-s $HOME/`ls | grep zip | sed s/.zip//` storm")

     (mkdir "daemon"))
    (directory/directory "$HOME/daemon/supervise" :owner "storm" :mode "700")
    (directory/directory "$HOME/storm/logs" :owner "storm" :mode "700")
    ))

(defn install-supervisor [request release local-dir-path github-private-key]
  (->
   request
   (install-dependencies)
   (directory/directory local-dir-path :owner "storm" :mode "700")

   (make release github-private-key)))

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

(defn install-ui [request]
  (-> request
    (directory/directory "$HOME/ui" :owner "storm" :mode "700")
    (directory/directory "$HOME/ui/logs" :owner "storm" :mode "700")
    (directory/directory "$HOME/ui/supervise" :owner "storm" :mode "700")
    (write-ui-exec "$HOME/ui/run")
    ))

(defn install-nimbus [request release local-dir-path github-private-key]
  (->
   request
   (directory/directory local-dir-path :owner "storm" :mode "700")
   (install-dependencies)
   (make release github-private-key)))

(defn exec-daemon [request]
  (->
   request
   (exec-script/exec-script
    (cd "$HOME/daemon")
    "ps ax | grep backtype.storm | grep -v grep | awk '{print $1}' | xargs kill -9\n\n"
    "sudo -u storm -H nohup supervise . &"
    (cd "$HOME/ui")
    "sudo -u storm -H nohup supervise . &")))

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

