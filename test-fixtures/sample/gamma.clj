(ns sample.gamma)

(defprotocol Renderable
  (render [this])
  (render-html [this]))

(defrecord TextWidget [content]
  Renderable
  (render [this] (:content this))
  (render-html [this] (str "<p>" (:content this) "</p>")))

(defmulti process-event :type)

(defmethod process-event :click [event]
  (println "Clicked:" (:target event)))

(defmethod process-event :keypress [event]
  (println "Key:" (:key event)))
