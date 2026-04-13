(ns clj-xref.graph-test
  "Tests for DOT/Graphviz output."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-xref.core :as xref]
            [clj-xref.graph :as graph]
            [clj-xref.test-utils :refer [make-var make-ref make-ns make-indexed-db]]
            [clojure.string :as str]))

(def db
  (make-indexed-db
    {:vars [(make-var 'app.core 'main)
            (make-var 'app.core 'handler)
            (make-var 'app.util 'fmt)]
     :refs [(make-ref :call 'app.core/main 'app.core/handler)
            (make-ref :call 'app.core/main 'app.util/fmt)
            (make-ref :call 'app.core/handler 'app.util/fmt)]
     :namespaces [(make-ns 'app.core)
                  (make-ns 'app.util)]}))

;; === ns-dep-dot ===

(deftest test-ns-dep-dot
  (let [dot (graph/ns-dep-dot db)]
    (is (str/starts-with? dot "digraph"))
    (is (str/includes? dot "\"app.core\" -> \"app.util\""))
    (is (str/ends-with? (str/trim dot) "}"))))

(deftest test-ns-dep-dot-subset
  (let [dot (graph/ns-dep-dot db {:namespaces ['app.core]})]
    (is (str/includes? dot "\"app.core\" -> \"app.util\""))
    ;; app.util has no deps, so no outgoing edges from it
    (is (not (str/includes? dot "\"app.util\" ->")))))

;; === call-graph-dot ===

(deftest test-call-graph-dot
  (let [dot (graph/call-graph-dot db 'app.core/main {:depth 2})]
    (is (str/starts-with? dot "digraph"))
    (is (str/includes? dot "\"app.core/main\" -> \"app.core/handler\""))
    (is (str/includes? dot "\"app.core/main\" -> \"app.util/fmt\""))
    (is (str/includes? dot "\"app.core/handler\" -> \"app.util/fmt\""))))

(deftest test-call-graph-dot-empty
  (let [dot (graph/call-graph-dot db 'app.util/fmt {:depth 1})]
    ;; No outgoing calls from fmt
    (is (str/starts-with? dot "digraph"))
    (is (not (str/includes? dot "->")))))
