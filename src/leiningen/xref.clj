(ns leiningen.xref
  "Leiningen plugin task for generating clj-xref databases.

   Usage:
     lein xref                            ; analyze source-paths + test-paths
     lein xref :output target/xref.edn    ; custom output path
     lein xref src/my/ns.clj              ; analyze specific files"
  (:require [clj-xref.analyze :as analyze]
            [clj-xref.emit :as emit]))

(defn- parse-args
  "Parse lein task args into {:output str, :paths [str ...]}."
  [args]
  (loop [args args
         opts {}
         paths []]
    (if (empty? args)
      (assoc opts :paths paths)
      (let [a (first args)]
        (if (.startsWith ^String a ":")
          (recur (drop 2 args)
                 (assoc opts (keyword (subs a 1)) (second args))
                 paths)
          (recur (rest args) opts (conj paths a)))))))

(defn xref
  "Generate a cross-reference database for this project."
  [project & args]
  (let [parsed  (parse-args args)
        output  (get parsed :output ".clj-xref/xref.edn")
        paths   (let [p (:paths parsed)]
                  (if (seq p)
                    p
                    (into (vec (:source-paths project))
                          (:test-paths project))))
        proj-name (:name project)]
    (println (str "clj-xref: analyzing " (pr-str paths) "..."))
    (let [db (analyze/analyze paths {:project proj-name})]
      (emit/write-edn db output)
      (println (str "clj-xref: wrote " (count (:vars db)) " vars, "
                    (count (:refs db)) " refs -> " output)))))
