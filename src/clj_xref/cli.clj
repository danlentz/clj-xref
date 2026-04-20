(ns clj-xref.cli
  "Command-line interface for clj-xref.

   Usage:
     clj -M:xref init
     clj -M:xref who-calls my.ns/fn
     clj -M:xref calls-who my.ns/fn
     clj -M:xref who-implements my.ns/Protocol
     clj -M:xref who-dispatches my.ns/multi
     clj -M:xref who-macroexpands my.ns/macro
     clj -M:xref unused
     clj -M:xref ns-deps my.ns
     clj -M:xref ns-dependents my.ns
     clj -M:xref apropos pattern
     clj -M:xref graph my.ns/fn"
  (:require [clj-xref.core :as xref]
            [clj-xref.analyze :as analyze]
            [clj-xref.emit :as emit]
            [clj-xref.graph :as graph]
            [clj-format.core :refer [clj-format]]))

(def ^:private +db-path+ ".clj-xref/xref.edn")
(def ^:private +default-paths+ ["src"])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Output formats                                                             ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private fmt-ref
  ["  " :str " (" :str ":" :int ")" :nl])

(def ^:private fmt-ref-callee
  ["  " :str " (" :str ":" :int ")" :nl])

(def ^:private fmt-dispatch
  ["  " :str " [" :str "] (" :str ":" :int ")" :nl])

(def ^:private fmt-impl
  ["  " :str " method " :str " (" :str ":" :int ")" :nl])

(def ^:private fmt-var
  ["  " :str " (" :str ":" :int ")" :nl])

(def ^:private fmt-ns
  ["  " :str :nl])

(def ^:private fmt-edge
  ["  " :str " -> " :str :nl])

(def ^:private fmt-count
  [:nl :int " result" [:plural {:rewind true}] :nl])

(def ^:private fmt-none
  ["  (none)" :nl])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database management                                                        ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ensure-db
  "Load the xref database, generating it first if the file doesn't exist."
  []
  (when-not (.exists (java.io.File. +db-path+))
    (clj-format true ["clj-xref: generating database..." :nl])
    (let [db (analyze/analyze +default-paths+)]
      (emit/write-edn db +db-path+)
      (clj-format true ["clj-xref: wrote " :int " var" [:plural {:rewind true}]
                         ", " :int " ref" [:plural {:rewind true}] :nl]
                  (count (:vars db)) (count (:refs db)))))
  (xref/load-db +db-path+))

