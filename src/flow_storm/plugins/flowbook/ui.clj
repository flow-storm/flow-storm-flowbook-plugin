(ns flow-storm.plugins.flowbook.ui
  (:require [flow-storm.debugger.ui.plugins :as fs-plugins]
            [flow-storm.debugger.ui.components :as ui]
            [flow-storm.debugger.ui.flows.screen :as flows-screen]
            [flow-storm.debugger.ui.utils :as ui-utils :refer [event-handler]]
            [flow-storm.debugger.state :as dbg-state]
            [flow-storm.debugger.runtime-api :as runtime-api :refer [rt-api]])
  (:import [javafx.stage FileChooser FileChooser$ExtensionFilter]
           [javafx.scene Scene]
           [javafx.stage Stage]))

(defn- create-flowbook-window [flowbook-file-path]
  (try
    (let [{:keys [web-view set-html set-handlers]} (ui/web-view)
          notebook-w 1000
          notebook-h 600
          refresh-flowbook (fn []
                             (set-html (slurp flowbook-file-path))
                             (set-handlers {"gotoLocation" (fn [flow-id thread-id idx]
                                                             (flows-screen/goto-location {:flow-id flow-id
                                                                                          :thread-id thread-id
                                                                                          :idx idx}))}))
          refresh-btn (ui/icon-button
                       :icon-name "mdi-reload"
                       :on-click refresh-flowbook
                       :tooltip "Reload flowbook")
          stage (doto (Stage.)
                  (.setTitle "FlowStorm replay notebook"))
          scene (Scene. (ui/border-pane
                         :top refresh-btn
                         :center web-view)
                        notebook-w
                        notebook-h)]

      (.setScene stage scene)

      (.setOnCloseRequest stage (event-handler [_] (dbg-state/unregister-jfx-stage! stage)))
      (dbg-state/register-jfx-stage! stage)

      (let [{:keys [x y]} (ui-utils/stage-center-box (dbg-state/main-jfx-stage) notebook-w notebook-h)]
        (.setX stage x)
        (.setY stage y))

      (-> stage .show)
      (refresh-flowbook))

    (catch Exception e
      (.printStackTrace e))))

(defn create-main-pane []
  (let [*loaded-flowbook-path (atom nil)
        open-flowbook-btn (ui/icon-button :icon-name  "mdi-book-open-page-variant"
                                          :tooltip "Show loaded flowbook"
                                          :disable true
                                          :on-click (fn []
                                                      (create-flowbook-window @*loaded-flowbook-path)))
        flow-clear (fn [_]
                     (reset! *loaded-flowbook-path nil)
                     (ui-utils/set-disable open-flowbook-btn true))
        main-pane (ui/v-box
                   :spacing 20
                   :align :center
                   :childs [(ui/label :text "Once you have recordings into some flows, use the `Save flowbook` button below to save them.")
                            (ui/label :text "A *-flowbook.html file will be created automatically with anchors to any bookmarks you have defined.")
                            (ui/label :text  "Edit this file with your prefered editor.")
                            (ui/label :text " You can call the javascript function gotoLocation.invoke(flow-id,thread-id,idx) to make the stepper jump to the location.")
                            (ui/button
                             :label "Save flowbook"
                             :on-click (fn []
                                         (let [file-chooser (doto (FileChooser.)
                                                              (.setTitle "Save flow"))
                                               _ (-> file-chooser .getExtensionFilters (.addAll [(FileChooser$ExtensionFilter. "Edn Files" (into-array String ["*.edn"]))]))
                                               selected-file (.showSaveDialog file-chooser (dbg-state/main-jfx-stage))
                                               selected-file-path (when selected-file (.getAbsolutePath selected-file))]
                                           (when selected-file-path
                                             (runtime-api/call-by-fn-key rt-api :plugins.flowbook/store-flowbook [selected-file-path (dbg-state/all-bookmarks) #{}])))))
                            (ui/label :text "Use the `Load flowbook` button below to load a previously stored flowbook.")
                            (ui/button
                             :label "Load flowbook"
                             :on-click (fn []
                                         (let [file-chooser (doto (FileChooser.)
                                                              (.setTitle "Select a file"))
                                               _ (-> file-chooser .getExtensionFilters (.addAll [(FileChooser$ExtensionFilter. "Edn Files" (into-array String ["*.edn"]))]))
                                               selected-file (.showOpenDialog file-chooser (dbg-state/main-jfx-stage))
                                               selected-file-path (when selected-file (.getAbsolutePath selected-file))]
                                           (when selected-file-path
                                             (let [{:keys [flowbook-path]} (runtime-api/call-by-fn-key rt-api :plugins.flowbook/load-flowbook [selected-file-path])]
                                               (ui-utils/set-disable open-flowbook-btn false)
                                               (reset! *loaded-flowbook-path flowbook-path))))))
                            (ui/label :text "Once a flowbook is loaded, use the button below will open it in a new window.")
                            open-flowbook-btn])]
      {:main-pane main-pane
       :flow-clear flow-clear}))

(defn- on-create [_]
  (let [{:keys [flow-clear main-pane]} (create-main-pane)]
    {:fx/node main-pane
     :flow-clear flow-clear}))

(defn- on-focus [_])

(defn- on-flow-clear [_ _])

(fs-plugins/register-plugin
 :flowbook
 {:label "Flowbook"
  :css-resource       "flow-storm-flowbook-plugin/styles.css"
  :dark-css-resource  "flow-storm-flowbook-plugin/dark.css"
  :light-css-resource "flow-storm-flowbook-plugin/light.css"
  :on-focus on-focus
  :on-create on-create
  :on-flow-clear on-flow-clear })
