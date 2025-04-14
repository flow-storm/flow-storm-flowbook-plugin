(ns flow-storm.plugins.flowbook.utils)

(defn primitives-array? [x]
  (if (nil? x)
    false
    (when-let [ct (-> x class .getComponentType)]
      (not= (.getName ct) "java.lang.Object"))))
