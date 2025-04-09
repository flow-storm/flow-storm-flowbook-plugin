(ns flow-storm.plugins.serde.ui
  (:require [flow-storm.debugger.ui.plugins :as fs-plugins]
            [flow-storm.debugger.ui.components :as ui]
            [flow-storm.debugger.state :as dbg-state]
            [flow-storm.debugger.runtime-api :as runtime-api :refer [rt-api]])
  (:import [javafx.stage FileChooser FileChooser$ExtensionFilter]))

(defn- on-create [_]
  (try
    (let [flow-clear (fn [_])

          save-pane (ui/v-box
                     :childs
                     [(ui/label :text "Save")
                      (ui/h-box :childs
                                [(ui/label :text "File:")
                                 (ui/icon-button :icon-name  "mdi-content-save"
                                                 :tooltip "Save flow"
                                                 :on-click (fn []
                                                             (let [file-chooser (doto (FileChooser.)
                                                                                  (.setTitle "Save flow"))
                                                                   _ (-> file-chooser .getExtensionFilters (.addAll [(FileChooser$ExtensionFilter. "Edn Files" (into-array String ["*.edn"]))]))
                                                                   selected-file (.showSaveDialog file-chooser (dbg-state/main-jfx-stage))
                                                                   selected-file-path (when selected-file (.getAbsolutePath selected-file))]
                                                               (when selected-file-path
                                                                 (runtime-api/call-by-fn-key rt-api :plugins.serde/serialize [selected-file-path #{}])))))])])
          load-pane (ui/v-box :childs [(ui/label :text "Load")
                                       (ui/h-box :childs [(ui/label :text "File:")
                                                          (ui/icon-button :icon-name  "mdi-folder-upload"
                                                                          :tooltip "Load flow"
                                                                          :on-click (fn []
                                                                                      (let [file-chooser (doto (FileChooser.)
                                                                                                           (.setTitle "Select a file"))
                                                                                            _ (-> file-chooser .getExtensionFilters (.addAll [(FileChooser$ExtensionFilter. "Edn Files" (into-array String ["*.edn"]))]))
                                                                                            selected-file (.showOpenDialog file-chooser (dbg-state/main-jfx-stage))
                                                                                            selected-file-path (when selected-file (.getAbsolutePath selected-file))]
                                                                                        (when selected-file-path
                                                                                          (runtime-api/call-by-fn-key rt-api :plugins.serde/replay-timelines [selected-file-path])))))])])
          main-pane (ui/v-box :childs [save-pane
                                       load-pane])]

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
