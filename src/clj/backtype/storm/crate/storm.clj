(ns backtype.storm.crate.storm
  (:use [pallet.compute :only [running? primary-ip private-ip]]
        [pallet.compute.jclouds]
        [org.jclouds.compute2 :only [nodes-in-group]]
        [pallet.configure :only [compute-service-properties pallet-config]]
        [backtype.storm.branch :only [branch>]]
        [pallet.thread-expr :only [if->]])
  (:require
   [backtype.storm.crate.zeromq :as zeromq]
   [backtype.storm.crate.leiningen :as leiningen]
   [pallet.crate.git :as git]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.java :as java]
   [pallet.resource.package :as package]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.remote-directory :as remote-directory]
   [pallet.action.file :as file]
   [backtype.storm.defaults :as defaults]))

(defn storm-config
  ([] (storm-config "default"))
  ([conf-name] (compute-service-properties (pallet-config) [conf-name])))

(defn running-nodes-for-group [compute group-name cluster-name]
  (filter
   running?
   (map (partial jclouds-node->node compute)
        (nodes-in-group compute (str group-name "-" cluster-name)))))

(defn nimbus-ip [compute name]
  (let [running-nodes
        (running-nodes-for-group compute "nimbus" name)]
    (assert (= (count running-nodes) 1))
    (primary-ip (first running-nodes))))

(defn nimbus-private-ip [compute name]
  (let [running-nodes
        (running-nodes-for-group compute "nimbus" name)]
    (assert (= (count running-nodes) 1))
    (private-ip (first running-nodes))))

(defn zookeeper-ips [compute name]
  (let [running-nodes
        (running-nodes-for-group compute "zookeeper" name)]
    (map primary-ip running-nodes)))

(defn supervisor-ips [compute name]
  (let [running-nodes
        (running-nodes-for-group compute "supervisor" name)]
    (map primary-ip running-nodes)))

(def mvn3-defaults (defaults/sub-default [:maven3]))

(defn- install-maven-3 [s & {:keys [version] :as opts}]
  (let [version (or version (mvn3-defaults [:version]))
        url (format (mvn3-defaults [:url-template]) version version)
        md5-url (format (mvn3-defaults [:md5-url-template]) version version)
        install-dir (mvn3-defaults [:install-dir])
        orig-bin-path (str install-dir "/bin/mvn")
        bin-destination (mvn3-defaults [:bin-destination])]
    (-> s
        (directory/directory install-dir)
        (exec-script/exec-checked-script
         "Install maven3" (println "Install Maven 3"))
        (remote-directory/remote-directory
         install-dir
         :url url
         :md5-url md5-url
         :unpack :tar
         :tar-options "xz")
        ;; link the maven exec so it is in the path
        (file/symbolic-link orig-bin-path bin-destination))))

(defn- install-dependencies [request branch method]
  (->
   request
   ;;(java/java :openjdk) ;; needed? installed by storm-base-server-spec?
   (git/git)
   (if-> (= method :apache)
         ;; apache releases are built with maven 3 and use no zeromq
         (install-maven-3 :version (mvn3-defaults [:version]))
         ;; classic releases are build with leiningen and use zeromq
         (->
          (leiningen/install
           (if (or (not branch) (= branch "master") (branch> branch "0.9.0")) 2 1))
          (zeromq/install :version (defaults/defaults [:zeromq :version]))
          (zeromq/install-jzmq :version (defaults/defaults [:jzmq :version]))))
   (package/package "daemontools")
   (package/package "unzip")
   (package/package "zip")))

