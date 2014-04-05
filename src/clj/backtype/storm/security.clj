(ns
  backtype.storm.security
  (:require [com.palletops.jclouds.compute2 :as compute]
            [com.palletops.jclouds.ec2.security-group2 :as sg])
  (:use [com.palletops.jclouds.ec2.core :only [get-region]])
  (:import org.jclouds.ec2.domain.UserIdGroupPair
           java.io.DataInputStream
           java.net.URL))

(def my-ip
  (memoize
    (fn []
      (let [is (DataInputStream. (.openStream (URL. "http://whatismyip.akamai.com/")))
            ret (.readLine is)]
        (.close is)
        ret))))

(defn authorizeme [compute group-name port region]
  (try
    (sg/authorize compute group-name port
               :ip-range (str (my-ip) "/32")
               :region region)
  (catch IllegalStateException _)))

(defn authorize-group
  ([compute region to-group from-group]
     (authorize-group compute region to-group
                      from-group (:aws-user-id (. compute environment))))
  ([compute region to-group from-group user-id]
    (try
      (.authorizeSecurityGroupIngressInRegion
        (sg/sg-service compute)
        region
        to-group
        (UserIdGroupPair. "" from-group))
    (catch IllegalStateException _))))
