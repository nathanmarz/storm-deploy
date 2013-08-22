(ns backtype.storm.crate.leiningen
  (:require
   [pallet.resource.remote-file :as remote-file]
   [pallet.action.exec-script :as exec-script]))

;; this is 1.5.2. freezing version to ensure deploy is stable
(def download-url "https://raw.github.com/technomancy/leiningen/a1fa43400295d57a9acfed10735c1235904a9407/bin/lein")

(defn install [request]
  (-> request
      (remote-file/remote-file
       "/usr/local/bin/lein1"
       :url download-url
       :owner "root"
       :mode 755)
      (exec-script/exec-script
       (export "LEIN_ROOT=1")
       ("/usr/local/bin/lein1"))))
