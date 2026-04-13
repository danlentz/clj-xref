(ns clj-xref.test-utils
  "Shared test utilities: builders, generators, helpers."
  (:require [clj-xref.core :as xref]
            [clj-xref.emit :as emit]
            [clojure.string :as str]
            [clojure.test.check.generators :as gen]))

;; === Helpers ===

(defn- ns->file [ns-sym]
  (str "src/" (str/replace (str ns-sym) "." "/") ".clj"))

(defn froms [refs] (set (map :from refs)))
(defn tos [refs] (set (map :to refs)))
(defn kinds [refs] (set (map :kind refs)))

;; === Builders ===

(defn make-var
  "Build a var-info map. Overrides replace defaults."
  [ns-sym local-name & {:as overrides}]
  (merge {:name       (symbol (str ns-sym) (str local-name))
          :ns         ns-sym
          :local-name local-name
          :file       (ns->file ns-sym)
          :line       1
          :col        1}
         overrides))

(defn make-ref
  "Build an xref-entry map. Adds :arity 1 for :call kind by default."
  [kind from-sym to-sym & {:as overrides}]
  (merge {:kind kind
          :from from-sym
          :to   to-sym
          :file (if-let [ns (some-> from-sym namespace)]
                  (ns->file (symbol ns))
                  "unknown.clj")
          :line 1
          :col  1}
         (when (= kind :call) {:arity 1})
         overrides))

(defn make-ns
  "Build an ns-info map."
  [ns-sym & {:as overrides}]
  (merge {:name ns-sym
          :file (ns->file ns-sym)
          :line 1
          :col  1}
         overrides))

(defn make-db
  "Build a raw (unindexed) xref database map."
  [{:keys [vars refs namespaces] :as content}]
  (merge {:version    1
          :generated  "2026-01-01T00:00:00Z"
          :project    "test"
          :paths      ["src"]
          :vars       (or vars [])
          :refs       (or refs [])
          :namespaces (or namespaces [])}
         content))

(defn make-indexed-db
  "Build an indexed xref database from content map."
  [content]
  (xref/index (make-db content)))

;; === Temp file helper ===

(defmacro with-temp-edn
  "Create a temp .edn file, bind its path, delete on exit."
  [[path-sym] & body]
  `(let [f# (java.io.File/createTempFile "xref-test" ".edn")
         ~path-sym (.getAbsolutePath f#)]
     (try
       ~@body
       (finally
         (.delete f#)))))

;; === Timing helper ===

(defn timed
  "Execute f, return {:result ... :ms ...}."
  [f]
  (let [start  (System/nanoTime)
        result (f)
        ms     (/ (- (System/nanoTime) start) 1e6)]
    {:result result :ms ms}))

;; === Large database generator (deterministic) ===

(defn generate-large-db
  "Generate a synthetic database with controllable dimensions."
  [{:keys [n-namespaces n-vars-per-ns n-refs-per-var]
    :or   {n-namespaces 10 n-vars-per-ns 20 n-refs-per-var 5}}]
  (let [nss   (mapv #(symbol (str "stress.ns" %)) (range n-namespaces))
        vars  (vec (for [ns-sym nss
                         i (range n-vars-per-ns)]
                     (make-var ns-sym (symbol (str "fn" i)) :line (inc i))))
        n-vars    (count vars)
        var-names (mapv :name vars)
        refs  (vec (for [vi (range n-vars)
                         ri (range n-refs-per-var)
                         :let [ti (mod (+ (* vi 7) (* ri 13) 1) n-vars)]]
                     (make-ref :call
                               (nth var-names vi)
                               (nth var-names ti)
                               :line (+ 10 ri))))]
    (make-db {:vars vars :refs refs :namespaces (mapv make-ns nss)})))

;; === test.check generators ===

(def gen-kind
  (gen/elements [:call :reference :macroexpand :dispatch :implement]))

(def gen-raw-db
  "Generator for structurally valid raw xref databases."
  (gen/bind (gen/tuple (gen/choose 1 5) (gen/choose 1 5))
    (fn [[n-ns n-var]]
      (let [nss       (mapv #(symbol (str "gen.ns" %)) (range n-ns))
            vars      (vec (for [ns-sym nss, i (range n-var)]
                             (make-var ns-sym (symbol (str "fn" i)))))
            var-syms  (mapv :name vars)
            n-vars    (count var-syms)]
        (gen/fmap
          (fn [ref-tuples]
            (make-db {:vars vars
                      :refs (mapv (fn [[fi ti k]]
                                    (make-ref k
                                              (nth var-syms fi)
                                              (nth var-syms ti)))
                                  ref-tuples)
                      :namespaces (mapv make-ns nss)}))
          (gen/vector (gen/tuple (gen/choose 0 (dec n-vars))
                                (gen/choose 0 (dec n-vars))
                                gen-kind)
                      0 (* 3 n-vars)))))))
