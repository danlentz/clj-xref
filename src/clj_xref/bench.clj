(ns clj-xref.bench
  "LLM token savings benchmark.

   Compares whole-tree vs xref-guided context selection by asking Claude
   the same questions under both strategies and measuring token usage
   and answer correctness.

   Requires clj-http and cheshire on the classpath (available via :dev profile)."
  (:require [clj-xref.core :as xref]
            [clj-format.core :refer [clj-format]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private default-model "claude-haiku-4-5-20251001")

(defn- api-key []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (throw (ex-info "Set ANTHROPIC_API_KEY environment variable to run the benchmark" {}))))

;; === Context builders ===

(defn- read-source-files
  "Read all .clj files under paths. Returns {path -> content}."
  [paths]
  (->> paths
       (mapcat (fn [p]
                 (let [f (io/file p)]
                   (if (.isFile f)
                     [f]
                     (filter #(str/ends-with? (.getName %) ".clj")
                             (file-seq f))))))
       (into (sorted-map)
             (map (fn [f] [(.getPath f) (slurp f)])))))

(defn- format-context
  "Format a file map as a context string with file headers."
  [file-map]
  (str/join "\n\n" (map (fn [[path content]]
                           (str "=== " path " ===\n" content))
                        (sort file-map))))

(defn- files-for-symbol
  "Find the set of source files relevant to sym's neighborhood.
   Includes the var's own file, files where callers live, and
   files where callees are defined."
  [db sym]
  (let [var-info     (get (:vars-by-name db) sym)
        callers      (xref/who-calls db sym)
        callees      (xref/calls-who db sym)
        ;; Callee definition files (not call-site files)
        callee-files (into #{} (comp (map :to)
                                     (map #(get (:vars-by-name db) %))
                                     (filter some?)
                                     (map :file))
                           callees)
        ;; Caller call-site files
        caller-files (into #{} (comp (map :file) (filter some?)) callers)
        ;; The var's own file
        self-file    (when var-info #{(:file var-info)})]
    (reduce into #{} [self-file caller-files callee-files])))

(defn whole-tree-context
  "Build context from all source files under paths."
  [paths]
  (let [files (read-source-files paths)]
    {:context    (format-context files)
     :file-count (count files)
     :char-count (reduce + 0 (map count (vals files)))}))

(defn xref-guided-context
  "Build context from only the files in sym's xref neighborhood."
  [db sym paths]
  (let [relevant (files-for-symbol db sym)
        all-files (read-source-files paths)
        selected (select-keys all-files relevant)]
    {:context    (format-context selected)
     :file-count (count selected)
     :char-count (reduce + 0 (map count (vals selected)))}))

;; === Claude API ===

(defn- ask-claude
  "Send a question with context to the Claude Messages API."
  [context question {:keys [model] :or {model default-model}}]
  (let [http-post       (requiring-resolve 'clj-http.client/post)
        json-generate   (requiring-resolve 'cheshire.core/generate-string)
        prompt (str "You are answering questions about a Clojure codebase.\n\n"
                    "Source code:\n\n" context
                    "\n\nQuestion: " question
                    "\n\nAnswer concisely, citing specific function names and namespaces.")
        start  (System/nanoTime)
        resp   (http-post "https://api.anthropic.com/v1/messages"
                 {:headers      {"x-api-key"         (api-key)
                                 "anthropic-version"  "2023-06-01"
                                 "content-type"       "application/json"}
                  :body         (json-generate
                                  {:model      model
                                   :max_tokens 1024
                                   :messages   [{:role "user" :content prompt}]})
                  :as           :json
                  :throw-exceptions false})
        ms     (/ (- (System/nanoTime) start) 1e6)
        body   (:body resp)
        status (:status resp)]
    (when (not= 200 status)
      (throw (ex-info (str "Claude API returned " status ": "
                           (or (get-in body [:error :message]) body))
                      {:status status :body body})))
    {:answer        (get-in body [:content 0 :text])
     :input-tokens  (get-in body [:usage :input_tokens])
     :output-tokens (get-in body [:usage :output_tokens])
     :model         model
     :latency-ms    ms}))

;; === Correctness checking ===

(defn- check-correctness
  "Check if the answer contains all expected fragments (case-insensitive)."
  [answer expected]
  (let [lower  (str/lower-case (or answer ""))
        hits   (filterv #(str/includes? lower (str/lower-case %)) expected)
        misses (filterv #(not (str/includes? lower (str/lower-case %))) expected)]
    {:correct? (= (count hits) (count expected))
     :hits     hits
     :misses   misses
     :score    (if (empty? expected) 1.0
                   (double (/ (count hits) (count expected))))}))

;; === Test questions ===

(def test-questions
  [{:id       :who-calls-index
    :question "What functions call clj-xref.core/index and why does each call it?"
    :target   'clj-xref.core/index
    :expected ["load-db" "analyze" "from-kondo-analysis"]}

   {:id       :protocol-inference
    :question "How does protocol implementation type inference work in the analyze namespace?"
    :target   'clj-xref.analyze/transform-analysis
    :expected ["infer-impl-type" "defrecord" "deftype"]}

   {:id       :incremental-trace
    :question "Trace step by step what happens when a user runs lein xref :only on a specific file."
    :target   'leiningen.xref/xref
    :expected ["parse-args" "read-edn" "analyze" "merge-analysis" "write-edn"]}

   {:id       :xref-kinds
    :question "What are all the xref entry kinds and how is each one classified from clj-kondo analysis data?"
    :target   'clj-xref.analyze/classify-kind
    :expected ["call" "reference" "macroexpand" "dispatch" "implement"]}])

;; === Benchmark runner ===

(def ^:private fmt-progress
  ["  " :str ": whole-tree (" :int " file" [:plural {:rewind true}] ")... "])

(def ^:private fmt-progress-guided
  [:int "t -> xref-guided (" :int " file" [:plural {:rewind true}] ")... "])

(defn- run-single
  "Run one question under both strategies."
  [db paths question opts]
  (let [{:keys [id question target expected]} question
        whole   (whole-tree-context paths)
        guided  (xref-guided-context db target paths)]
    (clj-format true fmt-progress (name id) (:file-count whole))
    (flush)
    (let [wr (ask-claude (:context whole) question opts)
          wc (check-correctness (:answer wr) expected)]
      (clj-format true fmt-progress-guided (:input-tokens wr) (:file-count guided))
      (flush)
      (let [gr (ask-claude (:context guided) question opts)
            gc (check-correctness (:answer gr) expected)]
        (clj-format true [:int "t" :nl] (:input-tokens gr))
        {:id id
         :whole   (merge (select-keys wr [:input-tokens :output-tokens :latency-ms])
                         (select-keys whole [:file-count :char-count])
                         (select-keys wc [:score :correct? :misses]))
         :guided  (merge (select-keys gr [:input-tokens :output-tokens :latency-ms])
                         (select-keys guided [:file-count :char-count])
                         (select-keys gc [:score :correct? :misses]))
         :savings (when (and (:input-tokens wr) (:input-tokens gr)
                             (pos? (:input-tokens wr)))
                    (double (- 1 (/ (:input-tokens gr)
                                    (:input-tokens wr)))))}))))

(def ^:private fmt-banner
  ["clj-xref benchmark" :nl
   "  analyzing " :pr "..." :nl])

(def ^:private fmt-stats
  ["  " :int " var" [:plural {:rewind true}]
   ", " :int " ref" [:plural {:rewind true}] :nl])

(def ^:private fmt-running
  ["  running " :int " question" [:plural {:rewind true}] " against " :str "..." :nl :nl])

(defn run-benchmark
  "Run the full benchmark. Returns vector of result maps.
   Options:
     :paths     - source paths to analyze (default [\"src\"])
     :model     - Claude model (default haiku)
     :questions - override test questions"
  [{:keys [paths model questions]
    :or   {paths     ["src"]
           questions test-questions}}]
  (clj-format true fmt-banner paths)
  (let [db   (xref/analyze paths)
        opts (cond-> {} model (assoc :model model))]
    (clj-format true fmt-stats (count (:vars db)) (count (:refs db)))
    (clj-format true fmt-running (count questions) (or model default-model))
    (mapv #(run-single db paths % opts) questions)))

;; === Output ===

(def ^:private fmt-header
  [:nl "=== clj-xref Token Savings Benchmark ===" :nl :nl
   "  " [:str {:width 22}]
   [:str {:width 11 :pad :left}]
   [:str {:width 11 :pad :left}]
   [:str {:width 9 :pad :left}]
   "  " [:str {:width 6 :pad :left}]
   " " [:str {:width 6 :pad :left}] :nl])

(def ^:private fmt-row
  ["  " [:str {:width 22}]
   [:int {:width 9 :pad :left}] "t "
   [:int {:width 9 :pad :left}] "t "
   [:int {:width 5 :pad :left}] "% "
   " " [:int {:width 4 :pad :left}] "% "
   [:int {:width 4 :pad :left}] "%" :nl])

(def ^:private fmt-separator
  ["  " [:each {:max 68} "-"] :nl])

(def ^:private fmt-totals
  [:nl "  Total tokens: whole-tree " :int ", xref-guided " :int
   " (" :int "% reduction)" :nl])

(def ^:private fmt-miss
  ["    " :str " (" :str "): " [:each {:sep ", "} :str] :nl])

(defn print-results
  "Print a comparison table."
  [results]
  (clj-format true fmt-header
              "" "Whole-tree" "Xref" "Savings" "W-Acc" "X-Acc")
  (clj-format true fmt-separator (repeat 68 "-"))
  (doseq [r results]
    (clj-format true fmt-row
                (name (:id r))
                (get-in r [:whole :input-tokens] 0)
                (get-in r [:guided :input-tokens] 0)
                (Math/round (* 100.0 (or (:savings r) 0)))
                (Math/round (* 100.0 (get-in r [:whole :score] 0)))
                (Math/round (* 100.0 (get-in r [:guided :score] 0)))))
  (clj-format true fmt-separator (repeat 68 "-"))
  (let [n (count results)
        avg-savings      (/ (reduce + (keep :savings results)) (max 1 n))
        avg-whole-score  (/ (reduce + (map #(get-in % [:whole :score]) results)) (max 1 n))
        avg-guided-score (/ (reduce + (map #(get-in % [:guided :score]) results)) (max 1 n))
        total-whole      (reduce + (map #(get-in % [:whole :input-tokens] 0) results))
        total-guided     (reduce + (map #(get-in % [:guided :input-tokens] 0) results))]
    (clj-format true fmt-row
                "AVERAGE"
                (quot total-whole n) (quot total-guided n)
                (Math/round (* 100.0 avg-savings))
                (Math/round (* 100.0 avg-whole-score))
                (Math/round (* 100.0 avg-guided-score)))
    (clj-format true fmt-totals
                total-whole total-guided
                (Math/round (* 100.0 (- 1 (/ (double total-guided)
                                              (max 1 total-whole)))))))
  (let [misses (for [r results
                     strategy [:whole :guided]
                     :let [m (get-in r [strategy :misses])]
                     :when (seq m)]
                 {:id (:id r) :strategy strategy :misses m})]
    (when (seq misses)
      (clj-format true [:nl "  Missed expected terms:" :nl])
      (doseq [{:keys [id strategy misses]} misses]
        (clj-format true fmt-miss (name id) (name strategy) misses)))))
