;
;
; Copyright (C) 2011 Cloud Conscious, LLC. <info@cloudconscious.com>
;
; ====================================================================
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
; http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
; ====================================================================
;

(ns
  #^{:author "Juegen Hoetzel, juergen@archlinux.org"
     :doc "A clojure binding for the jclouds AWS security group interface."}
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
