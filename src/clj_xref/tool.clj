(ns clj-xref.tool
  "deps.edn tool entry point for generating clj-xref databases.

   Usage:
     clj -T:xref generate
     clj -T:xref generate :paths '[\"src\"]' :output '\"target/xref.edn\"'"
  (:require [clj-xref.analyze :as analyze]
            [clj-xref.emit :as emit]))

(defn generate
  "Generate an xref database EDN file.
   Accepts a map with optional keys:
     :paths   - vector of source paths to analyze (default [\"src\" \"test\"])
     :output  - output file path (default \".clj-xref/xref.edn\")
     :project - project name string"
  [{:keys [paths output project]
    :or   {paths  ["src" "test"]
           output ".clj-xref/xref.edn"}}]
  (println (str "clj-xref: analyzing " (pr-str paths) "..."))
  (let [db (analyze/analyze paths {:project project})]
    (emit/write-edn db output)
    (println (str "clj-xref: wrote " (count (:vars db)) " vars, "
                  (count (:refs db)) " refs -> " output))))
