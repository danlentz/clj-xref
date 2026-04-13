(ns clj-xref.adversarial-test
  "Edge cases, boundary conditions, and malformed input."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-xref.core :as xref]
            [clj-xref.emit :as emit]
            [clj-xref.test-utils :as tu :refer [make-var make-ref make-ns
                                                 make-db make-indexed-db
                                                 with-temp-edn]]))

;; === Empty and minimal databases ===

(deftest test-completely-empty-db
  (let [db (make-indexed-db {:vars [] :refs [] :namespaces []})]
    (is (= [] (xref/who-calls db 'a/b)))
    (is (= [] (xref/calls-who db 'a/b)))
    (is (= [] (xref/who-references db 'a/b)))
    (is (= [] (xref/who-macroexpands db 'a/b)))
    (is (= [] (xref/who-implements db 'a/b)))
    (is (= [] (xref/who-dispatches db 'a/b)))
    (is (= [] (xref/ns-vars db 'a)))
    (is (= #{} (xref/ns-deps db 'a)))
    (is (= #{} (xref/ns-dependents db 'a)))))

(deftest test-refs-but-no-vars
  (let [db (make-indexed-db
             {:vars []
              :refs [(make-ref :call 'a/f 'b/g)]
              :namespaces []})]
    (is (= 1 (count (xref/who-calls db 'b/g))))
    (is (nil? (get (:vars-by-name db) 'a/f)))))

(deftest test-vars-but-no-refs
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'f)]
              :refs []
              :namespaces []})]
    (is (= [] (xref/who-calls db 'a/f)))
    (is (= [] (xref/calls-who db 'a/f)))
    (is (= 1 (count (xref/ns-vars db 'a))))))

(deftest test-no-namespaces
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'f)]
              :refs [(make-ref :call 'a/f 'b/g)]
              :namespaces []})]
    (is (= {} (:ns-by-name db)))
    (is (= 1 (count (xref/who-calls db 'b/g))))))

;; === Symbol edge cases ===

(deftest test-unqualified-symbol
  (is (= [] (xref/who-calls (make-indexed-db {:vars [] :refs [] :namespaces []}) 'foo))))

(deftest test-nil-symbol
  (let [db (make-indexed-db {:vars [] :refs [] :namespaces []})]
    (is (= [] (xref/who-calls db nil)))
    (is (= [] (xref/calls-who db nil)))
    (is (= [] (xref/who-references db nil)))))

(deftest test-single-segment-namespace
  (let [db (make-indexed-db
             {:vars [(make-var 'util 'foo)]
              :refs [(make-ref :call 'util/foo 'util/foo)]
              :namespaces [(make-ns 'util)]})]
    (is (= 1 (count (xref/who-calls db 'util/foo))))))

(deftest test-deep-namespace
  (let [deep-ns 'a.b.c.d.e.f.g.h
        db (make-indexed-db
             {:vars [(make-var deep-ns 'foo)]
              :refs [(make-ref :call (symbol (str deep-ns) "foo")
                                     (symbol (str deep-ns) "foo"))]
              :namespaces [(make-ns deep-ns)]})]
    (is (= 1 (count (xref/ns-vars db deep-ns))))))

(deftest test-special-character-symbols
  (let [db (make-indexed-db
             {:vars [(make-var 'my.ns '*earmuffs*)
                     (make-var 'my.ns '->Widget)
                     (make-var 'my.ns 'map->Widget)
                     (make-var 'my.ns '<top-level>)
                     (make-var 'my.ns 'foo?)
                     (make-var 'my.ns 'foo!)]
              :refs [(make-ref :call 'my.ns/foo? 'my.ns/*earmuffs*)]
              :namespaces [(make-ns 'my.ns)]})]
    (is (= 6 (count (xref/ns-vars db 'my.ns))))
    (is (= 1 (count (xref/who-calls db 'my.ns/*earmuffs*))))))

;; === Circular and self-referential ===

(deftest test-mutual-recursion
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'f) (make-var 'a 'g)]
              :refs [(make-ref :call 'a/f 'a/g)
                     (make-ref :call 'a/g 'a/f)]
              :namespaces [(make-ns 'a)]})]
    (is (= #{'a/g} (tu/froms (xref/who-calls db 'a/f))))
    (is (= #{'a/f} (tu/froms (xref/who-calls db 'a/g))))
    (is (= #{'a/g} (tu/tos (xref/calls-who db 'a/f))))
    (is (= #{'a/f} (tu/tos (xref/calls-who db 'a/g))))))

(deftest test-self-recursion
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'f)]
              :refs [(make-ref :call 'a/f 'a/f)]
              :namespaces [(make-ns 'a)]})]
    (is (= 1 (count (xref/who-calls db 'a/f))))
    (is (= 1 (count (xref/calls-who db 'a/f))))))

(deftest test-namespace-cycle
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'f) (make-var 'b 'g)]
              :refs [(make-ref :call 'a/f 'b/g)
                     (make-ref :call 'b/g 'a/f)]
              :namespaces [(make-ns 'a) (make-ns 'b)]})]
    (is (contains? (xref/ns-deps db 'a) 'b))
    (is (contains? (xref/ns-deps db 'b) 'a))
    (is (contains? (xref/ns-dependents db 'a) 'b))
    (is (contains? (xref/ns-dependents db 'b) 'a))))

;; === Duplicate entries ===

(deftest test-duplicate-refs
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'f) (make-var 'b 'g)]
              :refs [(make-ref :call 'a/f 'b/g)
                     (make-ref :call 'a/f 'b/g)]
              :namespaces []})]
    (is (= 2 (count (xref/who-calls db 'b/g))))))

(deftest test-duplicate-vars
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'f :line 1)
                     (make-var 'a 'f :line 99)]
              :refs []
              :namespaces []})]
    ;; vars-by-name keeps the last one (map insert semantics)
    (is (= 99 (:line (get (:vars-by-name db) 'a/f))))
    ;; ns-vars returns both
    (is (= 2 (count (xref/ns-vars db 'a))))))

;; === Transform edge cases ===

(deftest test-zero-arity
  (let [db (make-indexed-db
             {:vars []
              :refs [(make-ref :call 'a/f 'b/g :arity 0)]
              :namespaces []})]
    (is (= 1 (count (xref/who-calls db 'b/g))))))

;; === File system edge cases ===

(deftest test-load-nonexistent-file
  (is (thrown? java.io.FileNotFoundException
        (xref/load-db "/no/such/file.edn"))))

(deftest test-load-empty-file
  (with-temp-edn [path]
    (spit path "")
    (is (thrown? Exception (emit/read-edn path)))))

(deftest test-load-invalid-edn
  (with-temp-edn [path]
    (spit path "{:broken [}")
    (is (thrown? Exception (emit/read-edn path)))))

;; === from-kondo-analysis without analysis data ===

(deftest test-from-kondo-analysis-missing-analysis
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
        #"no :analysis data"
        (xref/from-kondo-analysis {:findings [] :summary {}}))))