(defn get-release [request branch commit method]
  (let [url "https://github.com/apache/incubator-storm.git"
        sha1 (if (empty? commit) "" commit) ; empty string for pallet
        mvn-bin (mvn3-defaults [:bin-destination])
        classic? (= method :classic)]
    (-> request
        (exec-script/exec-checked-script
         "Build storm"
         (cd "$HOME")
         (mkdir -p "build")
         (cd "$HOME/build")

         ;; clone the storm repo
         (when-not (directory? "storm")
           ;; the name of the repo has changed, so we still check it
           ;; out as 'storm'
           (git clone -b ~branch ~url "storm"))
         (cd storm)

         ;; checkout the branch (and possibly sha)
         (git pull)
         (if (not (empty? ~sha1))
           (git checkout -b newbranch ~sha1))

         ;; build storm
         (if ~classic?
           ;; in classic mode we build using the bin/build_release.sh script
           (do
             (bash "bin/build_release.sh")
             ;; the install script expects the zip file in ~storm
             (cp "*.zip $HOME/"))
           ;; the apache versions use maven3
           (do
             (mvn "install" "-Dmaven.test.skip=true")
             (cd "storm-dist/binary/")
             ;; prevent being asked for the gpg password
             (mvn "package" "-Dmaven.test.skip=true" "-Dgpg.skip=true")
             ;; the install script expects the zip file in ~storm
             (cp "target/*.zip $HOME/")))))))

(defn make [request branch commit method]
  (->
   request
   ;; clean up any zip file from the ~storm/
   (exec-script/exec-checked-script
     "clean up home"
     (cd "$HOME")
     (rm "-f *.zip"))
   ;; get the desired version of the storm .zip file
   (get-release branch commit method)
   (exec-script/exec-checked-script
    "prepare daemon"

    ;; unzip the storm .zip file
     (cd "$HOME")
     (unzip "-o *.zip")
     ;; remove the build directory (if it's there)
     (rm "-f storm")

     (ln "-s $HOME/`ls | grep zip | sed s/.zip//` storm")

     ;; create needed directories
     (mkdir -p "daemon")
     (mkdir -p "$HOME/storm/log4j")
     (chmod "755" "$HOME/storm/log4j")
     (touch "$HOME/storm/log4j/storm.log.properties")
     (touch "$HOME/storm/log4j/log4j.properties")
     (chmod "755" "$HOME/storm/log4j/storm.log.properties")
     (chmod "755" "$HOME/storm/log4j/log4j.properties"))
    (directory/directory "$HOME/daemon/supervise" :owner "storm" :mode "700")
    (directory/directory "$HOME/storm/logs" :owner "storm" :mode "700")
    (directory/directory "$HOME/storm/bin" :mode "755")))

(defn install-supervisor [request branch commit method local-dir-path]
  (->
   request
   (install-dependencies branch method)
   (directory/directory local-dir-path :owner "storm" :mode "700")
   (make branch commit method)))

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
       :mode 755)))

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
       :mode 755)))

(defn install-ui [request]
  (-> request
    (directory/directory "$HOME/ui" :owner "storm" :mode "700")
    (directory/directory "$HOME/ui/logs" :owner "storm" :mode "700")
    (directory/directory "$HOME/ui/supervise" :owner "storm" :mode "700")
    (write-ui-exec "$HOME/ui/run")))

(defn install-drpc [request]
  (-> request
    (directory/directory "$HOME/drpc" :owner "storm" :mode "700")
    (directory/directory "$HOME/drpc/logs" :owner "storm" :mode "700")
    (directory/directory "$HOME/drpc/supervise" :owner "storm" :mode "700")
    (write-drpc-exec "$HOME/drpc/run")))

(defn install-nimbus [request branch commit method local-dir-path]
  (->
   request
   (directory/directory local-dir-path :owner "storm" :mode "700")
   (install-dependencies branch method)
   (make branch commit method)))

(defn exec-daemon [request]
  (->
   request
   (exec-script/exec-script
    (cd "$HOME/daemon")
    "ps ax | grep backtype.storm | grep -v grep | awk '{print $1}' | xargs kill -9\n\n"
    "sudo -u storm -H nohup supervise . &")))

(defn exec-ui [request]
  (exec-script/exec-script request
    "sudo -u storm -H nohup supervise ~storm/ui > nohup-ui.log &"))

(defn exec-drpc [request]
  (exec-script/exec-script request
    "sudo -u storm -H nohup supervise ~storm/drpc > nohup-drpc.log &"))

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
      :mode 755)))

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

