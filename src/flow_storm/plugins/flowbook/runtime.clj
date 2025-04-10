(ns flow-storm.plugins.flowbook.runtime
  (:require [flow-storm.runtime.indexes.api :as ia]
            [flow-storm.runtime.debuggers-api :as dbg-api]
            [flow-storm.runtime.types.bind-trace :as fs-bind-trace]
            [flow-storm.runtime.indexes.protocols :as ip]
            [flow-storm.runtime.values :as rt-values]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.core.protocols :refer [Datafiable]]
            [clojure.datafy :refer [datafy]]
            [clojure+.walk :refer [prewalk]])
  (:import [java.io Serializable File FileInputStream FileOutputStream
            ObjectOutputStream ObjectInputStream OptionalDataException EOFException]
           [java.util HashMap]
           [clojure.storm Tracer]))

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

(defn- make-timeline-filepath [file-path flow-id thread-id]
  (let [{:keys [parent name]} (file-info file-path)
        file-name (format  "%s-flow-%d-thread-%d-timeline.ser" name flow-id thread-id)]
    {:file-path (str parent File/separator file-name)
     :file-name file-name}))

(defn- make-total-order-timeline-filepath [file-path flow-id]
  (let [{:keys [parent name]} (file-info file-path)
        file-name (format  "%s-flow-%d-total-order-timeline.ser" name flow-id)]
    {:file-path (str parent File/separator file-name)
     :file-name file-name}))

(defn- make-forms-file-path [file-path]
  (let [{:keys [parent name]} (file-info file-path)
        file-name (str name "-forms.ser")]
    {:file-path (str parent File/separator file-name)
     :file-name file-name}))

(defn- reify-timeline-value-references! [*values-registry *forms-ids timeline]
  (let [ref-value! (fn [v]
                     (swap! *values-registry rt-values/add-val-ref v)
                     (:vid (rt-values/get-value-ref @*values-registry v)))]
    (->> timeline
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
                 (transient []))
         persistent!)))

(comment

  (def vrs (reify-value-references 0 [32]))

  )

(defn- class-name [obj]
  (when obj
    (.getName (class obj))))

(def not-counted-limit 1000)

