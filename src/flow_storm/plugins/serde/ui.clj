(ns flow-storm.plugins.serde.ui
  (:require [flow-storm.debugger.ui.plugins :as fs-plugins]
            [flow-storm.debugger.ui.components :as ui]
            [flow-storm.debugger.ui.utils :as ui-utils :refer [event-handler]]
            [flow-storm.debugger.state :as dbg-state]
            [flow-storm.debugger.runtime-api :as runtime-api :refer [rt-api]])
  (:import [javafx.stage FileChooser FileChooser$ExtensionFilter]
           [javafx.scene Scene]
           [javafx.stage Stage]))

(defn- create-notebook-window [notebook-web-view]
  (try
    (let [notebook-w 1000
          notebook-h 600
          stage (doto (Stage.)
                  (.setTitle "FlowStorm replay notebook"))
          scene (Scene. notebook-web-view
                        notebook-w
                        notebook-h)]

      (.setScene stage scene)

      (.setOnCloseRequest stage (event-handler [_] (dbg-state/unregister-jfx-stage! stage)))
      (dbg-state/register-jfx-stage! stage)

      (let [{:keys [x y]} (ui-utils/stage-center-box (dbg-state/main-jfx-stage) notebook-w notebook-h)]
        (.setX stage x)
        (.setY stage y))

      (-> stage .show))

    (catch Exception e
      (.printStackTrace e))))

(defn- on-create [_]
  (try
    (let [flow-clear (fn [_])

          main-pane (ui/v-box :childs [(ui/button :label "Save flows"
                                                  :on-click (fn []
                                                              (let [file-chooser (doto (FileChooser.)
                                                                                   (.setTitle "Save flow"))
                                                                    _ (-> file-chooser .getExtensionFilters (.addAll [(FileChooser$ExtensionFilter. "Edn Files" (into-array String ["*.edn"]))]))
                                                                    selected-file (.showSaveDialog file-chooser (dbg-state/main-jfx-stage))
                                                                    selected-file-path (when selected-file (.getAbsolutePath selected-file))]
                                                                (when selected-file-path
                                                                  (runtime-api/call-by-fn-key rt-api :plugins.serde/serialize [selected-file-path #{}])))))
                                       (ui/button :label "Load flows"
                                          :on-click (fn []
                                                      (let [file-chooser (doto (FileChooser.)
                                                                           (.setTitle "Select a file"))
                                                            _ (-> file-chooser .getExtensionFilters (.addAll [(FileChooser$ExtensionFilter. "Edn Files" (into-array String ["*.edn"]))]))
                                                            selected-file (.showOpenDialog file-chooser (dbg-state/main-jfx-stage))
                                                            selected-file-path (when selected-file (.getAbsolutePath selected-file))]
                                                        (when selected-file-path
                                                          (runtime-api/call-by-fn-key rt-api :plugins.serde/replay-timelines [selected-file-path])))))
                                       (ui/button :label "Load notebook"
                                          :on-click (fn []
                                                      (let [{:keys [web-view set-html set-handlers]} (ui/web-view)]
                                                        (set-html "<html><body><button onclick='test.invoke(42,44)'>Click me</button></body></html>")
                                                        (set-handlers {"test" (fn [arg1 arg2] (println "@@@@ we got it" arg1 arg2))})
                                                        (create-notebook-window web-view))))
                                       ]
                              :spacing 10)]
      {:fx/node main-pane
       :flow-clear flow-clear})
    (catch Exception e
      (.printStackTrace e)
      (ui/label :text (.getMessage e)))))


(defn- on-focus [_])

(defn- on-flow-clear [_ _])

(fs-plugins/register-plugin
 :serde
 {:label "Serde"
  :css-resource       "flow-storm-serde-plugin/styles.css"
  :dark-css-resource  "flow-storm-serde-plugin/dark.css"
  :light-css-resource "flow-storm-serde-plugin/light.css"
  :on-focus on-focus
  :on-create on-create
  :on-flow-clear on-flow-clear })
