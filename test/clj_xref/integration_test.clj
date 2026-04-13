(ns clj-xref.integration-test
  "End-to-end tests with real clj-kondo analysis."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-xref.core :as xref]
            [clj-xref.emit :as emit]
            [clj-xref.test-utils :refer [with-temp-edn froms tos]]))

;; === Analyze test fixtures ===

(deftest test-fixture-analysis
  (let [db (xref/analyze ["test-fixtures"])]

    (testing "finds all three namespaces"
      (is (= 3 (count (:namespaces db))))
      (is (= #{'sample.alpha 'sample.beta 'sample.gamma}
             (set (map :name (:namespaces db))))))

    (testing "finds expected var definitions"
      (let [var-names (set (map :name (:vars db)))]
        (is (contains? var-names 'sample.alpha/greet))
        (is (contains? var-names 'sample.alpha/farewell))
        (is (contains? var-names 'sample.alpha/*greeting-style*))
        (is (contains? var-names 'sample.alpha/with-greeting))
        (is (contains? var-names 'sample.beta/welcome))
        (is (contains? var-names 'sample.beta/process))
        (is (contains? var-names 'sample.beta/formal-welcome))
        (is (contains? var-names 'sample.gamma/Renderable))
        (is (contains? var-names 'sample.gamma/process-event))))

    (testing "who-calls greet"
      (let [callers (xref/who-calls db 'sample.alpha/greet)]
        (is (pos? (count callers)))
        (is (contains? (froms callers) 'sample.beta/welcome))))

    (testing "who-macroexpands with-greeting"
      (let [expanders (xref/who-macroexpands db 'sample.alpha/with-greeting)]
        (is (pos? (count expanders)))
        (is (contains? (froms expanders) 'sample.beta/formal-welcome))))

    (testing "who-dispatches process-event"
      (let [dispatches (xref/who-dispatches db 'sample.gamma/process-event)]
        (is (= 2 (count dispatches)))
        (is (= #{":click" ":keypress"} (set (map :dispatch-val dispatches))))))

    (testing "ns-deps"
      (is (contains? (xref/ns-deps db 'sample.beta) 'sample.alpha)))

    (testing "ns-dependents"
      (is (contains? (xref/ns-dependents db 'sample.alpha) 'sample.beta)))))

;; === Full roundtrip: analyze -> write -> load -> query ===

(deftest test-full-roundtrip
  (with-temp-edn [path]
    (let [db-raw ((requiring-resolve 'clj-xref.analyze/analyze)
                   ["test-fixtures"] {:project "roundtrip-test"})]
      (emit/write-edn db-raw path)
      (let [db (xref/load-db path)]
        (is (= "roundtrip-test" (:project db)))
        (is (pos? (count (:vars db))))
        (is (pos? (count (:refs db))))
        ;; Queries work on loaded db
        (is (pos? (count (xref/who-calls db 'sample.alpha/greet))))
        (is (= 2 (count (xref/who-dispatches db 'sample.gamma/process-event))))))))

;; === Self-analysis: clj-xref analyzes its own source ===

(deftest test-self-analysis
  (let [db (xref/analyze ["src"])]

    (testing "finds all 5 namespaces"
      (is (= 5 (count (:namespaces db)))))

    (testing "index is called by load-db, analyze, from-kondo-analysis"
      (let [callers (froms (xref/who-calls db 'clj-xref.core/index))]
        (is (contains? callers 'clj-xref.core/load-db))
        (is (contains? callers 'clj-xref.core/analyze))
        (is (contains? callers 'clj-xref.core/from-kondo-analysis))))

    (testing "write-edn is called by tool and lein plugin"
      (let [callers (froms (xref/who-calls db 'clj-xref.emit/write-edn))]
        (is (contains? callers 'clj-xref.tool/generate))
        (is (contains? callers 'leiningen.xref/xref))))

    (testing "core depends on emit but not directly on analyze"
      (let [deps (xref/ns-deps db 'clj-xref.core)]
        (is (contains? deps 'clj-xref.emit))
        ;; analyze is loaded via requiring-resolve, not a static dependency
        (is (not (contains? deps 'clj-xref.analyze)))))))

;; === from-kondo-analysis path ===

(deftest test-from-kondo-analysis
  (let [kondo-run! (requiring-resolve 'clj-kondo.core/run!)
        result (kondo-run! {:lint ["test-fixtures"]
                            :config {:analysis {:protocol-impls true}}})
        db (xref/from-kondo-analysis result {:paths ["test-fixtures"]
                                             :project "kondo-test"})]
    (is (= "kondo-test" (:project db)))
    (is (pos? (count (:vars db))))
    (is (pos? (count (xref/who-calls db 'sample.alpha/greet))))))
