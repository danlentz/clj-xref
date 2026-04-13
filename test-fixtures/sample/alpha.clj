(ns sample.alpha)

(defn greet [name]
  (str "Hello, " name))

(defn farewell [name]
  (str "Goodbye, " name))

(def ^:dynamic *greeting-style* :formal)

(defmacro with-greeting [style & body]
  `(binding [*greeting-style* ~style]
     ~@body))
