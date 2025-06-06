(ns flow-storm.plugins.flowbook.runtime
  (:require [flow-storm.runtime.indexes.api :as ia]
            [flow-storm.runtime.debuggers-api :as dbg-api]
            [flow-storm.plugins.flowbook.utils :as u]
            [flow-storm.runtime.types.bind-trace :as fs-bind-trace]
            [flow-storm.runtime.indexes.protocols :as ip]
            [flow-storm.runtime.events :as rt-events]
            [flow-storm.runtime.values :as rt-values]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure+.walk :refer [prewalk]]
            [clojure.string :as str])
  (:import [java.io File FileInputStream FileOutputStream
            ObjectOutputStream ObjectInputStream OptionalDataException EOFException]
           [java.util HashMap]
           [clojure.storm Tracer]))

(defprotocol Datafiable
  :extend-via-metadata true

  (datafy [o] "return a representation of o as data (default identity)"))

(extend-protocol Datafiable
  nil
  (datafy [_] nil)

  Object
  (datafy [x] x)

  clojure.lang.ArrayChunk
  (datafy [^ArrayChunk ac]
    (persistent!
     (.reduce ac (fn [acc o] (conj! acc o))
              (transient []))))

  java.util.LinkedList
  (datafy [^java.util.LinkedList ll]
    (seq ll))

  java.util.HashMap
  (datafy [^java.util.HashMap hm]
    (into {} hm))

  clojure.lang.APersistentMap$KeySeq
  (datafy [^clojure.lang.APersistentMap$KeySeq ks]
    (map identity ks))

  )

(defn datafy-it [x]
  (let [v (datafy x)]
    (if (identical? v x)
      v
      (if (instance? clojure.lang.IObj v)
        (vary-meta v assoc ::class (-> x class .getName symbol))
        v))))

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

