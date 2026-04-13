(ns clj-xref.analyze
  "Transform clj-kondo analysis data into the clj-xref data model."
  (:require [clj-kondo.core :as kondo])
  (:import [java.time Instant]))

(defn- qualify-symbol
  "Build a namespace-qualified symbol from ns and name."
  [ns-sym name-sym]
  (when (and ns-sym name-sym)
    (symbol (str ns-sym) (str name-sym))))

(defn- classify-kind
  "Classify a clj-kondo var-usage into an xref kind."
  [var-usage]
  (cond
    (:defmethod var-usage) :dispatch
    (:macro var-usage)     :macroexpand
    (:arity var-usage)     :call
    :else                  :reference))

(defn- transform-var-def
  "Transform a clj-kondo :var-definitions entry into a var-info map."
  [vd]
  (cond-> {:name       (qualify-symbol (:ns vd) (:name vd))
           :ns         (:ns vd)
           :local-name (:name vd)
           :file       (:filename vd)
           :line       (:row vd)
           :col        (:col vd)}
    (:end-row vd)           (assoc :end-line (:end-row vd))
    (:end-col vd)           (assoc :end-col (:end-col vd))
    (:fixed-arities vd)     (assoc :fixed-arities (:fixed-arities vd))
    (:varargs-min-arity vd) (assoc :varargs-min-arity (:varargs-min-arity vd))
    (:private vd)           (assoc :private? true)
    (:macro vd)             (assoc :macro? true)
    (:defined-by vd)        (assoc :defined-by (:defined-by vd))
    (:doc vd)               (assoc :doc (:doc vd))
    (:protocol-ns vd)       (assoc :protocol {:ns   (:protocol-ns vd)
                                              :name (:protocol-name vd)})))

(defn- transform-var-usage
  "Transform a clj-kondo :var-usages entry into an xref-entry map."
  [vu]
  (let [from-sym (if (:from-var vu)
                   (qualify-symbol (:from vu) (:from-var vu))
                   (qualify-symbol (:from vu) '<top-level>))]
    (cond-> {:kind (classify-kind vu)
             :from from-sym
             :to   (qualify-symbol (:to vu) (:name vu))
             :file (:filename vu)
             :line (:row vu)
             :col  (:col vu)}
      (:end-row vu)   (assoc :end-line (:end-row vu))
      (:end-col vu)   (assoc :end-col (:end-col vu))
      (:arity vu)     (assoc :arity (:arity vu))
      (:defmethod vu) (assoc :dispatch-val (:dispatch-val-str vu)))))

(defn- infer-impl-type
  "Infer the implementing type name for a protocol-impl entry by finding
   the enclosing defrecord/deftype var-definition at the same file location."
  [var-defs-raw pi]
  (let [filename (:filename pi)
        row      (:row pi)]
    (->> var-defs-raw
         (filter (fn [vd]
                   (and (= filename (:filename vd))
                        (<= (:row vd) row)
                        (:end-row vd)
                        (>= (:end-row vd) row)
                        (contains? #{'clojure.core/defrecord
                                     'clojure.core/deftype}
                                   (:defined-by vd))
                        (let [n (str (:name vd))]
                          (not (or (.startsWith n "->")
                                   (.startsWith n "map->")))))))
         first
         :name)))

(defn- transform-protocol-impl
  "Transform a clj-kondo :protocol-impls entry into an xref-entry map.
   Uses var-defs-raw to infer the implementing type for defrecord/deftype."
  [pi var-defs-raw]
  (let [impl-type (infer-impl-type var-defs-raw pi)
        from-sym  (qualify-symbol (:impl-ns pi)
                                  (or impl-type (:method-name pi)))]
    (cond-> {:kind       :implement
             :from       from-sym
             :to         (qualify-symbol (:protocol-ns pi) (:protocol-name pi))
             :file       (:filename pi)
             :line       (:row pi)
             :col        (:col pi)
             :method     (:method-name pi)}
      (:end-row pi)    (assoc :end-line (:end-row pi))
      (:end-col pi)    (assoc :end-col (:end-col pi))
      (:defined-by pi) (assoc :defined-by (:defined-by pi)))))

(defn- transform-ns-def
  "Transform a clj-kondo :namespace-definitions entry into an ns-info map."
  [nd]
  (cond-> {:name (:name nd)
           :file (:filename nd)
           :line (:row nd)
           :col  (:col nd)}
    (:doc nd) (assoc :doc (:doc nd))))

(defn transform-analysis
  "Transform raw clj-kondo analysis data into the clj-xref data model.
   Returns a flat (unindexed) xref database map suitable for writing to EDN."
  [analysis {:keys [paths project]}]
  (let [var-defs-raw (:var-definitions analysis)
        var-defs   (mapv transform-var-def var-defs-raw)
        var-refs   (mapv transform-var-usage (:var-usages analysis))
        proto-refs (mapv #(transform-protocol-impl % var-defs-raw)
                         (:protocol-impls analysis))
        ns-defs    (mapv transform-ns-def (:namespace-definitions analysis))]
    (cond-> {:version    1
             :generated  (str (Instant/now))
             :paths      (vec paths)
             :namespaces ns-defs
             :vars       var-defs
             :refs       (into var-refs proto-refs)}
      project (assoc :project project))))

(defn- build-kondo-config
  "Build the clj-kondo config, deep-merging the :analysis map so caller
   additions don't clobber built-in defaults like :protocol-impls."
  [kondo-config]
  (let [base-analysis {:protocol-impls true :arglists true}
        user-analysis (:analysis kondo-config)
        merged-analysis (if (map? user-analysis)
                          (merge base-analysis user-analysis)
                          base-analysis)]
    (assoc (dissoc kondo-config :analysis)
           :analysis merged-analysis)))

(defn analyze
  "Run clj-kondo analysis on `paths` and return a flat xref database map.
   Options:
     :project      - project name string
     :kondo-config - extra config map merged into clj-kondo config"
  [paths & [{:keys [project kondo-config]}]]
  (let [result (kondo/run! {:lint   paths
                            :config (build-kondo-config kondo-config)})
        errors (get-in result [:summary :error] 0)]
    (when (pos? errors)
      (binding [*out* *err*]
        (println (str "clj-xref: warning: clj-kondo reported " errors " error(s). "
                      "The xref database may be incomplete."))))
    (transform-analysis (:analysis result) {:paths paths :project project})))