(defn- ensure-serializable [*cache visited obj]
  (try
    (let [dobj (datafy obj)]

      ;; for non countables we can't use the cache because hashCode could run forever
      (if (and (seq? dobj) (not (counted? dobj)) (= not-counted-limit (bounded-count not-counted-limit dobj)))

        {:uncountable-obj/class-name (class-name dobj)
         :uncountable-obj/head (mapv #(ensure-serializable *cache visited %) (take not-counted-limit dobj))}

        (if (.containsKey ^HashMap *cache obj)
          (.get ^HashMap *cache obj)
          (let [ser (prewalk (fn [o]
                               (try
                                 (when-not (visited o) ;; this is just to block cycles on the recursion we have on meta walk
                                   (let [ser (when-let [datafied-v (datafy o)]
                                               (if (and (instance? Serializable datafied-v)
                                                        (not (fn? datafied-v))
                                                        (not (class? o))
                                                        (not (instance? clojure.lang.Namespace o))) ;; var datafy does reflection and returns a cyclic graph

                                                 (let [datafied-v-ser-meta (if (meta datafied-v)
                                                                             (vary-meta datafied-v #(ensure-serializable *cache (conj visited o) %))
                                                                             datafied-v)]
                                                   datafied-v-ser-meta)

                                                 {:unserializable-obj/class-name (class-name o)}))]
                                     ser))
                                 (catch Exception _
                                   (let [ser {:unserializable-obj/class-name (class-name o)}]
                                     ser))))
                             obj)]
            (.put *cache obj ser)
            ser))))
    (catch Exception _
      (let [ser {:unserializable-obj/class-name (class-name obj)}]
        ser))))

(extend-protocol Datafiable
  clojure.lang.ArrayChunk
  (datafy [^ArrayChunk ac]
    (persistent!
     (.reduce ac (fn [acc o] (conj! acc o))
              (transient [])))))

(extend-protocol Datafiable
  java.util.LinkedList
  (datafy [^java.util.LinkedList ll]
    (seq ll)))

(defn- serialize-values [file-path values]
  (with-open [output-stream (ObjectOutputStream. (FileOutputStream. file-path))]
    (let [*cache (HashMap.)]
      (doseq [v values]
        (let [v-data (ensure-serializable *cache #{} v)]
          (.writeObject ^ObjectOutputStream output-stream v-data))))
    (.flush output-stream)))


(defn- deserialize-objects [file-path]
  (with-open [input-stream (ObjectInputStream. (FileInputStream. file-path))]
    (let [values (loop [objects (transient [])]
                   (let [o (try
                             (.readObject ^ObjectInputStream input-stream)
                             (catch OptionalDataException _ ::deserialize-end)
                             (catch EOFException _ ::deserialize-end))]
                     (if (= o ::deserialize-end)
                       (persistent! objects)

                       (recur (conj! objects o)))))]
      values)))

(comment
  (serialize-values "/home/jmonetta/my-projects/flow-storm-testers/big-demo/test.ser" [1 (range) 3])
  (deserialize-objects "/home/jmonetta/my-projects/flow-storm-testers/big-demo/test.ser")
  (def tl (ia/get-timeline 0 32))
  (ia/as-immutable (first tl))
  (ia/as-immutable (get tl 2))

  )

(defn serialize-timeline! [flow-id thread-id tl-refs main-file-path]
  (let [timeline (ia/get-timeline flow-id thread-id)
        thread-id (ia/timeline-thread-id timeline 0)
        thread-name (ia/timeline-thread-name timeline 0)
        {:keys [file-path file-name]} (make-timeline-filepath main-file-path flow-id thread-id)]
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
                                                                     (assoc :val-id (get (:bindings-vals-ids tl-ref) b-idx))
                                                                     (assoc :coord (ip/get-coord-raw b))))) )
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
     :file file-name}))

(defn- serialize-total-order-timeline! [flow-id to-timeline main-file-path]
  (let [{:keys [file-path file-name]} (make-total-order-timeline-filepath main-file-path flow-id)]
    (with-open [out-stream (ObjectOutputStream. (FileOutputStream. file-path))]
      (doseq [tot-entry to-timeline]
        (let [entry-data {:thread-id (ia/tote-thread-id tot-entry)
                          :idx (ia/tote-timeline-idx tot-entry)}]
          (.writeObject out-stream entry-data)))
      (.flush out-stream))
    file-name))

