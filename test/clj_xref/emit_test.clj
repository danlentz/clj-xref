(ns clj-xref.emit-test
  "Tests for EDN serialization: roundtrip, special characters, file system."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-xref.emit :as emit]
            [clj-xref.test-utils :as tu :refer [make-var make-ref make-ns make-db
                                                 with-temp-edn generate-large-db]]
            [clojure.string :as str]))

;; === Roundtrip fidelity ===

(deftest test-roundtrip-scalars
  (with-temp-edn [path]
    (let [db (make-db {:vars [] :refs [] :namespaces []})]
      (emit/write-edn db path)
      (let [loaded (emit/read-edn path)]
        (is (= (:version db) (:version loaded)))
        (is (= (:generated db) (:generated loaded)))
        (is (= (:project db) (:project loaded)))
        (is (= (:paths db) (:paths loaded)))))))

(deftest test-roundtrip-var-maps
  (with-temp-edn [path]
    (let [db (make-db
               {:vars [(make-var 'app.core 'main :fixed-arities #{1 2}
                                 :private? true :defined-by 'clojure.core/defn)
                       (make-var 'app.proto 'render
                                 :protocol {:ns 'app.proto :name 'Renderable})]
                :refs []
                :namespaces []})]
      (emit/write-edn db path)
      (let [loaded (emit/read-edn path)
            v1 (first (:vars loaded))
            v2 (second (:vars loaded))]
        (is (= #{1 2} (:fixed-arities v1)))
        (is (true? (:private? v1)))
        (is (= 'clojure.core/defn (:defined-by v1)))
        (is (= {:ns 'app.proto :name 'Renderable} (:protocol v2)))))))

(deftest test-roundtrip-ref-maps
  (with-temp-edn [path]
    (let [db (make-db
               {:vars []
                :refs [(make-ref :call 'a/f 'b/g :arity 3)
                       (make-ref :dispatch 'a/f 'a/multi :dispatch-val ":click")
                       (make-ref :implement 'a/Widget 'p/Renderable :method 'render)]
                :namespaces []})]
      (emit/write-edn db path)
      (let [loaded (emit/read-edn path)
            refs (:refs loaded)]
        (is (= 3 (count refs)))
        (is (= 3 (:arity (first refs))))
        (is (= ":click" (:dispatch-val (second refs))))
        (is (= 'render (:method (nth refs 2))))))))

(deftest test-roundtrip-empty-vectors
  (with-temp-edn [path]
    (let [db (make-db {:vars [] :refs [] :namespaces []})]
      (emit/write-edn db path)
      (let [loaded (emit/read-edn path)]
        (is (= [] (:vars loaded)))
        (is (= [] (:refs loaded)))
        (is (= [] (:namespaces loaded)))))))

(deftest test-roundtrip-large
  (with-temp-edn [path]
    (let [db (generate-large-db {:n-namespaces 20 :n-vars-per-ns 25 :n-refs-per-var 10})]
      (emit/write-edn db path)
      (let [loaded (emit/read-edn path)]
        (is (= (count (:vars db)) (count (:vars loaded))))
        (is (= (count (:refs db)) (count (:refs loaded))))
        (is (= (count (:namespaces db)) (count (:namespaces loaded))))))))

;; === Special characters ===

(deftest test-roundtrip-special-symbols
  (with-temp-edn [path]
    (let [db (make-db
               {:vars [(make-var 'my.ns '*earmuffs*)
                       (make-var 'my.ns '->Widget)
                       (make-var 'my.ns 'map->Widget)
                       (make-var 'my.ns '<top-level>)
                       (make-var 'my.ns 'kebab-case?)]
                :refs []
                :namespaces []})]
      (emit/write-edn db path)
      (let [loaded (emit/read-edn path)
            names (set (map :name (:vars loaded)))]
        (is (contains? names 'my.ns/*earmuffs*))
        (is (contains? names 'my.ns/->Widget))
        (is (contains? names 'my.ns/map->Widget))
        (is (contains? names 'my.ns/<top-level>))
        (is (contains? names 'my.ns/kebab-case?))))))

(deftest test-roundtrip-strings-with-special-chars
  (with-temp-edn [path]
    (let [db (make-db
               {:vars [(make-var 'a 'b :doc "line1\nline2\n\"quoted\"")]
                :refs []
                :namespaces [(make-ns 'a :doc "has \"quotes\" and\nnewlines")]})]
      (emit/write-edn db path)
      (let [loaded (emit/read-edn path)]
        (is (= "line1\nline2\n\"quoted\"" (:doc (first (:vars loaded)))))
        (is (= "has \"quotes\" and\nnewlines" (:doc (first (:namespaces loaded)))))))))

;; === File system behavior ===

(deftest test-write-creates-parent-dirs
  (let [dir (java.io.File/createTempFile "xref-dir" "")
        _ (.delete dir)
        path (str (.getAbsolutePath dir) "/nested/deep/xref.edn")]
    (try
      (emit/write-edn (make-db {:vars [] :refs [] :namespaces []}) path)
      (is (.exists (java.io.File. path)))
      (finally
        ;; cleanup
        (doseq [f (reverse (file-seq (java.io.File. (.getAbsolutePath dir))))]
          (.delete f))))))

(deftest test-write-overwrites
  (with-temp-edn [path]
    (emit/write-edn (make-db {:vars [(make-var 'a 'b)] :refs [] :namespaces []}) path)
    (emit/write-edn (make-db {:vars [] :refs [] :namespaces []}) path)
    (let [loaded (emit/read-edn path)]
      (is (= 0 (count (:vars loaded)))))))

(deftest test-read-nonexistent-throws
  (is (thrown? java.io.FileNotFoundException
        (emit/read-edn "/no/such/file/xref.edn"))))

;; === Greppability ===

(deftest test-one-entry-per-line
  (with-temp-edn [path]
    (let [db (make-db
               {:vars [(make-var 'app.core 'main)
                       (make-var 'app.core 'handler)]
                :refs [(make-ref :call 'app.core/main 'app.core/handler)]
                :namespaces [(make-ns 'app.core)]})
          _ (emit/write-edn db path)
          lines (str/split-lines (slurp path))]
      (is (some #(str/includes? % "app.core/main") lines))
      (is (some #(str/includes? % "app.core/handler") lines)))))
