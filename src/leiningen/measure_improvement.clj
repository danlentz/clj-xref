(ns leiningen.measure-improvement
  "Leiningen task to measure LLM token savings from xref-guided context.

   Usage:
     lein measure-improvement
     lein measure-improvement :model claude-sonnet-4-6"
  (:require [clj-xref.bench :as bench]))

(defn- parse-args [args]
  (loop [args args
         opts {}]
    (if (empty? args)
      opts
      (let [a (first args)]
        (if (.startsWith ^String a ":")
          (recur (drop 2 args)
                 (assoc opts (keyword (subs a 1)) (second args)))
          (recur (rest args) opts))))))

(defn measure-improvement
  "Run the LLM token savings benchmark.

   Requires ANTHROPIC_API_KEY environment variable."
  [project & args]
  (let [parsed (parse-args args)
        opts   (cond-> {:paths (or (:source-paths project) ["src"])}
                 (:model parsed) (assoc :model (:model parsed)))]
    (try
      (let [results (bench/run-benchmark opts)]
        (bench/print-results results))
      (catch clojure.lang.ExceptionInfo e
        (if (= "Set ANTHROPIC_API_KEY environment variable to run the benchmark"
               (.getMessage e))
          (println (str "Error: " (.getMessage e)))
          (throw e))))))
