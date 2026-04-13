(ns clj-xref.graph
  "DOT/Graphviz output for namespace and call graphs."
  (:require [clj-xref.core :as xref]
            [clojure.string :as str]))

(defn- dot-escape [s]
  (str "\"" (str/replace (str s) "\"" "\\\"") "\""))

(defn- edges->dot
  "Render a set of [from to] edges as a DOT digraph string."
  [edges {:keys [label]}]
  (let [lines (map (fn [[from to]]
                     (str "  " (dot-escape from) " -> " (dot-escape to)))
                   (sort-by (juxt first second) edges))]
    (str "digraph "
         (if label (dot-escape label) "G")
         " {\n"
         (str/join "\n" lines)
         "\n}\n")))

(defn ns-dep-dot
  "Generate DOT output for the namespace dependency graph.
   Options:
     :namespaces - restrict to these namespaces (default: all in db)"
  [db & [{:keys [namespaces]}]]
  (let [nss (or namespaces (map :name (:namespaces db)))
        edges (set (for [ns-sym nss
                         dep (xref/ns-deps db ns-sym)]
                     [(str ns-sym) (str dep)]))]
    (edges->dot edges {:label "namespace-deps"})))

(defn call-graph-dot
  "Generate DOT output for a call graph rooted at `sym`.
   Options are passed to `clj-xref.core/call-graph`."
  [db sym & [opts]]
  (let [edges (xref/call-graph db sym opts)
        str-edges (set (map (fn [[from to]] [(str from) (str to)]) edges))]
    (edges->dot str-edges {:label (str sym)})))
