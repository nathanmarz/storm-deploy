(ns backtype.storm.crate.newrelic
  (:require
   [pallet.resource.remote-file :as remote-file]
   [pallet.compute :as compute]
   [pallet.session :as session]
   [pallet.action.exec-script :as exec-script]))

(defn debfile [request]
  (if (compute/is-64bit? (session/target-node request))
      "newrelic-sysmond_1.3.1.437_amd64.deb"
      "newrelic-sysmond_1.3.1.437_i386.deb"))

(defn download-url [request]
  (str "http://download.newrelic.com/debian/dists/newrelic/non-free/binary-" 
       (if (compute/is-64bit? (session/target-node request))
           "amd64/"
           "i386/")
       (debfile request)))

(defn install [request]
  (let [newrelicpkg (debfile request)]
    (-> request
        (remote-file/remote-file
         (str "/home/storm/" newrelicpkg)
         :url (download-url request)
         :owner "storm"
         :mode 644)
        (exec-script/exec-script
         (sudo dpkg -i ~newrelicpkg)))))

(defn configure [request license-key]
  (-> request
      (exec-script/exec-script
        ~(str "sudo nrsysmond-config --set license_key=" license-key))))

(defn init [request]
  (-> request
      (exec-script/exec-script
        ("sudo /etc/init.d/newrelic-sysmond start"))))
