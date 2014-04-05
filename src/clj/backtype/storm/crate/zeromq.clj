(ns backtype.storm.crate.zeromq
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.resource.package :as package]
   [pallet.resource.remote-directory :as remote-directory]
   [pallet.resource.directory :as directory]
;;   [pallet.crate.maven :as maven]
   [pallet.crate.git :as git]
   [pallet.parameter :as parameter]
   [pallet.crate.iptables :as iptables]
   ))

(def src-path "/opt/local/zeromq")
(def md5s {})

(defn download-url
  "The url for downloading zeromq"
  [version]
  (format
   "http://download.zeromq.org/zeromq-%s.tar.gz"
   version))

(defn install
  "Install zeromq from source."
  [request & {:keys [version] :or {version "2.0.10"}}]
  (->
   request
   (package/packages
    :yum ["gcc" "gcc-c++" "glib" "glibc-common" "libuuid-devel"]
    :aptitude ["build-essential" "uuid-dev"])
   (remote-directory/remote-directory
    src-path
    :url (download-url version) :md5 (md5s version) :unpack :tar)
   (exec-script/exec-checked-script
    "Build zeromq"
    (cd ~src-path)
    ("./configure")
    (make)
    (make install)
    ("/sbin/ldconfig"))
   ;;(parameter/assoc-for-target [:zeromq :version] version)
   ))

(defn install-jzmq
  "Install jzmq from source. You must install zeromq first."
  [request & {:keys [version]}]
  (->
   request
;;   (maven/package) ;; mvn not used?
   (git/git)
   (package/packages
    :yum ["libtool" "pkg-config" "autoconf"]
    :aptitude ["libtool" "pkg-config" "autoconf"])

   (exec-script/exec-checked-script
    "Build jzmq"

    (var tmpdir (~directory/make-temp-dir "rf"))
    (cd (quoted @tmpdir))
    ;; use frozen version of jzmq
    (git clone "git://github.com/nathanmarz/jzmq.git")

    (cd "jzmq")

    (export (str "JAVA_HOME="
                 @(dirname @(dirname @(dirname @(update-alternatives "--list" java))))))

    ("touch src/classdist_noinst.stamp")
    (cd "src")
    ("CLASSPATH=.:./.:$CLASSPATH javac -d . org/zeromq/ZMQ.java org/zeromq/App.java org/zeromq/ZMQForwarder.java org/zeromq/EmbeddedLibraryTools.java org/zeromq/ZMQQueue.java org/zeromq/ZMQStreamer.java org/zeromq/ZMQException.java")
    (cd "..")
    
    ("./autogen.sh")
    ("./configure")
    (make)

    (make install)
;;    (mvn ~(print-str "install:install-file -Dfile=/usr/local/share/java/zmq.jar"
;;                     "-DgroupId=org.zeromq"
;;                     "-DartifactId=zmq"
;;                     (format "-Dversion=%s" version)
;;                     "-Dpackaging=jar"))
                     )))

(defn iptables-accept
  "Accept zeromq connections, by default on port 5672"
  ([request] (iptables-accept request 5672))
  ([request port]
     (iptables/iptables-accept-port request port)))

