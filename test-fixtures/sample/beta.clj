(ns sample.beta
  (:require [sample.alpha :as alpha]))

(defn welcome [person]
  (alpha/greet (:name person)))

(defn process [people]
  (map alpha/greet (map :name people)))

(defn formal-welcome [person]
  (alpha/with-greeting :formal
    (alpha/greet (:name person))))
