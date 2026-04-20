(ns clj-xref.generative-test
  "Property-based tests using test.check."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clj-xref.core :as xref]
            [clj-xref.emit :as emit]
            [clj-xref.test-utils :as tu :refer [gen-raw-db with-temp-edn]]))

(def num-tests 100)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Index invariant properties                                                 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec p1-by-target-grouping num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)]
      (every? (fn [[sym entries]]
                (every? #(= sym (:to %)) entries))
              (:by-target db)))))

(defspec p2-by-source-grouping num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)]
      (every? (fn [[sym entries]]
                (every? #(= sym (:from %)) entries))
              (:by-source db)))))

(defspec p3-by-file-grouping num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)]
      (every? (fn [[path entries]]
                (every? #(= path (:file %)) entries))
              (:by-file db)))))

(defspec p4-vars-by-name num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)]
      (every? (fn [[sym var-info]]
                (= sym (:name var-info)))
              (:vars-by-name db)))))

(defspec p5-index-completeness num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          all-by-target (set (mapcat val (:by-target db)))
          all-by-source (set (mapcat val (:by-source db)))
          all-refs      (set (:refs db))]
      (and (= all-refs all-by-target)
           (= all-refs all-by-source)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query subset properties                                                    ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec p6-who-calls-subset-of-who-references num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          syms (distinct (map :to (:refs raw-db)))]
      (every? (fn [sym]
                (let [calls (set (xref/who-calls db sym))
                      refs  (set (xref/who-references db sym))]
                  (every? refs calls)))
              syms))))

(defspec p7-who-macroexpands-subset-of-who-references num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          syms (distinct (map :to (:refs raw-db)))]
      (every? (fn [sym]
                (let [macros (set (xref/who-macroexpands db sym))
                      refs   (set (xref/who-references db sym))]
                  (every? refs macros)))
              syms))))

(defspec p8-who-implements-subset-of-who-references num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          syms (distinct (map :to (:refs raw-db)))]
      (every? (fn [sym]
                (let [impls (set (xref/who-implements db sym))
                      refs  (set (xref/who-references db sym))]
                  (every? refs impls)))
              syms))))

(defspec p9-who-dispatches-subset-of-who-references num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          syms (distinct (map :to (:refs raw-db)))]
      (every? (fn [sym]
                (let [disps (set (xref/who-dispatches db sym))
                      refs  (set (xref/who-references db sym))]
                  (every? refs disps)))
              syms))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query kind correctness                                                     ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec p10-who-calls-only-call-kind num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          syms (distinct (map :to (:refs raw-db)))]
      (every? (fn [sym]
                (every? #(= :call (:kind %)) (xref/who-calls db sym)))
              syms))))

(defspec p11-who-macroexpands-only-macroexpand-kind num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          syms (distinct (map :to (:refs raw-db)))]
      (every? (fn [sym]
                (every? #(= :macroexpand (:kind %)) (xref/who-macroexpands db sym)))
              syms))))

(defspec p12-who-implements-only-implement-kind num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          syms (distinct (map :to (:refs raw-db)))]
      (every? (fn [sym]
                (every? #(= :implement (:kind %)) (xref/who-implements db sym)))
              syms))))

(defspec p13-who-dispatches-only-dispatch-kind num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          syms (distinct (map :to (:refs raw-db)))]
      (every? (fn [sym]
                (every? #(= :dispatch (:kind %)) (xref/who-dispatches db sym)))
              syms))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bidirectional consistency                                                  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec p14-who-calls-calls-who-inverse num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          call-refs (filter #(= :call (:kind %)) (:refs raw-db))]
      (every? (fn [ref]
                (and (some #{ref} (xref/who-calls db (:to ref)))
                     (some #{ref} (xref/calls-who db (:from ref)))))
              call-refs))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Namespace query properties                                                 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec p15-ns-deps-self-exclusion num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          nss (map :name (:namespaces raw-db))]
      (every? (fn [ns-sym]
                (not (contains? (xref/ns-deps db ns-sym) ns-sym)))
              nss))))

(defspec p16-ns-dependents-self-exclusion num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          nss (map :name (:namespaces raw-db))]
      (every? (fn [ns-sym]
                (not (contains? (xref/ns-dependents db ns-sym) ns-sym)))
              nss))))

(defspec p17-ns-deps-dependents-duality num-tests
  (prop/for-all [raw-db gen-raw-db]
    (let [db (xref/index raw-db)
          nss (set (map :name (:namespaces raw-db)))]
      (every? (fn [ns-a]
                (every? (fn [dep-ns]
                          (contains? (xref/ns-dependents db dep-ns) ns-a))
                        ;; Only check deps that are actually defined namespaces
                        (filter nss (xref/ns-deps db ns-a))))
              nss))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EDN roundtrip property                                                     ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defspec p18-edn-roundtrip-preservation num-tests
  (prop/for-all [raw-db gen-raw-db]
    (with-temp-edn [path]
      (emit/write-edn raw-db path)
      (let [loaded (emit/read-edn path)]
        (and (= (:version raw-db) (:version loaded))
             (= (:paths raw-db) (:paths loaded))
             (= (:project raw-db) (:project loaded))
             (= (count (:vars raw-db)) (count (:vars loaded)))
             (= (count (:refs raw-db)) (count (:refs loaded)))
             (= (count (:namespaces raw-db)) (count (:namespaces loaded)))
             (= (set (:vars raw-db)) (set (:vars loaded)))
             (= (set (:refs raw-db)) (set (:refs loaded))))))))
