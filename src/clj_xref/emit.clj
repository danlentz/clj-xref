(ns clj-xref.emit
  "Read and write xref database EDN files."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [java.nio.file Files StandardCopyOption]))

(def ^:private key-order
  "Top-level keys in the order they should appear in the EDN file."
  [:version :generated :project :paths :namespaces :vars :refs :protocols :keywords])

(defn- write-edn-to-writer
  "Write the EDN content to a writer."
  [db w]
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
    (println "}")))

(defn write-edn
  "Write an xref database to an EDN file at `path`.
   Creates parent directories if needed. Writes to a temp file first,
   then atomically renames to prevent corruption on interrupted writes."
  [db path]
  (let [target (io/file path)
        dir    (.getParentFile target)]
    (when dir (.mkdirs dir))
    (let [tmp (java.io.File/createTempFile ".xref-" ".edn.tmp"
                                           (or dir (io/file ".")))]
      (try
        (with-open [w (io/writer tmp)]
          (write-edn-to-writer db w))
        (Files/move (.toPath tmp) (.toPath target)
                    (into-array [StandardCopyOption/REPLACE_EXISTING
                                 StandardCopyOption/ATOMIC_MOVE]))
        (catch Exception e
          (.delete tmp)
          (throw e))))))

(defn read-edn
  "Read an xref database from an EDN file at `path`."
  [path]
  (with-open [r (java.io.PushbackReader. (io/reader (io/file path)))]
    (edn/read r)))
