(ns flow-storm.plugins.serde.ui
  (:require [flow-storm.debugger.ui.plugins :as fs-plugins]
            [flow-storm.debugger.ui.components :as ui]
            [flow-storm.debugger.ui.utils :as ui-utils]
            [flow-storm.debugger.state :as dbg-state]
            [flow-storm.debugger.runtime-api :as runtime-api :refer [rt-api]]
            [clojure.string :as str])
  (:import [javafx.scene.control Label Button TextField]
           [javafx.scene.layout HBox VBox]
           [javafx.event EventHandler]
           [javafx.scene Node]
           [javafx.stage FileChooser FileChooser$ExtensionFilter]))

(defn- on-create [_]
  (try
    (let [box (ui/v-box :childs [])
          *save-flow-id (atom 0)
          *load-flow-id (atom 0)

          save-flow-cmb (ui/combo-box :items []
                                       :cell-factory (fn [_ flow-id] (ui/label :text (str flow-id)))
                                       :button-factory (fn [_ flow-id] (ui/label :text (str flow-id)))
                                       :on-change (fn [_ flow-id] (when flow-id (reset! *save-flow-id flow-id))))

          load-flow-cmb (ui/combo-box :items (into [] (range 10))
                                      :cell-factory (fn [_ flow-id] (ui/label :text (str flow-id)))
                                      :button-factory (fn [_ flow-id] (ui/label :text (str flow-id)))
                                      :on-change (fn [_ flow-id] (when flow-id (reset! *load-flow-id flow-id))))

          flow-clear (fn [flow-id]
                       )

          save-pane (ui/v-box :childs [(ui/label :text "Save")
                                       (ui/h-box :childs [(ui/label :text "Flow:")
                                                          save-flow-cmb
                                                          (ui/label :text "File:")
                                                          (ui/icon-button :icon-name  "mdi-content-save"
                                                                          :tooltip "Save flow"
                                                                          :on-click (fn []
                                                                                      (let [file-chooser (doto (FileChooser.)
                                                                                                           (.setTitle "Save flow"))
                                                                                            _ (-> file-chooser .getExtensionFilters (.addAll [(FileChooser$ExtensionFilter. "Edn Files" (into-array String ["*.edn"]))]))
                                                                                            selected-file (.showSaveDialog file-chooser (dbg-state/main-jfx-stage))
                                                                                            selected-file-path (when selected-file (.getAbsolutePath selected-file))]
                                                                                        (when selected-file-path
                                                                                          (runtime-api/call-by-fn-key rt-api :plugins.serde/serialize [selected-file-path @*save-flow-id])))))])])
          load-pane (ui/v-box :childs [(ui/label :text "Load")
                                       (ui/h-box :childs [(ui/label :text "Flow:")
                                                          load-flow-cmb
                                                          (ui/label :text "File:")
                                                          (ui/icon-button :icon-name  "mdi-folder-upload"
                                                                          :tooltip "Load flow"
                                                                          :on-click (fn []
                                                                                      (let [file-chooser (doto (FileChooser.)
                                                                                                           (.setTitle "Select a file"))
                                                                                            _ (-> file-chooser .getExtensionFilters (.addAll [(FileChooser$ExtensionFilter. "Edn Files" (into-array String ["*.edn"]))]))
                                                                                            selected-file (.showOpenDialog file-chooser (dbg-state/main-jfx-stage))
                                                                                            selected-file-path (when selected-file (.getAbsolutePath selected-file))]
                                                                                        (when selected-file-path
                                                                                          (runtime-api/call-by-fn-key rt-api :plugins.serde/replay-timelines [selected-file-path @*load-flow-id])))))])])
          main-pane (ui/v-box :childs [save-pane
                                       load-pane])]

      {:fx/node main-pane
       :save-flow-cmb save-flow-cmb
       :selected-save-flow-id-ref *save-flow-id
       :flow-clear flow-clear})
    (catch Exception e
      (.printStackTrace e)
      (ui/label :text (.getMessage e)))))


(defn- on-focus [{:keys [save-flow-cmb selected-save-flow-id-ref]}]
  (let [flow-ids (into #{} (map first (runtime-api/all-flows-threads rt-api)))]
    (ui-utils/combo-box-set-items save-flow-cmb flow-ids)
    (ui-utils/combo-box-set-selected save-flow-cmb @selected-save-flow-id-ref)))

(defn- on-flow-clear [flow-id {:keys [flow-clear]}]
  (flow-clear flow-id))

(fs-plugins/register-plugin
 :serde
 {:label "Serde"
  :css-resource       "flow-storm-serde-plugin/styles.css"
  :dark-css-resource  "flow-storm-serde-plugin/dark.css"
  :light-css-resource "flow-storm-serde-plugin/light.css"
  :on-focus on-focus
  :on-create on-create
  :on-flow-clear on-flow-clear })
