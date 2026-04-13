(ns clj-xref.analyze-test
  "Unit tests for the clj-kondo -> clj-xref transform layer."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-xref.analyze :as analyze]))

;; Access private transform functions for direct unit testing
(def ^:private classify-kind     @#'analyze/classify-kind)
(def ^:private transform-var-def @#'analyze/transform-var-def)
(def ^:private transform-var-usage @#'analyze/transform-var-usage)
(def ^:private transform-protocol-impl @#'analyze/transform-protocol-impl)
(def ^:private transform-ns-def @#'analyze/transform-ns-def)
(def ^:private build-kondo-config @#'analyze/build-kondo-config)

;; === classify-kind ===

(deftest test-classify-kind-dispatch-wins
  (is (= :dispatch (classify-kind {:defmethod true})))
  (is (= :dispatch (classify-kind {:defmethod true :macro true})))
  (is (= :dispatch (classify-kind {:defmethod true :arity 3})))
  (is (= :dispatch (classify-kind {:defmethod true :macro true :arity 1}))))

(deftest test-classify-kind-macro-second
  (is (= :macroexpand (classify-kind {:macro true})))
  (is (= :macroexpand (classify-kind {:macro true :arity 2}))))

(deftest test-classify-kind-call
  (is (= :call (classify-kind {:arity 2})))
  (is (= :call (classify-kind {:arity 0}))))

(deftest test-classify-kind-reference
  (is (= :reference (classify-kind {})))
  (is (= :reference (classify-kind {:some-other-key true}))))

;; === transform-var-def ===

(deftest test-transform-var-def-minimal
  (let [vd {:ns 'my.ns :name 'foo :filename "src/my/ns.clj" :row 5 :col 1}
        result (transform-var-def vd)]
    (is (= 'my.ns/foo (:name result)))
    (is (= 'my.ns (:ns result)))
    (is (= 'foo (:local-name result)))
    (is (= "src/my/ns.clj" (:file result)))
    (is (= 5 (:line result)))
    (is (= 1 (:col result)))
    ;; Optional fields should be absent
    (is (not (contains? result :private?)))
    (is (not (contains? result :macro?)))
    (is (not (contains? result :end-line)))
    (is (not (contains? result :defined-by)))
    (is (not (contains? result :doc)))
    (is (not (contains? result :protocol)))))

(deftest test-transform-var-def-full
  (let [vd {:ns 'my.ns :name 'bar :filename "src/my/ns.clj"
            :row 10 :col 1 :end-row 15 :end-col 20
            :fixed-arities #{1 2} :varargs-min-arity 3
            :private true :macro true
            :defined-by 'clojure.core/defmacro
            :doc "A doc string"
            :protocol-ns 'my.proto :protocol-name 'MyProto}
        result (transform-var-def vd)]
    (is (= 'my.ns/bar (:name result)))
    (is (= 15 (:end-line result)))
    (is (= 20 (:end-col result)))
    (is (= #{1 2} (:fixed-arities result)))
    (is (= 3 (:varargs-min-arity result)))
    (is (true? (:private? result)))
    (is (true? (:macro? result)))
    (is (= 'clojure.core/defmacro (:defined-by result)))
    (is (= "A doc string" (:doc result)))
    (is (= {:ns 'my.proto :name 'MyProto} (:protocol result)))))

(deftest test-transform-var-def-boolean-absent-when-falsey
  (let [vd {:ns 'x :name 'y :filename "x.clj" :row 1 :col 1
            :private nil :macro false}
        result (transform-var-def vd)]
    (is (not (contains? result :private?)))
    (is (not (contains? result :macro?)))))

;; === transform-var-usage ===

(deftest test-transform-var-usage-call
  (let [vu {:from 'my.ns :from-var 'handler :to 'other.ns :name 'fmt
            :filename "src/my/ns.clj" :row 10 :col 5 :arity 2}
        result (transform-var-usage vu)]
    (is (= :call (:kind result)))
    (is (= 'my.ns/handler (:from result)))
    (is (= 'other.ns/fmt (:to result)))
    (is (= 2 (:arity result)))))

(deftest test-transform-var-usage-reference
  (let [vu {:from 'my.ns :from-var 'process :to 'other.ns :name 'fmt
            :filename "src/my/ns.clj" :row 10 :col 5}
        result (transform-var-usage vu)]
    (is (= :reference (:kind result)))
    (is (not (contains? result :arity)))))

(deftest test-transform-var-usage-macroexpand
  (let [vu {:from 'my.ns :from-var 'handler :to 'other.ns :name 'with-log
            :filename "src/my/ns.clj" :row 10 :col 3 :macro true :arity 2}
        result (transform-var-usage vu)]
    (is (= :macroexpand (:kind result)))))

(deftest test-transform-var-usage-dispatch
  (let [vu {:from 'my.ns :from-var nil :to 'my.ns :name 'process-event
            :filename "src/my/ns.clj" :row 20 :col 1
            :defmethod true :dispatch-val-str ":click"}
        result (transform-var-usage vu)]
    (is (= :dispatch (:kind result)))
    (is (= ":click" (:dispatch-val result)))))

(deftest test-transform-var-usage-top-level
  (let [vu {:from 'my.ns :from-var nil :to 'clojure.core :name 'defn
            :filename "src/my/ns.clj" :row 1 :col 1 :macro true}
        result (transform-var-usage vu)]
    (is (= 'my.ns/<top-level> (:from result)))))

(deftest test-transform-var-usage-nil-to
  (let [vu {:from 'my.ns :from-var 'foo :to nil :name nil
            :filename "src/my/ns.clj" :row 1 :col 1}
        result (transform-var-usage vu)]
    (is (nil? (:to result)))))

;; === transform-protocol-impl ===

(deftest test-transform-protocol-impl-with-type-inference
  (let [var-defs [{:ns 'my.types :name 'Widget :filename "src/my/types.clj"
                   :row 5 :col 1 :end-row 15 :end-col 10
                   :defined-by 'clojure.core/defrecord}
                  {:ns 'my.types :name '->Widget :filename "src/my/types.clj"
                   :row 5 :col 1 :end-row 15 :end-col 10
                   :defined-by 'clojure.core/defrecord}]
        pi {:impl-ns 'my.types :method-name 'render
            :protocol-ns 'my.proto :protocol-name 'Renderable
            :filename "src/my/types.clj" :row 10 :col 1
            :defined-by 'clojure.core/defrecord}
        result (transform-protocol-impl pi var-defs)]
    (is (= :implement (:kind result)))
    (is (= 'my.types/Widget (:from result)))
    (is (= 'my.proto/Renderable (:to result)))
    (is (= 'render (:method result)))))

(deftest test-transform-protocol-impl-no-enclosing-type
  ;; When no matching defrecord/deftype encloses the impl, fall back to method name
  (let [pi {:impl-ns 'my.types :method-name 'render
            :protocol-ns 'my.proto :protocol-name 'Renderable
            :filename "src/my/types.clj" :row 10 :col 1
            :defined-by 'clojure.core/extend-protocol}
        result (transform-protocol-impl pi [])]
    (is (= 'my.types/render (:from result)))))

;; === transform-ns-def ===

(deftest test-transform-ns-def-minimal
  (let [nd {:name 'my.ns :filename "src/my/ns.clj" :row 1 :col 1}
        result (transform-ns-def nd)]
    (is (= 'my.ns (:name result)))
    (is (not (contains? result :doc)))))

(deftest test-transform-ns-def-with-doc
  (let [nd {:name 'my.ns :filename "src/my/ns.clj" :row 1 :col 1
            :doc "A namespace"}
        result (transform-ns-def nd)]
    (is (= "A namespace" (:doc result)))))

;; === transform-analysis ===

(deftest test-transform-analysis-full
  (let [analysis {:var-definitions [{:ns 'a :name 'f :filename "a.clj" :row 1 :col 1}]
                  :var-usages [{:from 'a :from-var 'f :to 'b :name 'g
                                :filename "a.clj" :row 2 :col 3 :arity 1}]
                  :protocol-impls [{:impl-ns 'a :method-name 'render
                                    :protocol-ns 'p :protocol-name 'R
                                    :filename "a.clj" :row 5 :col 1}]
                  :namespace-definitions [{:name 'a :filename "a.clj" :row 1 :col 1}]}
        result (analyze/transform-analysis analysis {:paths ["src"] :project "test"})]
    (is (= 1 (:version result)))
    (is (string? (:generated result)))
    (is (= ["src"] (:paths result)))
    (is (= "test" (:project result)))
    (is (= 1 (count (:vars result))))
    (is (= 2 (count (:refs result))))
    (is (= 1 (count (:namespaces result))))))

(deftest test-transform-analysis-empty
  (let [analysis {:var-definitions [] :var-usages []
                  :protocol-impls nil :namespace-definitions []}
        result (analyze/transform-analysis analysis {:paths [] :project nil})]
    (is (= 1 (:version result)))
    (is (= [] (:vars result)))
    (is (= [] (:refs result)))
    (is (= [] (:namespaces result)))
    (is (not (contains? result :project)))))

;; === build-kondo-config (deep merge) ===

(deftest test-build-kondo-config-preserves-defaults
  (let [cfg (build-kondo-config {:analysis {:locals true}})]
    (is (true? (get-in cfg [:analysis :protocol-impls])))
    (is (true? (get-in cfg [:analysis :arglists])))
    (is (true? (get-in cfg [:analysis :locals])))))

(deftest test-build-kondo-config-no-user-analysis
  (let [cfg (build-kondo-config {:output {:format :edn}})]
    (is (true? (get-in cfg [:analysis :protocol-impls])))
    (is (true? (get-in cfg [:analysis :arglists])))
    (is (= :edn (get-in cfg [:output :format])))))

(deftest test-build-kondo-config-nil
  (let [cfg (build-kondo-config nil)]
    (is (true? (get-in cfg [:analysis :protocol-impls])))))
