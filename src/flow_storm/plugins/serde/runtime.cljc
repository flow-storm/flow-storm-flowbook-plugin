(ns flow-storm.plugins.serde.runtime
  (:require [flow-storm.runtime.indexes.api :as ia]
            [flow-storm.runtime.debuggers-api :as dbg-api]
            [flow-storm.runtime.types.bind-trace :as fs-bind-trace]
            [flow-storm.runtime.indexes.protocols :as ip]
            [flow-storm.types :as types]
            [flow-storm.runtime.values :as rt-values]
            [clojure.java.io :as io]
            [clojure.edn :as edn])
  (:import [java.io Serializable File FileInputStream FileOutputStream ObjectInputStream NotSerializableException
            ObjectOutputStream OptionalDataException EOFException DataOutputStream]
           [clojure.storm Tracer]))

(defprotocol SerializeP
  (serialize-value [_]))

(extend-protocol SerializeP
  Object
  (serialize-value [v] v))

(defn- file-info [file-path]
  (let [file (io/file file-path)
        [_ fname fext] (re-find #"(.+)\.(.+)" (.getName file))]
    {:path (.getAbsolutePath file)
     :parent (.getParent file) 
     :name fname
     :ext fext}))

(defn- make-value-filepath [file-path]
  (let [{:keys [parent name]} (file-info file-path)
        file-name (str name "-values.ser")]
    {:file-path (str parent File/separator file-name)
     :file-name file-name} ))

(defn- make-timeline-filepath [file-path thread-id]
  (let [{:keys [parent name]} (file-info file-path)
        file-name (format  "%s-thread-%d-timeline.ser" name thread-id)]
    {:file-path (str parent File/separator file-name)
     :file-name file-name}))

(defn- make-forms-file-path [file-path]
  (let [{:keys [parent name]} (file-info file-path)
        file-name (str name "-forms.ser")]
    {:file-path (str parent File/separator file-name)
     :file-name file-name}))

(defn- reify-value-references [flow-id thread-ids]
  (let [timelines (mapv #(ia/get-timeline flow-id %) thread-ids)
        *values-ref-registry (atom (rt-values/make-empty-value-ref-registry))
        *forms-ids (atom #{})
        ref-value! (fn [v]
                     (let [vrefs @*values-ref-registry]
                       (swap! *values-ref-registry rt-values/add-val-ref v)
                       (:vid (rt-values/get-value-ref @*values-ref-registry v))))
        timelines-refs (reduce (fn [acc timeline]
                                 (let [timeline-refs
                                       (reduce (fn [tl-refs tl-entry]
                                                 (cond
                                                   (ia/fn-call-trace? tl-entry)
                                                   (do
                                                     (swap! *forms-ids conj (ia/get-form-id tl-entry))
                                                     (conj! tl-refs {:tl-entry tl-entry
                                                                     :args-ids (mapv ref-value! (ia/get-fn-args tl-entry))
                                                                     :bindings-vals-ids (mapv (comp ref-value! ia/get-bind-val) (ia/get-fn-bindings tl-entry))}))
                                                   
                                                   (or (ia/expr-trace? tl-entry)
                                                       (ia/fn-return-trace? tl-entry))                                
                                                   (conj! tl-refs {:tl-entry tl-entry
                                                                   :val-id (ref-value! (ia/get-expr-val tl-entry))})
                                                   
                                                   (ia/fn-unwind-trace? tl-entry)
                                                   (conj! tl-refs {:tl-entry tl-entry
                                                                   :throwable-id (ref-value! (ia/get-throwable tl-entry))})))
                                               (transient [])
                                               timeline)]
                                   (assoc acc timeline (persistent! timeline-refs))))            
                               {}
                               timelines)]
    {:timelines-refs timelines-refs
     :values (mapv first (sort-by (comp :vid second) (rt-values/all-val-ref-tuples @*values-ref-registry)))
     :forms-ids @*forms-ids}))

(comment

  (def vrs (reify-value-references 0 [32]))
 
  )

(defn serialize-values [file-path values]
  (with-open [output-stream (proxy [ObjectOutputStream] [(FileOutputStream. file-path)]  
                              (replaceObject [obj]
                                (if (and (instance? Serializable obj)
                                         (not (fn? obj)))
                                  obj
                                  (let [obj-name (.getName (class obj))]
                                    (println "Can't serialize value of type " obj-name ".")
                                    (reify Object
                                      Serializable
                                      Object
                                      (toString [_] (str "Placeholder " obj-name))))))
                              (toString []
                                (proxy-super enableReplaceObject true)
                                (proxy-super toString)))]
    (.toString output-stream)
    (doseq [v values]
      (let [v-ser (if v (serialize-value v) :flow-storm/nil)]
        (.writeObject ^ObjectOutputStream output-stream v-ser)))
    (.flush output-stream)))

(comment
  (def tl (ia/get-timeline 0 32))
  (ia/as-immutable (first tl))
  (ia/as-immutable (get tl 2))

  )

(defn serialize-timelines [timelines-refs main-file-path]
  (->> timelines-refs
       (mapv (fn [[timeline tl-refs]]
               (let [thread-id (ia/timeline-thread-id timeline 0)
                     thread-name (ia/timeline-thread-name timeline 0)
                     {:keys [file-path file-name]} (make-timeline-filepath main-file-path thread-id)]
                 (with-open [out-stream (ObjectOutputStream. (FileOutputStream. file-path))]
                   (doseq [{:keys [tl-entry] :as tl-ref} tl-refs]
                     (let [entry-data (cond
                                        (ia/fn-call-trace? tl-entry)
                                        {:type :fn-call
                                         :fn-ns (ia/get-fn-ns tl-entry)                                         
                                         :fn-name (ia/get-fn-name tl-entry)
                                         :form-id (ia/get-form-id tl-entry)
                                         :args-ids (:args-ids tl-ref)
                                         :bindings-vals (->> (ia/get-fn-bindings tl-entry)
                                                             (map-indexed (fn [b-idx b]
                                                                            (let [ib (ia/as-immutable b)]
                                                                              (-> ib
                                                                                  (dissoc :type :value)
                                                                                  (assoc :val-id (get (:bindings-vals-ids tl-ref) b-idx))))) )
                                                             (into []))}
                                        
                                        (ia/expr-trace? tl-entry)
                                        {:type :expr
                                         :coord (ia/get-coord tl-entry)
                                         :val-id (:val-id tl-ref)}
                                        
                                        (ia/fn-return-trace? tl-entry)                                
                                        {:type :fn-return
                                         :coord (ia/get-coord tl-entry)
                                         :val-id (:val-id tl-ref)}
                                        
                                        (ia/fn-unwind-trace? tl-entry)
                                        {:type :fn-unwind
                                         :coord (ia/get-coord tl-entry)
                                         :throwable-id (:throwable-id tl-ref)})]
                       (.writeObject out-stream entry-data)))
                   (.flush out-stream))
                 {:thread-id thread-id
                  :thread-name thread-name
                  :file file-name})))))

(defn- deserialize-objects [file-path]
  (with-open [input-stream (ObjectInputStream. (FileInputStream. file-path))]
    (loop [objects (transient [])]
      (if-let [o (try
                   (.readObject ^ObjectInputStream input-stream)
                   (catch OptionalDataException ode nil)
                   (catch EOFException eof nil))]
        (recur (conj! objects o))

        (persistent! objects)))))

(defn serialize-forms [forms-ids main-file-path]
  (let [{:keys [file-path file-name]} (make-forms-file-path main-file-path)]
    (with-open [out-stream (ObjectOutputStream. (FileOutputStream. file-path))]      
      (binding [*print-meta* true]
        (doseq [form-id forms-ids]
          (let [form-data (-> (ia/get-form form-id)
                              (update :form/form (fn [frm] (vary-meta frm dissoc :clojure.storm/emitted-coords))))]
            (.writeObject ^ObjectOutputStream out-stream form-data))))
      (.flush out-stream))
    file-name))

(defn serialize
  ([main-file-path flow-id] (serialize main-file-path flow-id nil))
  ([main-file-path flow-id thread-ids]  
   (let [thread-ids (or thread-ids (ia/all-threads-ids flow-id))
         {value-fpath :file-path value-fname :file-name} (make-value-filepath main-file-path)
         {:keys [timelines-refs values forms-ids]} (reify-value-references flow-id thread-ids)
         _ (serialize-values value-fpath values)
         timelines-data (serialize-timelines timelines-refs main-file-path)
         forms-file (serialize-forms forms-ids main-file-path)
         file-data {:values-file value-fname
                    :timelines-data timelines-data
                    :forms-file forms-file
                    :total-order-timeline-file nil ;; TODO
                    :bookmarks []}]
     (spit main-file-path (pr-str file-data)))))

(defn replay-timelines [main-file-path flow-id]
  (let [{:keys [values-file timelines-data forms-file]} (edn/read-string (slurp main-file-path))
        {:keys [parent]} (file-info main-file-path)
        values (deserialize-objects (str parent File/separator values-file))
        get-val (fn [val-id] (get values val-id))
        forms (deserialize-objects (str parent File/separator forms-file))]

    (doseq [{:form/keys [id ns form file line]} forms]      
      (Tracer/registerFormObject id ns file line form))
    
    (doseq [{:keys [thread-id thread-name file]} timelines-data]
      (let [tl-entries-maps (deserialize-objects (str parent File/separator file))]
        (doseq [entry-map tl-entries-maps]
          (case (:type entry-map)
            :fn-call   (let [args (mapv get-val (:args-ids entry-map))
                             tl-idx (ia/add-fn-call-trace flow-id
                                                          thread-id
                                                          thread-name
                                                          (:fn-ns entry-map)
                                                          (:fn-name entry-map)
                                                          (:form-id entry-map)
                                                          args
                                                          false)
                             fn-call (get (ia/get-timeline flow-id thread-id) tl-idx)]
                         (doseq [bmap (:bindings entry-map)]
                           (let [b (fs-bind-trace/make-bind-trace (:symbol bmap)
                                                                  (get-val (:val-id bmap))
                                                                  (:coord bmap)
                                                                  (:visible-after-idx bmap))]
                             (ip/add-binding fn-call b))))
            :fn-return (ia/add-fn-return-trace flow-id thread-id (:coord entry-map) (get-val (:val-id entry-map)) false)
            :fn-unwind (ia/add-fn-unwind-trace flow-id thread-id (:coord entry-map) (get-val (:throwable-id entry-map)) false)
            :expr (ia/add-expr-exec-trace flow-id thread-id (:coord entry-map) (get-val (:val-id entry-map)) false))))))
  )

(comment
  (serialize "/home/jmonetta/my-projects/flow-storm-serde-plugin/tmp/sum-ser.edn" 0)
  (replay-timelines "/home/jmonetta/my-projects/flow-storm-serde-plugin/tmp/sum-ser.edn" 3)

  (replay-timelines "/home/jmonetta/my-projects/flow-storm-testers/big-demo/serializations/web-ser.edn" 3)
  
  )
(dbg-api/register-api-function :plugins.serde/serialize serialize)
(dbg-api/register-api-function :plugins.serde/replay-timelines replay-timelines)