(defn- do-init
  "Generate or regenerate the xref database."
  [_args]
  (clj-format true ["clj-xref: generating database..." :nl])
  (let [db (analyze/analyze +default-paths+)]
    (emit/write-edn db +db-path+)
    (clj-format true ["clj-xref: wrote " :int " var" [:plural {:rewind true}]
                       ", " :int " ref" [:plural {:rewind true}] :nl]
                (count (:vars db)) (count (:refs db)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query commands                                                             ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-sym [s]
  (when s (symbol s)))

(defn- print-refs
  "Print a list of xref entries with :from, file, and line."
  [results]
  (if (empty? results)
    (clj-format true fmt-none)
    (doseq [r results]
      (clj-format true fmt-ref (str (:from r)) (str (:file r)) (:line r))))
  (clj-format true fmt-count (count results)))

(defn- print-callees
  "Print a list of xref entries with :to, file, and line."
  [results]
  (if (empty? results)
    (clj-format true fmt-none)
    (doseq [r results]
      (clj-format true fmt-ref-callee (str (:to r)) (str (:file r)) (:line r))))
  (clj-format true fmt-count (count results)))

(defn- do-who-calls [args]
  (let [db  (ensure-db)
        sym (parse-sym (first args))]
    (print-refs (xref/who-calls db sym))))

(defn- do-calls-who [args]
  (let [db  (ensure-db)
        sym (parse-sym (first args))]
    (print-callees (xref/calls-who db sym))))

(defn- do-who-references [args]
  (let [db  (ensure-db)
        sym (parse-sym (first args))]
    (print-refs (xref/who-references db sym))))

(defn- do-who-implements [args]
  (let [db      (ensure-db)
        sym     (parse-sym (first args))
        results (xref/who-implements db sym)]
    (if (empty? results)
      (clj-format true fmt-none)
      (doseq [r results]
        (clj-format true fmt-impl
                    (str (:from r)) (str (:method r))
                    (str (:file r)) (:line r))))
    (clj-format true fmt-count (count results))))

(defn- do-who-dispatches [args]
  (let [db      (ensure-db)
        sym     (parse-sym (first args))
        results (xref/who-dispatches db sym)]
    (if (empty? results)
      (clj-format true fmt-none)
      (doseq [r results]
        (clj-format true fmt-dispatch
                    (str (:from r)) (str (:dispatch-val r))
                    (str (:file r)) (:line r))))
    (clj-format true fmt-count (count results))))

(defn- do-who-macroexpands [args]
  (let [db  (ensure-db)
        sym (parse-sym (first args))]
    (print-refs (xref/who-macroexpands db sym))))

(defn- do-unused [_args]
  (let [db      (ensure-db)
        results (xref/unused-vars db)]
    (if (empty? results)
      (clj-format true fmt-none)
      (doseq [v results]
        (clj-format true fmt-var
                    (str (:name v)) (str (:file v)) (:line v))))
    (clj-format true fmt-count (count results))))

(defn- do-ns-deps [args]
  (let [db   (ensure-db)
        sym  (parse-sym (first args))
        deps (sort (xref/ns-deps db sym))]
    (if (empty? deps)
      (clj-format true fmt-none)
      (doseq [d deps]
        (clj-format true fmt-ns (str d))))
    (clj-format true fmt-count (count deps))))

(defn- do-ns-dependents [args]
  (let [db   (ensure-db)
        sym  (parse-sym (first args))
        deps (sort (xref/ns-dependents db sym))]
    (if (empty? deps)
      (clj-format true fmt-none)
      (doseq [d deps]
        (clj-format true fmt-ns (str d))))
    (clj-format true fmt-count (count deps))))

(defn- do-apropos [args]
  (let [db      (ensure-db)
        pattern (first args)
        results (xref/apropos db (re-pattern (or pattern "")))]
    (if (empty? results)
      (clj-format true fmt-none)
      (doseq [v results]
        (clj-format true fmt-var
                    (str (:name v)) (str (:file v)) (:line v))))
    (clj-format true fmt-count (count results))))

(defn- do-graph [args]
  (let [db    (ensure-db)
        sym   (parse-sym (first args))
        edges (sort (xref/call-graph db sym {:depth 3 :direction :outgoing}))]
    (if (empty? edges)
      (clj-format true fmt-none)
      (doseq [[from to] edges]
        (clj-format true fmt-edge (str from) (str to))))
    (clj-format true fmt-count (count edges))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dispatch                                                                   ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private commands
  {"init"             do-init
   "who-calls"        do-who-calls
   "calls-who"        do-calls-who
   "who-references"   do-who-references
   "who-implements"   do-who-implements
   "who-dispatches"   do-who-dispatches
   "who-macroexpands" do-who-macroexpands
   "unused"           do-unused
   "ns-deps"          do-ns-deps
   "ns-dependents"    do-ns-dependents
   "apropos"          do-apropos
   "graph"            do-graph})

(def ^:private fmt-usage
  ["Usage: clj -M:xref <command> [args]" :nl
   :nl
   "Commands:" :nl
   "  init                        generate/regenerate the xref database" :nl
   "  who-calls ns/fn             who calls this function?" :nl
   "  calls-who ns/fn             what does this function call?" :nl
   "  who-references ns/fn        all references to this var" :nl
   "  who-implements ns/Protocol  who implements this protocol?" :nl
   "  who-dispatches ns/multi     defmethod dispatch values" :nl
   "  who-macroexpands ns/macro   where is this macro expanded?" :nl
   "  unused                      find dead code" :nl
   "  ns-deps ns                  namespace dependencies" :nl
   "  ns-dependents ns            reverse namespace dependencies" :nl
   "  apropos pattern             search vars by name pattern" :nl
   "  graph ns/fn                 transitive call graph" :nl])

(defn -main [& args]
  (let [cmd  (first args)
        rest (rest args)]
    (if-let [handler (get commands cmd)]
      (handler rest)
      (clj-format true fmt-usage))))
