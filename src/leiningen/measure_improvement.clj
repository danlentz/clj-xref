(ns leiningen.measure-improvement
  "Leiningen task to measure LLM token savings from xref-guided context.

   Usage:
     lein measure-improvement
     lein measure-improvement :model claude-sonnet-4-6

   Requires clj-http on the classpath (available via :dev profile)
   and ANTHROPIC_API_KEY environment variable.")

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

   Requires ANTHROPIC_API_KEY environment variable and clj-http
   on the classpath (included in :dev profile)."
  [project & args]
  (let [run-bench    (requiring-resolve 'clj-xref.bench/run-benchmark)
        print-results (requiring-resolve 'clj-xref.bench/print-results)]
    (when-not run-bench
      (println "Error: clj-xref.bench could not be loaded. Ensure clj-http is on the classpath (lein :dev profile).")
      (System/exit 1))
    (let [parsed (parse-args args)
          opts   (cond-> {:paths (or (:source-paths project) ["src"])}
                   (:model parsed) (assoc :model (:model parsed)))]
      (try
        (print-results (run-bench opts))
        (catch clojure.lang.ExceptionInfo e
          (if (re-find #"ANTHROPIC_API_KEY" (.getMessage e))
            (println (str "Error: " (.getMessage e)))
            (throw e)))))))
