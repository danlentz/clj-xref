(ns clj-xref.stress-test
  "Performance and scale tests with large synthetic databases."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-xref.core :as xref]
            [clj-xref.emit :as emit]
            [clj-xref.test-utils :as tu :refer [generate-large-db with-temp-edn timed]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Index build time                                                           ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest test-index-time-small
  (let [db (generate-large-db {:n-namespaces 10 :n-vars-per-ns 50 :n-refs-per-var 10})
        {:keys [ms]} (timed #(xref/index db))]
    (is (= 500 (count (:vars db))))
    (is (= 5000 (count (:refs db))))
    (is (< ms 2000) (str "Index build took " ms "ms, expected < 2000ms"))))

(deftest test-index-time-medium
  (let [db (generate-large-db {:n-namespaces 40 :n-vars-per-ns 50 :n-refs-per-var 10})
        {:keys [ms]} (timed #(xref/index db))]
    (is (= 2000 (count (:vars db))))
    (is (= 20000 (count (:refs db))))
    (is (< ms 5000) (str "Index build took " ms "ms, expected < 5000ms"))))

(deftest test-index-time-large
  (let [db (generate-large-db {:n-namespaces 100 :n-vars-per-ns 50 :n-refs-per-var 20})
        {:keys [ms]} (timed #(xref/index db))]
    (is (= 5000 (count (:vars db))))
    (is (= 100000 (count (:refs db))))
    (is (< ms 10000) (str "Index build took " ms "ms, expected < 10000ms"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query time on large database                                               ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def large-db
  (let [raw (generate-large-db {:n-namespaces 100 :n-vars-per-ns 50 :n-refs-per-var 20})]
    (xref/index raw)))

(deftest test-query-time-who-calls
  ;; Pick a symbol that has many callers (the first var in ns0)
  (let [{:keys [ms result]} (timed #(xref/who-calls large-db 'stress.ns0/fn0))]
    (is (< ms 50) (str "who-calls took " ms "ms, expected < 50ms"))
    (is (vector? result))))

(deftest test-query-time-calls-who
  (let [{:keys [ms result]} (timed #(xref/calls-who large-db 'stress.ns0/fn0))]
    (is (< ms 50) (str "calls-who took " ms "ms, expected < 50ms"))
    (is (vector? result))))

(deftest test-query-time-who-references
  (let [{:keys [ms result]} (timed #(xref/who-references large-db 'stress.ns0/fn0))]
    (is (< ms 50) (str "who-references took " ms "ms, expected < 50ms"))
    (is (vector? result))))

(deftest test-query-time-ns-deps
  (let [{:keys [ms result]} (timed #(xref/ns-deps large-db 'stress.ns0))]
    (is (< ms 2000) (str "ns-deps took " ms "ms, expected < 2000ms"))
    (is (set? result))))

(deftest test-query-time-ns-dependents
  (let [{:keys [ms result]} (timed #(xref/ns-dependents large-db 'stress.ns0))]
    (is (< ms 2000) (str "ns-dependents took " ms "ms, expected < 2000ms"))
    (is (set? result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EDN write/read time                                                        ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest test-edn-write-time-medium
  (let [db (generate-large-db {:n-namespaces 10 :n-vars-per-ns 50 :n-refs-per-var 10})]
    (with-temp-edn [path]
      (let [{:keys [ms]} (timed #(emit/write-edn db path))]
        (is (< ms 5000) (str "Write 5K refs took " ms "ms, expected < 5000ms"))))))

(deftest test-edn-read-time-medium
  (let [db (generate-large-db {:n-namespaces 10 :n-vars-per-ns 50 :n-refs-per-var 10})]
    (with-temp-edn [path]
      (emit/write-edn db path)
      (let [{:keys [ms]} (timed #(emit/read-edn path))]
        (is (< ms 5000) (str "Read 5K refs took " ms "ms, expected < 5000ms"))))))

(deftest test-edn-roundtrip-large
  (let [db (generate-large-db {:n-namespaces 100 :n-vars-per-ns 50 :n-refs-per-var 20})]
    (with-temp-edn [path]
      (let [{write-ms :ms} (timed #(emit/write-edn db path))
            {read-ms :ms}  (timed #(emit/read-edn path))
            file-size (.length (java.io.File. path))]
        (is (< write-ms 30000) (str "Write 100K refs took " write-ms "ms"))
        (is (< read-ms 30000) (str "Read 100K refs took " read-ms "ms"))
        ;; File size sanity check: should be under 30MB
        (is (< file-size (* 30 1024 1024))
            (str "File is " (/ file-size 1024 1024.0) "MB, expected < 30MB"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Memory stability (smoke check)                                             ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest test-repeated-queries-no-crash
  (dotimes [_ 100]
    (xref/who-calls large-db 'stress.ns0/fn0)
    (xref/calls-who large-db 'stress.ns0/fn0)
    (xref/who-references large-db 'stress.ns0/fn0)))
