(ns clj-xref.tool
  "deps.edn tool entry point for generating clj-xref databases.

   Usage:
     clj -T:xref generate
     clj -T:xref generate :paths '[\"src\"]' :output '\"target/xref.edn\"'
     clj -T:xref generate :only '[\"src/my/ns.clj\"]'"
  (:require [clj-xref.analyze :as analyze]
            [clj-xref.emit :as emit]))

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
      (println (str "clj-xref: incremental update for " (pr-str only) "..."))
      (let [existing (emit/read-edn output)
            new-db   (analyze/analyze only {:project project})
            errors   (:kondo-errors (meta new-db) 0)]
        (if (pos? errors)
          (binding [*out* *err*]
            (println "clj-xref: incremental update aborted due to analysis errors. Existing database unchanged."))
          (let [merged (analyze/merge-analysis existing new-db only)]
            (emit/write-edn merged output)
            (println (str "clj-xref: wrote " (count (:vars merged)) " vars, "
                          (count (:refs merged)) " refs -> " output))))))
    (do
      (println (str "clj-xref: analyzing " (pr-str paths) "..."))
      (let [db (analyze/analyze paths {:project project})]
        (emit/write-edn db output)
        (println (str "clj-xref: wrote " (count (:vars db)) " vars, "
                      (count (:refs db)) " refs -> " output))))))
