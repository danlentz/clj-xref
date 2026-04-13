(ns clj-xref.emit
  "Read and write xref database EDN files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private key-order
  "Top-level keys in the order they should appear in the EDN file."
  [:version :generated :project :paths :namespaces :vars :refs :protocols :keywords])

(defn write-edn
  "Write an xref database to an EDN file at `path`.
   Creates parent directories if needed. Produces one entry per line
   within vectors for readability."
  [db path]
  (let [f (io/file path)]
    (io/make-parents f)
    (with-open [w (io/writer f)]
      (binding [*out* w
                *print-length* nil
                *print-level* nil]
        (println "{")
        (doseq [k key-order
                :when (contains? db k)
                :let [v (get db k)]]
          (print (str " " (pr-str k) " "))
          (if (and (sequential? v) (pos? (count v)))
            (do
              (println "[")
              (doseq [entry v]
                (print "  ")
                (prn entry))
              (println " ]"))
            (prn v)))
        (println "}")))))

(defn read-edn
  "Read an xref database from an EDN file at `path`."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader (io/file path)))]
    (edn/read r)))
