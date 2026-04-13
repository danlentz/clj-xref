(ns clj-xref.core-test
  (:require [clojure.test :refer :all]
            [clj-xref.core :as xref]
            [clj-xref.emit :as emit]
            [clojure.java.io :as io]))

;; === Synthetic test data (no kondo dependency) ===

(def raw-db
  "A hand-crafted xref database for unit testing queries."
  {:version    1
   :generated  "2026-04-13T00:00:00Z"
   :project    "test-project"
   :paths      ["src"]

   :namespaces [{:name 'app.core  :file "src/app/core.clj"  :line 1 :col 1}
                {:name 'app.util  :file "src/app/util.clj"  :line 1 :col 1}
                {:name 'app.proto :file "src/app/proto.clj" :line 1 :col 1}]

   :vars [{:name 'app.core/main       :ns 'app.core  :local-name 'main
           :file "src/app/core.clj"  :line 5  :col 1}
          {:name 'app.core/handler    :ns 'app.core  :local-name 'handler
           :file "src/app/core.clj"  :line 10 :col 1}
          {:name 'app.core/process    :ns 'app.core  :local-name 'process
           :file "src/app/core.clj"  :line 15 :col 1}
          {:name 'app.util/format-name :ns 'app.util :local-name 'format-name
           :file "src/app/util.clj"  :line 3  :col 1}
          {:name 'app.util/with-log   :ns 'app.util  :local-name 'with-log
           :file "src/app/util.clj"  :line 8  :col 1 :macro? true}
          {:name 'app.proto/Renderable :ns 'app.proto :local-name 'Renderable
           :file "src/app/proto.clj" :line 3  :col 1}
          {:name 'app.core/process-event :ns 'app.core :local-name 'process-event
           :file "src/app/core.clj" :line 25 :col 1}]

   :refs [;; main calls handler
          {:kind :call :from 'app.core/main :to 'app.core/handler
           :file "src/app/core.clj" :line 6 :col 3 :arity 1}
          ;; main calls format-name
          {:kind :call :from 'app.core/main :to 'app.util/format-name
           :file "src/app/core.clj" :line 7 :col 3 :arity 1}
          ;; handler calls format-name
          {:kind :call :from 'app.core/handler :to 'app.util/format-name
           :file "src/app/core.clj" :line 11 :col 5 :arity 1}
          ;; handler macroexpands with-log
          {:kind :macroexpand :from 'app.core/handler :to 'app.util/with-log
           :file "src/app/core.clj" :line 10 :col 3}
          ;; process references format-name (passes as arg to map)
          {:kind :reference :from 'app.core/process :to 'app.util/format-name
           :file "src/app/core.clj" :line 16 :col 8}
          ;; Widget implements Renderable
          {:kind :implement :from 'app.core/Widget :to 'app.proto/Renderable
           :file "src/app/core.clj" :line 20 :col 1 :method 'render}
          ;; defmethod :click on process-event
          {:kind :dispatch :from 'app.core/process-event :to 'app.core/process-event
           :file "src/app/core.clj" :line 28 :col 1 :dispatch-val ":click"}
          ;; defmethod :keypress on process-event
          {:kind :dispatch :from 'app.core/process-event :to 'app.core/process-event
           :file "src/app/core.clj" :line 31 :col 1 :dispatch-val ":keypress"}]})

(def db (xref/index raw-db))

;; === Unit tests for query functions ===

(deftest test-who-calls
  (let [results (xref/who-calls db 'app.util/format-name)]
    (is (= 2 (count results)))
    (is (every? #(= :call (:kind %)) results))
    (is (= #{'app.core/main 'app.core/handler}
           (set (map :from results))))))

(deftest test-who-calls-no-results
  (is (empty? (xref/who-calls db 'nonexistent/var))))

(deftest test-calls-who
  (let [results (xref/calls-who db 'app.core/main)]
    (is (= 2 (count results)))
    (is (= #{'app.core/handler 'app.util/format-name}
           (set (map :to results))))))

(deftest test-who-references
  (let [results (xref/who-references db 'app.util/format-name)]
    (is (= 3 (count results)))
    (is (= #{:call :reference} (set (map :kind results))))))

(deftest test-who-macroexpands
  (let [results (xref/who-macroexpands db 'app.util/with-log)]
    (is (= 1 (count results)))
    (is (= 'app.core/handler (:from (first results))))))

(deftest test-who-implements
  (let [results (xref/who-implements db 'app.proto/Renderable)]
    (is (= 1 (count results)))
    (is (= :implement (:kind (first results))))
    (is (= 'app.core/Widget (:from (first results))))))

(deftest test-who-dispatches
  (let [results (xref/who-dispatches db 'app.core/process-event)]
    (is (= 2 (count results)))
    (is (= #{":click" ":keypress"} (set (map :dispatch-val results))))))

(deftest test-ns-vars
  (let [vars (xref/ns-vars db 'app.core)]
    (is (= 4 (count vars)))
    (is (= #{'app.core/main 'app.core/handler 'app.core/process 'app.core/process-event}
           (set (map :name vars))))))

(deftest test-ns-deps
  (let [deps (xref/ns-deps db 'app.core)]
    (is (contains? deps 'app.util))
    (is (contains? deps 'app.proto))
    (is (not (contains? deps 'app.core)))))

(deftest test-ns-dependents
  (let [deps (xref/ns-dependents db 'app.util)]
    (is (contains? deps 'app.core))
    (is (not (contains? deps 'app.util)))))

;; === EDN roundtrip ===

(deftest test-edn-roundtrip
  (let [tmp (java.io.File/createTempFile "xref-test" ".edn")
        path (.getAbsolutePath tmp)]
    (try
      (emit/write-edn raw-db path)
      (let [loaded (emit/read-edn path)]
        (is (= (:version raw-db) (:version loaded)))
        (is (= (:project raw-db) (:project loaded)))
        (is (= (count (:vars raw-db)) (count (:vars loaded))))
        (is (= (count (:refs raw-db)) (count (:refs loaded))))
        (is (= (count (:namespaces raw-db)) (count (:namespaces loaded))))
        ;; Verify we can index and query the loaded db
        (let [loaded-db (xref/index loaded)]
          (is (= 2 (count (xref/who-calls loaded-db 'app.util/format-name))))))
      (finally
        (.delete tmp)))))

;; === Integration test (requires clj-kondo) ===

(deftest test-integration-analyze
  (let [db (xref/analyze ["test-fixtures"])]
    (testing "finds all three namespaces"
      (is (= 3 (count (:namespaces db)))))

    (testing "finds var definitions"
      (is (some #(= 'sample.alpha/greet (:name %)) (:vars db)))
      (is (some #(= 'sample.beta/welcome (:name %)) (:vars db))))

    (testing "who-calls greet"
      (let [callers (xref/who-calls db 'sample.alpha/greet)]
        (is (pos? (count callers)))
        (is (some #(= 'sample.beta/welcome (:from %)) callers))))

    (testing "who-macroexpands with-greeting"
      (let [expanders (xref/who-macroexpands db 'sample.alpha/with-greeting)]
        (is (pos? (count expanders)))
        (is (some #(= 'sample.beta/formal-welcome (:from %)) expanders))))

    (testing "who-dispatches process-event"
      (let [dispatches (xref/who-dispatches db 'sample.gamma/process-event)]
        (is (= 2 (count dispatches)))))))

;; === Integration test: lein plugin / tool roundtrip ===

(deftest test-integration-roundtrip
  (let [tmp (java.io.File/createTempFile "xref-rt" ".edn")
        path (.getAbsolutePath tmp)]
    (try
      (let [db-raw ((requiring-resolve 'clj-xref.analyze/analyze)
                     ["test-fixtures"] {:project "test"})]
        (emit/write-edn db-raw path)
        (let [db (xref/load-db path)]
          (is (= "test" (:project db)))
          (is (pos? (count (:vars db))))
          (is (pos? (count (xref/who-calls db 'sample.alpha/greet))))))
      (finally
        (.delete tmp)))))
