(ns leiningen.xref
  "Leiningen plugin task for generating clj-xref databases.

   Usage:
     lein xref                            ; analyze source-paths + test-paths
     lein xref :output target/xref.edn    ; custom output path
     lein xref :only src/my/ns.clj        ; incremental re-analysis"
  (:require [clj-xref.analyze :as analyze]
            [clj-xref.emit :as emit]))

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
        (println (str "clj-xref: incremental update for " (pr-str only) "..."))
        (let [existing (emit/read-edn output)
              new-db   (analyze/analyze only {:project proj-name})
              errors   (:kondo-errors (meta new-db) 0)]
          (if (pos? errors)
            (binding [*out* *err*]
              (println "clj-xref: incremental update aborted due to analysis errors. Existing database unchanged."))
            (let [merged (analyze/merge-analysis existing new-db only)]
              (emit/write-edn merged output)
              (println (str "clj-xref: wrote " (count (:vars merged)) " vars, "
                            (count (:refs merged)) " refs -> " output))))))
      (let [paths (let [p (:paths parsed)]
                    (if (seq p)
                      p
                      (into (vec (:source-paths project))
                            (:test-paths project))))]
        (println (str "clj-xref: analyzing " (pr-str paths) "..."))
        (let [db (analyze/analyze paths {:project proj-name})]
          (emit/write-edn db output)
          (println (str "clj-xref: wrote " (count (:vars db)) " vars, "
                        (count (:refs db)) " refs -> " output)))))))