(defn- serialize-flows [flows main-file-path]
  (let [flows (reduce-kv (fn [acc [flow-id thread-id] tl-refs]
                          (assoc-in acc [flow-id :thread-timelines thread-id] (serialize-timeline! flow-id thread-id tl-refs main-file-path)))
                        {}
                        flows)
        flows-with-tot (reduce (fn [flows' flow-id]
                                 (if-let [to-tl (ia/total-order-timeline flow-id)]
                                   (assoc-in flows' [flow-id :total-order-timeline] (serialize-total-order-timeline! flow-id to-tl main-file-path))
                                   flows'))
                        flows
                        (keys flows))]
    flows-with-tot))

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

(defn reify-flows-timelines []
  (let [all-flows-threads (ia/all-threads)
        *forms-ids (atom #{})
        *values-registry (atom (rt-values/make-empty-value-ref-registry))
        flows (reduce (fn [acc [flow-id thread-id]]
                        (let [timeline (ia/get-timeline flow-id thread-id)
                              timeline-refs (reify-timeline-value-references! *values-registry *forms-ids timeline)]
                          (assoc acc [flow-id thread-id] timeline-refs)))
                      {}
                      all-flows-threads)
        values (mapv first (sort-by (comp :vid second) (rt-values/all-val-ref-tuples @*values-registry)))]
    {:flows flows
     :values values
     :forms-ids @*forms-ids}))

(defn serialize [main-file-path _skip-flows-threads-set]
  (let [{value-fpath :file-path value-fname :file-name} (make-value-filepath main-file-path)
        {:keys [flows values forms-ids]} (reify-flows-timelines)
        _ (serialize-values value-fpath values)
        flows-data (serialize-flows flows main-file-path)
        forms-file (serialize-forms forms-ids main-file-path)
        file-data {:version "1"
                   :flows flows-data
                   :values-file value-fname
                   :forms-file forms-file
                   :bookmarks []}]
    (spit main-file-path (pr-str file-data))))

(defn replay-timelines-v1 [main-file-path {:keys [values-file flows forms-file]}]
  (let [{:keys [parent]} (file-info main-file-path)
        values (deserialize-objects (str parent File/separator values-file))

        get-val (fn [val-id] (get values val-id))
        forms (deserialize-objects (str parent File/separator forms-file))
        replay-entry (fn [flow-id thread-id thread-name entry-map total-order?]
                       (case (:type entry-map)
                         :fn-call   (let [args (mapv get-val (:args-ids entry-map))
                                          tl-idx (ia/add-fn-call-trace flow-id
                                                                       thread-id
                                                                       thread-name
                                                                       (:fn-ns entry-map)
                                                                       (:fn-name entry-map)
                                                                       (:form-id entry-map)
                                                                       args
                                                                       total-order?)
                                          fn-call (get (ia/get-timeline flow-id thread-id) tl-idx)]

                                      (doseq [bmap (:bindings-vals entry-map)]
                                        (let [b (fs-bind-trace/make-bind-trace (:symbol bmap)
                                                                               (get-val (:val-id bmap))
                                                                               (:coord bmap)
                                                                               (:visible-after bmap))]
                                          (ip/add-binding fn-call b))))
                         :fn-return (ia/add-fn-return-trace flow-id thread-id (:coord entry-map) (get-val (:val-id entry-map)) total-order?)
                         :fn-unwind (ia/add-fn-unwind-trace flow-id thread-id (:coord entry-map) (get-val (:throwable-id entry-map)) total-order?)
                         :expr (ia/add-expr-exec-trace flow-id thread-id (:coord entry-map) (get-val (:val-id entry-map)) total-order?)))]

    (doseq [{:form/keys [id ns form file line]} forms]
      (Tracer/registerFormObject id ns file line form))

    (doseq [[flow-id {:keys [thread-timelines total-order-timeline]}] flows]
      (if total-order-timeline
        ;; if there is a total-order-timeline we need to load all of them on memory
        (let [tot-entries-maps (deserialize-objects (str parent File/separator total-order-timeline))
              {:keys [deser-timelines thread-names]}
              (reduce-kv (fn [acc thread-id {:keys [thread-name file]}]
                           (let [tl-entries-maps (deserialize-objects (str parent File/separator file))]
                             (-> acc
                                 (assoc-in [:deser-timelines thread-id] tl-entries-maps)
                                 (assoc-in [:thread-names thread-id] thread-name))))
                         {:deser-timelines {}
                          :thread-names {}}
                         thread-timelines)]
          (doseq [{:keys [thread-id idx]} tot-entries-maps]
            (let [entry-map (get-in deser-timelines [thread-id idx])]
              (replay-entry flow-id thread-id (thread-names thread-id) entry-map true))))

        (doseq [[thread-id {:keys [thread-name file]}] thread-timelines]
          (let [tl-entries-maps (deserialize-objects (str parent File/separator file))]
            (doseq [entry-map tl-entries-maps]
              (replay-entry flow-id thread-id thread-name entry-map false))))))))

(defn replay-timelines [main-file-path]
  (let [{:keys [version] :as file-data} (edn/read-string (slurp main-file-path))]
    (case version
      "1" (replay-timelines-v1 main-file-path file-data)
      (throw (ex-info "Unsupported version" file-data)))))

(dbg-api/register-api-function :plugins.flowbook/serialize serialize)
(dbg-api/register-api-function :plugins.flowbook/replay-timelines replay-timelines)
