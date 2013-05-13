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
  (:require (org.jclouds [compute2 :as compute])
    [org.jclouds.ec2.ebs2 :as ebs])
  (:import org.jclouds.ec2.domain.IpProtocol
           org.jclouds.ec2.domain.SecurityGroup
           org.jclouds.ec2.services.SecurityGroupClient
           org.jclouds.ec2.domain.UserIdGroupPair
           java.io.DataInputStream
           java.net.URL))

(defn #^SecurityGroupClient
  sg-service
  "Returns the SecurityGroup Client associated with the specified  compute service."
  [compute]
  (-> compute .getContext .getProviderSpecificContext .getApi .getSecurityGroupServices))

(defn create-group
  "Creates a new security group.

  e.g. (create-group compute \"Database Server\" \"Description for group\" :region :us-west-1)"
  [compute name & {:keys [description region]}]
  (.createSecurityGroupInRegion (sg-service compute) (ebs/get-region region) name (or description name)))

(defn delete-group
  "Deletes a security group.

  e.g. (delete-group compute \"Database Server\" :region :us-west-1)"
  [compute name & {:keys [region]}]
  (.deleteSecurityGroupInRegion (sg-service compute) (ebs/get-region region) name))

(defn groups
  "Returns a map of GroupName -> org.jclouds.ec2.domain.SecurityGroup instances.

   e.g. (groups compute :region :us-east-1)"
  [compute & {:keys [region]}]
  (into {} (for [#^SecurityGroup group (.describeSecurityGroupsInRegion (sg-service compute)
                                                                        (ebs/get-region region)
                                                                        (into-array String '()))]
             [(.getName group) group])))

(defn get-protocol [v]
  "Coerce argument to a IP Protocol."
  (cond
   (instance? IpProtocol v) v
   (keyword? v) (if-let [p (get {:tcp IpProtocol/TCP
                                 :udp IpProtocol/UDP
                                 :icmp IpProtocol/ICMP}
                                v)]
                  p
                  (throw (IllegalArgumentException.
                          (str "Can't obtain IP protocol from " v " (valid :tcp, :udp and :icmp)"))))
   (nil? v) IpProtocol/TCP
   :else (throw (IllegalArgumentException.
                 (str "Can't obtain IP protocol from argument of type " (type v))))))

(def my-ip
  (memoize
    (fn []
      (let [is (DataInputStream. (.openStream (URL. "http://whatismyip.akamai.com/")))
            ret (.readLine is)]
        (.close is)
        ret
        ))))

(defn authorize
  "Adds permissions to a security group.

   e.g. (authorize compute \"jclouds#webserver#us-east-1\" 80 :ip-range \"0.0.0.0/0\")
        (authorize compute \"jclouds#webserver#us-east-1\" [1000,2000] :protocol :udp)"

  [compute group-name port & {:keys [protocol ip-range region]}]
  (let [group ((groups compute :region region) group-name)
        [from-port to-port] (if (number? port) [port port] port)]
    (if group
      (.authorizeSecurityGroupIngressInRegion
       (sg-service compute) (ebs/get-region region) (.getName group) (get-protocol protocol) from-port to-port (or ip-range "0.0.0.0/0"))
      (throw (IllegalArgumentException.
              (str "Can't find security group for name " group-name region ip-range from-port to-port))))))

(defn authorizeme [compute group-name port region]
  (try
    (authorize compute group-name port :ip-range (str (my-ip) "/32") :region region
    )
  (catch IllegalStateException _)
  ))

(defn authorize-group
  ([compute region to-group from-group]
    (authorize-group compute region to-group from-group (:aws-user-id (. compute environment)))
    )
  ([compute region to-group from-group user-id]
    (try
      (.authorizeSecurityGroupIngressInRegion
        (sg-service compute)
        region
        to-group
        (UserIdGroupPair. "" from-group)
        )
    (catch IllegalStateException _)
    )))

(defn revoke
  "Revokes permissions from a security group.

   e.g. (revoke compute 80 \"jclouds#webserver#us-east-1\" :protocol :tcp 80 80 :ip-range \"0.0.0.0/0\")"
  [compute group-name port & {:keys [protocol ip-range region]}]
  (let [group ((groups compute :region region) group-name)
        [from-port to-port] (if (number? port) [port port] port)]
    (if group
     (.revokeSecurityGroupIngressInRegion
      (sg-service compute) (ebs/get-region region) (.getName group) (get-protocol protocol) from-port to-port (or ip-range "0.0.0.0/0"))
     (throw (IllegalArgumentException.
             (str "Can't find security group for name " group-name))))))
