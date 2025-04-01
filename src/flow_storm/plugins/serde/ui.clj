(ns flow-storm.plugins.serde.ui
  (:require [flow-storm.debugger.ui.plugins :as fs-plugins]
            [flow-storm.debugger.runtime-api :as runtime-api :refer [rt-api]]
            [clojure.string :as str])
  (:import [javafx.scene.control Label Button TextField]
           [javafx.scene.layout HBox VBox]
           [javafx.event EventHandler]
           [javafx.scene Node]))

(fs-plugins/register-plugin
 :serde
 {:label "Serde"
  :on-create (fn [_]
               {:fx/node (Label. "Serde")})})
