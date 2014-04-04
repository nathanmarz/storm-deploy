(defproject storm-deploy "0.0.6-SNAPSHOT"
  :source-paths ["src/clj"]
  :test-paths ["test/clj"]
  :profiles {:dev {:resource-paths ["conf"]}}
  :aliases {"deploy-storm" ["run" "-m" "backtype.storm.provision"]}

  :repositories
  {"sonatype" "https://oss.sonatype.org/content/repositories/releases"
   "jclouds-snapshot" "https://oss.sonatype.org/content/repositories/snapshots"}

  :dependencies [[storm "0.5.4"]
                 [commons-codec "1.4"]
                 [org.cloudhoist/pallet "0.7.5"
                  :exclusions
                  [jsch-agent-proxy/jsch-agent-proxy-jna
                   jsch-agent-proxy]]
                 [com.palletops/pallet-jclouds "1.7.0-alpha.3"]
                 [org.cloudhoist/java "0.5.0"]
                 [org.cloudhoist/git "0.5.0"]
                 [org.cloudhoist/ssh-key "0.5.0"]
                 [org.cloudhoist/automated-admin-user "0.5.0"]
                 [org.cloudhoist/iptables "0.5.0"]
;;                 [org.cloudhoist/maven "0.5.0"]
                 [org.cloudhoist/zookeeper "0.5.1"]
                 [org.cloudhoist/nagios-config "0.5.0"]
                 [org.cloudhoist/crontab "0.5.0"]

                 [org.apache.jclouds.driver/jclouds-sshj "1.7.1"]
                 [org.apache.jclouds.provider/aws-ec2 "1.7.1"]
                 [org.apache.jclouds.provider/aws-s3 "1.7.1"]

                 [com.jcraft/jsch "0.1.51"]
                 [com.jcraft/jsch.agentproxy.usocket-jna "0.0.7"]
                 [com.jcraft/jsch.agentproxy.usocket-nc "0.0.7"]
                 [com.jcraft/jsch.agentproxy.sshagent "0.0.7"]
                 [com.jcraft/jsch.agentproxy.pageant "0.0.7"]
                 [com.jcraft/jsch.agentproxy.core "0.0.7"]
                 [com.jcraft/jsch.agentproxy.jsch "0.0.7"]

                 [log4j/log4j "1.2.14"]
                 [jvyaml "1.0.0"]]

  :dev-dependencies [[swank-clojure "1.2.1"]
                     [org.cloudhoist/pallet-lein "0.5.2"]]
  :min-lein-version "2.0.0")