(defn- make-flowbook-filepath [file-path]
  (let [{:keys [parent name]} (file-info file-path)
        file-name (format  "%s-flowbook.html" name)]
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

(defn- class-name [obj]
  (when obj
    (.getName (class obj))))

(def not-counted-limit 1000)

(defn- clojure-data? [obj]
  (or (nil? obj)
      (char? obj)
      (number? obj)
      (boolean? obj)
      (symbol? obj)
      (string? obj)
      (keyword? obj)
      (and (coll? obj)
           ;; if we are looking at collections let's make sure
           ;; it is a clojure one and not some other object that implemented
           ;; the interfaces
           (-> obj class .getName (str/starts-with? "clojure")))))

(defn- add-unserializable! [*unserializable-classes obj]
  (let [cn (class-name obj)]
    (when-not (fn? obj) (swap! *unserializable-classes conj cn)) ;; do not report functions
    {::unserializable-obj-class-name cn
     ::unserializable-obj-id (System/identityHashCode obj)}))

(defn- ensure-serializable [*unserializable-classes *cache visited obj]
  (try
    (let [dobj (datafy-it obj)]

      (cond

        ;; for non countables we can't use the cache because hashCode could run forever
        (and (seq? dobj) (not (counted? dobj)) (= not-counted-limit (bounded-count not-counted-limit dobj)))
        (do
          (swap! *unserializable-classes conj (class-name dobj))
          {::uncountable-obj-class-name (class-name dobj)
           ::uncountable-obj-id (System/identityHashCode dobj)
           ::uncountable-obj-head (mapv #(ensure-serializable *unserializable-classes *cache visited %) (take not-counted-limit dobj))})

        (instance? clojure.lang.LazySeq dobj)
        {::lazy-seq-head (mapv #(ensure-serializable *unserializable-classes *cache visited %) dobj)}

        :else
        (if (.containsKey ^HashMap *cache obj)
          (.get ^HashMap *cache obj)
          (let [ser (prewalk (fn [o]
                               (try
                                 (if (contains? visited o) ;; this is just to block cycles on the recursion we have on meta walk
                                   ::cycle-detected
                                   (let [ser (let [datafied-v (datafy-it o)]
                                               (cond

                                                 (clojure-data? datafied-v)
                                                 (let [datafied-v-ser-meta (if (meta datafied-v)
                                                                             (vary-meta datafied-v #(ensure-serializable *unserializable-classes *cache (conj visited o) %))
                                                                             datafied-v)]
                                                   datafied-v-ser-meta)

                                                 (u/primitives-array? datafied-v)

                                                 {::primitive-array-type (-> datafied-v class .getComponentType .getName)
                                                  ::primitive-array-data (into [] datafied-v)}

                                                 :else
                                                 (add-unserializable! *unserializable-classes datafied-v)))]
                                     ser))
                                 (catch Exception _
                                   (add-unserializable! *unserializable-classes o))))
                             obj)]
            (.put *cache obj ser)
            ser))))
    (catch Exception _
      (add-unserializable! *unserializable-classes obj))))

(defn- serialize-values [*unserializable-classes file-path values]
  (with-open [output-stream (ObjectOutputStream. (FileOutputStream. file-path))]
    (let [*cache (HashMap.)]
      (doseq [v values]
        (let [v-data (ensure-serializable *unserializable-classes *cache #{} v)]
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

(defn- serialize-timeline! [flow-id thread-id tl-refs main-file-path]
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
                                 (let [to-tl (ia/total-order-timeline flow-id)]
                                   (if (pos? (count to-tl))
                                     (assoc-in flows' [flow-id :total-order-timeline] (serialize-total-order-timeline! flow-id to-tl main-file-path))
                                     flows')))
                        flows
                        (keys flows))]
    flows-with-tot))

(defn- serialize-forms [forms-ids main-file-path]
  (let [{:keys [file-path file-name]} (make-forms-file-path main-file-path)]
    (with-open [out-stream (ObjectOutputStream. (FileOutputStream. file-path))]
      (binding [*print-meta* true]
        (doseq [form-id forms-ids]
          (let [form-data (-> (ia/get-form form-id)
                              (update :form/form (fn [frm] (vary-meta frm dissoc :clojure.storm/emitted-coords))))]
            (.writeObject ^ObjectOutputStream out-stream form-data))))
      (.flush out-stream))
    file-name))

(defn- reify-flows-timelines []
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

(defn- render-bookmarks-html [bookmarks]
  (->> bookmarks
       (mapv (fn [{:keys [flow-id thread-id idx note]}]
               (format "<div><a onclick='gotoLocation.invoke(%d,%d,%d)'>%s</a></div>\n" flow-id thread-id idx note)))
       (apply str)))

(defn- create-flowbook-file [flowbook-fpath bookmarks]
  (let [flowbook-content (format "<html>\n<head> <style> a {cursor: pointer}; </style> </head>\n<body>\n%s\n</body>\n</html>" (render-bookmarks-html bookmarks))]
    (spit flowbook-fpath flowbook-content)))

(defn store-flowbook [main-file-path bookmarks _skip-flows-threads-set]
  (let [{value-fpath :file-path value-fname :file-name} (make-value-filepath main-file-path)
        {flowbook-fpath :file-path flowbook-fname :file-name} (make-flowbook-filepath main-file-path)
        {:keys [flows values forms-ids]} (reify-flows-timelines)
        _ (create-flowbook-file flowbook-fpath bookmarks)
        *unserializable-classes (atom #{})
        _ (serialize-values *unserializable-classes value-fpath values)
        flows-data (serialize-flows flows main-file-path)
        forms-file (serialize-forms forms-ids main-file-path)
        file-data {:version "1"
                   :flows flows-data
                   :values-file value-fname
                   :forms-file forms-file
                   :bookmarks bookmarks
                   :flowbook-file flowbook-fname}]
    (spit main-file-path (pr-str file-data))
    {:unserializable-classes @*unserializable-classes}))

(defn load-flowbook-v1 [main-file-path {:keys [values-file flows forms-file bookmarks flowbook-file]}]
  (try
    (let [{:keys [parent]} (file-info main-file-path)
          values (deserialize-objects (str parent File/separator values-file))
          flowbook-path (str parent File/separator flowbook-file)
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
                (replay-entry flow-id thread-id thread-name entry-map false))))))

      (doseq [b bookmarks]
        (rt-events/publish-event! (rt-events/make-expression-bookmark-event b)))
      {:flowbook-path flowbook-path})
    (catch Exception e (.printStackTrace e))))

(defn load-flowbook [main-file-path]
  (let [{:keys [version] :as file-data} (edn/read-string (slurp main-file-path))]
    (case version
      "1" (load-flowbook-v1 main-file-path file-data)
      (throw (ex-info "Unsupported version" file-data)))))

(dbg-api/register-api-function :plugins.flowbook/store-flowbook store-flowbook)
(dbg-api/register-api-function :plugins.flowbook/load-flowbook load-flowbook)
