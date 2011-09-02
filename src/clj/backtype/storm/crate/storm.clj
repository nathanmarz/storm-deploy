(ns backtype.storm.crate.storm
  (:use [clojure.contrib.def :only [defnk]]
        [pallet.compute :only [running? primary-ip]]
        [org.jclouds.compute :only [nodes-with-tag]])
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
   ))

(defn make [request github-private-key]
  (->
   request
   (install-github-key "root" github-private-key)

  (exec-script/exec-checked-script
    "Build storm"

    (cd "$HOME")
    (if-not (directory? "storm")
      (git clone "git@github.com:nathanmarz/storm"))

    (cd storm)
    (git pull)
    (export "LEIN_ROOT=1")
    (lein deps)
    (lein compile)
    (lein jar)
    (rm "-rf src/dev") ; get rid of dev resources not suitable for production 
    )
   (directory/directory "$HOME/storm/logs" :owner "storm" :mode "700")
   (directory/directory "$HOME/storm/supervise" :owner "storm" :mode "700")
   ))

(defn install-supervisor [request local-dir-path github-private-key]
  (->
   request
   (install-dependencies)
   (directory/directory local-dir-path :owner "storm" :mode "700")

   (make github-private-key)))

(defn write-ui-exec [request path]
  (-> request
      (remote-file/remote-file
       path
       :content (str
                 "#!/bin/bash\n\n
              export LD_LIBRARY_PATH=/usr/local/lib\n\n
              cd $HOME/storm\n\n
              export LEIN_ROOT=1\n
              lein ring server-headless 8080")
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
    (exec-script/exec-script
      (cd "$HOME/storm")
      ;; This is because compojure/jetty work differently in production mode than development mode... need to be in resources/ directory
      (rm "-rf" "conf/public")
      (cp "-R" "src/ui/public" "conf/"))
    ))

(defn install-nimbus [request local-dir-path github-private-key]
  (->
   request
   (directory/directory local-dir-path :owner "storm" :mode "700")
   (install-dependencies)
   (make github-private-key)))

(defn exec-daemon [request]
  (->
   request
   (exec-script/exec-script
    (cd "$HOME/storm")
    "ps ax | grep backtype.storm | grep -v grep | awk '{print $1}' | xargs kill -9\n\n"
    "sudo -u storm -H nohup supervise . &"
    (cd "$HOME/ui")
    "sudo -u storm -H nohup supervise . &")))

(defnk write-storm-exec [request name klass :java-opts "" :args ""]
  (-> request
    (remote-file/remote-file
      "$HOME/storm/run"
      :content (str
                "#!/bin/bash\n\n
                export LD_LIBRARY_PATH=/usr/local/lib\n\n
                java -server " java-opts
                " -Dlogfile.name=" name ".log"
                " -cp `lein classpath`:log4j/:conf/ " klass " " args)
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

