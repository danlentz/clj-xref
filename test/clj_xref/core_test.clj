(ns clj-xref.core-test
  "Unit tests for the query API."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-xref.core :as xref]
            [clj-xref.test-utils :as tu :refer [make-var make-ref make-ns
                                                 make-indexed-db froms tos kinds]]))

(def db
  (make-indexed-db
    {:vars [(make-var 'app.core 'main)
            (make-var 'app.core 'handler)
            (make-var 'app.core 'process)
            (make-var 'app.util 'format-name)
            (make-var 'app.util 'with-log :macro? true)
            (make-var 'app.proto 'Renderable)
            (make-var 'app.core 'process-event)]
     :refs [;; main calls handler and format-name
            (make-ref :call 'app.core/main 'app.core/handler)
            (make-ref :call 'app.core/main 'app.util/format-name)
            ;; handler calls format-name, macroexpands with-log
            (make-ref :call 'app.core/handler 'app.util/format-name)
            (make-ref :macroexpand 'app.core/handler 'app.util/with-log)
            ;; process references format-name (HOF)
            (make-ref :reference 'app.core/process 'app.util/format-name)
            ;; protocol impl
            (make-ref :implement 'app.core/Widget 'app.proto/Renderable :method 'render)
            ;; multimethod dispatches
            (make-ref :dispatch 'app.core/process-event 'app.core/process-event
                      :dispatch-val ":click")
            (make-ref :dispatch 'app.core/process-event 'app.core/process-event
                      :dispatch-val ":keypress")]
     :namespaces [(make-ns 'app.core)
                  (make-ns 'app.util)
                  (make-ns 'app.proto)]}))

;; === index ===

(deftest test-index-by-target
  (doseq [[sym entries] (:by-target db)]
    (is (every? #(= sym (:to %)) entries))))

(deftest test-index-by-source
  (doseq [[sym entries] (:by-source db)]
    (is (every? #(= sym (:from %)) entries))))

(deftest test-index-by-file
  (doseq [[path entries] (:by-file db)]
    (is (every? #(= path (:file %)) entries))))

(deftest test-index-vars-by-name
  (doseq [[sym var-info] (:vars-by-name db)]
    (is (= sym (:name var-info)))))

(deftest test-index-ns-by-name
  (doseq [[sym ns-info] (:ns-by-name db)]
    (is (= sym (:name ns-info)))))

(deftest test-index-empty
  (let [empty-db (xref/index {:vars [] :refs [] :namespaces []})]
    (is (= {} (:by-target empty-db)))
    (is (= {} (:by-source empty-db)))
    (is (= {} (:by-file empty-db)))
    (is (= {} (:vars-by-name empty-db)))
    (is (= {} (:ns-by-name empty-db)))))

;; === who-calls ===

(deftest test-who-calls
  (let [results (xref/who-calls db 'app.util/format-name)]
    (is (= 2 (count results)))
    (is (every? #(= :call (:kind %)) results))
    (is (= #{'app.core/main 'app.core/handler} (froms results)))))

(deftest test-who-calls-excludes-non-call
  (let [results (xref/who-calls db 'app.util/format-name)]
    ;; process has a :reference to format-name, should not appear
    (is (not (contains? (froms results) 'app.core/process)))))

(deftest test-who-calls-no-results
  (is (= [] (xref/who-calls db 'nonexistent/var))))

(deftest test-who-calls-self-recursion
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'f)]
              :refs [(make-ref :call 'a/f 'a/f)]
              :namespaces [(make-ns 'a)]})]
    (is (= 1 (count (xref/who-calls db 'a/f))))
    (is (= 'a/f (:from (first (xref/who-calls db 'a/f)))))))

;; === calls-who ===

(deftest test-calls-who
  (let [results (xref/calls-who db 'app.core/main)]
    (is (= 2 (count results)))
    (is (= #{'app.core/handler 'app.util/format-name} (tos results)))))

(deftest test-calls-who-excludes-macroexpand
  ;; handler macroexpands with-log, but calls-who should exclude it
  (let [results (xref/calls-who db 'app.core/handler)]
    (is (every? #(= :call (:kind %)) results))
    (is (not (contains? (tos results) 'app.util/with-log)))))

(deftest test-calls-who-no-results
  (is (= [] (xref/calls-who db 'app.util/format-name))))

;; === who-references ===

(deftest test-who-references-all-kinds
  (let [results (xref/who-references db 'app.util/format-name)]
    (is (= 3 (count results)))
    (is (= #{:call :reference} (kinds results)))))

(deftest test-who-references-superset-of-who-calls
  (let [refs (set (xref/who-references db 'app.util/format-name))
        calls (set (xref/who-calls db 'app.util/format-name))]
    (is (every? refs calls))))

;; === who-macroexpands ===

(deftest test-who-macroexpands
  (let [results (xref/who-macroexpands db 'app.util/with-log)]
    (is (= 1 (count results)))
    (is (= 'app.core/handler (:from (first results))))))

(deftest test-who-macroexpands-no-results
  (is (= [] (xref/who-macroexpands db 'app.util/format-name))))

;; === who-implements ===

(deftest test-who-implements
  (let [results (xref/who-implements db 'app.proto/Renderable)]
    (is (= 1 (count results)))
    (is (= 'app.core/Widget (:from (first results))))))

(deftest test-who-implements-multiple
  (let [db (make-indexed-db
             {:vars [] :namespaces []
              :refs [(make-ref :implement 'a/WidgetA 'p/R :method 'render)
                     (make-ref :implement 'b/WidgetB 'p/R :method 'render)]})]
    (is (= 2 (count (xref/who-implements db 'p/R))))))

(deftest test-who-implements-no-results
  (is (= [] (xref/who-implements db 'app.core/main))))

;; === who-dispatches ===

(deftest test-who-dispatches
  (let [results (xref/who-dispatches db 'app.core/process-event)]
    (is (= 2 (count results)))
    (is (= #{":click" ":keypress"} (set (map :dispatch-val results))))))

(deftest test-who-dispatches-no-results
  (is (= [] (xref/who-dispatches db 'app.core/main))))

;; === ns-vars ===

(deftest test-ns-vars
  (let [vars (xref/ns-vars db 'app.core)]
    (is (= 4 (count vars)))
    (is (= #{'app.core/main 'app.core/handler 'app.core/process 'app.core/process-event}
           (set (map :name vars))))))

(deftest test-ns-vars-empty
  (is (= [] (xref/ns-vars db 'nonexistent.ns))))

;; === ns-deps ===

(deftest test-ns-deps
  (let [deps (xref/ns-deps db 'app.core)]
    (is (contains? deps 'app.util))
    (is (contains? deps 'app.proto))
    (is (not (contains? deps 'app.core)))))

(deftest test-ns-deps-isolated
  (is (= #{} (xref/ns-deps db 'app.proto))))

;; === ns-dependents ===

(deftest test-ns-dependents
  (let [deps (xref/ns-dependents db 'app.util)]
    (is (contains? deps 'app.core))
    (is (not (contains? deps 'app.util)))))

(deftest test-ns-dependents-no-dependents
  (is (= #{} (xref/ns-dependents db 'app.core))))

;; === unused-vars ===

(deftest test-unused-vars
  (let [unused (set (map :name (xref/unused-vars db)))]
    ;; main is never the :to of any ref — it IS unused
    (is (contains? unused 'app.core/main))
    ;; handler is called by main
    (is (not (contains? unused 'app.core/handler)))
    ;; format-name and with-log are referenced
    (is (not (contains? unused 'app.util/format-name)))
    (is (not (contains? unused 'app.util/with-log)))))

(deftest test-unused-vars-include-private
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'pub)
                     (make-var 'a 'priv :private? true)]
              :refs []
              :namespaces [(make-ns 'a)]})
        without-private (map :name (xref/unused-vars db))
        with-private (map :name (xref/unused-vars db {:include-private? true}))]
    (is (= ['a/pub] without-private))
    (is (= #{'a/pub 'a/priv} (set with-private)))))

;; === call-graph ===

(deftest test-call-graph-outgoing
  (let [edges (xref/call-graph db 'app.core/main {:depth 2 :direction :outgoing})]
    ;; main -> handler, main -> format-name
    (is (contains? edges ['app.core/main 'app.core/handler]))
    (is (contains? edges ['app.core/main 'app.util/format-name]))
    ;; handler -> format-name (depth 2)
    (is (contains? edges ['app.core/handler 'app.util/format-name]))))

(deftest test-call-graph-incoming
  (let [edges (xref/call-graph db 'app.util/format-name {:depth 2 :direction :incoming})]
    ;; handler -> format-name, main -> format-name
    (is (contains? edges ['app.core/handler 'app.util/format-name]))
    (is (contains? edges ['app.core/main 'app.util/format-name]))))

(deftest test-call-graph-depth-limit
  (let [edges-d1 (xref/call-graph db 'app.core/main {:depth 1})
        edges-d2 (xref/call-graph db 'app.core/main {:depth 2})]
    ;; depth 1: only direct callees
    (is (every? #(= 'app.core/main (first %)) edges-d1))
    ;; depth 2: includes transitive
    (is (>= (count edges-d2) (count edges-d1)))))

(deftest test-call-graph-cycle
  (let [db (make-indexed-db
             {:vars [(make-var 'a 'f) (make-var 'a 'g)]
              :refs [(make-ref :call 'a/f 'a/g)
                     (make-ref :call 'a/g 'a/f)]
              :namespaces [(make-ns 'a)]})
        edges (xref/call-graph db 'a/f {:depth 10})]
    ;; Both edges present — back-edges are preserved
    (is (contains? edges ['a/f 'a/g]))
    (is (contains? edges ['a/g 'a/f]))
    ;; Should terminate despite cycle
    (is (= 2 (count edges)))))

(deftest test-call-graph-empty
  (is (= #{} (xref/call-graph db 'app.util/format-name {:depth 3 :direction :outgoing}))))

;; === apropos ===

(deftest test-apropos
  (let [results (xref/apropos db "format")]
    (is (= 1 (count results)))
    (is (= 'app.util/format-name (:name (first results))))))

(deftest test-apropos-regex
  (let [results (xref/apropos db #"(?i)process")]
    (is (= #{'app.core/process 'app.core/process-event}
           (set (map :name results))))))

(deftest test-apropos-no-match
  (is (= [] (xref/apropos db "zzzzz"))))
