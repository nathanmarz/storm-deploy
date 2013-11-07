(ns backtype.storm.branch)

(defn parse-branch [branch]
  (map #(Integer/parseInt %) (.split branch "\\.")))

(defn branch> [branch1 branch2]
  (->> (map - (parse-branch branch1) (parse-branch branch2))
       (take-while #(>= % 0))
       (some pos?)))
