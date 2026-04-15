(ns clj-xref.tool
  "deps.edn tool entry point for generating clj-xref databases.

   Usage:
     clj -T:xref generate
     clj -T:xref generate :paths '[\"src\"]' :output '\"target/xref.edn\"'
     clj -T:xref generate :only '[\"src/my/ns.clj\"]'"
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

(defn generate
  "Generate an xref database EDN file.
   Accepts a map with optional keys:
     :paths   - vector of source paths to analyze (default [\"src\" \"test\"])
     :output  - output file path (default \".clj-xref/xref.edn\")
     :project - project name string
     :only    - vector of specific files to re-analyze (incremental mode)"
  [{:keys [paths output project only]
    :or   {paths  ["src" "test"]
           output ".clj-xref/xref.edn"}}]
  (if only
    (do
      (clj-format true fmt-incremental only)
      (let [existing (emit/read-edn output)
            new-db   (analyze/analyze only {:project project})
            merged   (analyze/merge-analysis existing new-db only)]
        (emit/write-edn merged output)
        (clj-format true fmt-wrote
                    (count (:vars merged)) (count (:refs merged)) output)))
    (do
      (clj-format true fmt-analyzing paths)
      (let [db (analyze/analyze paths {:project project})]
        (emit/write-edn db output)
        (clj-format true fmt-wrote
                    (count (:vars db)) (count (:refs db)) output)))))
