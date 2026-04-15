(ns leiningen.xref
  "Leiningen plugin task for generating clj-xref databases.

   Usage:
     lein xref                            ; analyze source-paths + test-paths
     lein xref :output target/xref.edn    ; custom output path
     lein xref :only src/my/ns.clj        ; incremental re-analysis"
  (:require [clj-xref.analyze :as analyze]
            [clj-xref.emit :as emit]
            [clj-format.core :refer [clj-format]]))

(def ^:private fmt-analyzing
  ["clj-xref: analyzing " :pr "..." :nl])

(def ^:private fmt-incremental
  ["clj-xref: incremental update for " :pr "..." :nl])

(def ^:private fmt-wrote
  ["clj-xref: wrote " :int " var" [:plural {:rewind true}]
   ", " :int " ref" [:plural {:rewind true}] " -> " :str :nl])

(defn- parse-args
  "Parse lein task args into {:output str, :only [str ...], :paths [str ...]}."
  [args]
  (loop [args args
         opts {}
         paths []]
    (if (empty? args)
      (assoc opts :paths paths)
      (let [a (first args)]
        (if (.startsWith ^String a ":")
          (let [k (keyword (subs a 1))
                v (second args)]
            (if (= k :only)
              (recur (drop 2 args)
                     (update opts :only (fnil conj []) v)
                     paths)
              (recur (drop 2 args)
                     (assoc opts k v)
                     paths)))
          (recur (rest args) opts (conj paths a)))))))

(defn xref
  "Generate a cross-reference database for this project."
  [project & args]
  (let [parsed    (parse-args args)
        output    (get parsed :output ".clj-xref/xref.edn")
        proj-name (:name project)]
    (if-let [only (:only parsed)]
      (do
        (clj-format true fmt-incremental only)
        (let [existing (emit/read-edn output)
              new-db   (analyze/analyze only {:project proj-name})
              merged   (analyze/merge-analysis existing new-db only)]
          (emit/write-edn merged output)
          (clj-format true fmt-wrote
                      (count (:vars merged)) (count (:refs merged)) output)))
      (let [paths (let [p (:paths parsed)]
                    (if (seq p)
                      p
                      (into (vec (:source-paths project))
                            (:test-paths project))))]
        (clj-format true fmt-analyzing paths)
        (let [db (analyze/analyze paths {:project proj-name})]
          (emit/write-edn db output)
          (clj-format true fmt-wrote
                      (count (:vars db)) (count (:refs db)) output))))))
