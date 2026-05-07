(ns openhab.api.view
  "Transport-neutral conversion from query read models to API-safe values.

   The query layer keeps typed EDN. This layer makes those values portable JSON
   data without choosing route names, HTTP status codes, or GraphQL field shapes.")

(defn- keyword-string [x]
  (cond->> (name x)
    (namespace x) (str (namespace x) "/")))

(defn- map-key [k]
  (cond
    (keyword? k) (keyword-string k)
    (string? k) k
    :else (str k)))

(defn- json-number? [value]
  ;; JSON has no portable representation for ratios, NaN, or infinities.
  (cond
    (ratio? value) false
    (instance? Double value) (Double/isFinite value)
    (instance? Float value) (Float/isFinite value)
    :else (number? value)))

(defn api-value
  "Returns a JSON-safe representation of a Clojure value.

   Keywords and symbols become strings, instants and UUIDs become ISO/plain strings,
   maps get string keys, and sequential/set values become vectors. Primitive JSON
   scalar values pass through unchanged, but ratios and non-finite floating-point
   values are rejected because JSON has no portable representation for them."
  [value]
  (cond
    (nil? value) nil
    (or (string? value) (boolean? value)) value
    (json-number? value) value
    (number? value) (throw (ex-info "Unsupported API number"
                                     {:value value
                                      :class (some-> value class .getName)}))
    (keyword? value) (keyword-string value)
    (symbol? value) (str value)
    (instance? java.time.Instant value) (.toString value)
    (inst? value) (-> value .toInstant .toString)
    (uuid? value) (str value)
    (map? value) (into {}
                       (map (fn [[k v]]
                              [(map-key k) (api-value v)]))
                       value)
    (set? value) (mapv api-value (sort-by pr-str value))
    (sequential? value) (mapv api-value value)
    :else (throw (ex-info "Unsupported API value"
                          {:value value
                           :class (some-> value class .getName)}))))

(defn- assoc-some [m k v]
  (if (some? v)
    (assoc m k v)
    m))

(defn link
  "Returns the public API view of a Link read model."
  [lnk]
  (-> (select-keys lnk [:item-name :thing-id :profile])
      (assoc :channel-id (api-value (:channel-id lnk)))))

(defn item
  "Returns the public API view of an Item read model."
  [itm]
  (cond-> (assoc (select-keys itm [:item-name :item-type])
                 :tags (api-value (:tags itm))
                 :group-names (api-value (:group-names itm))
                 :links (mapv link (:links itm)))
    (contains? itm :label) (assoc :label (:label itm))
    (contains? itm :category) (assoc :category (:category itm))
    (contains? itm :metadata) (assoc :metadata (api-value (:metadata itm)))
    (contains? itm :state) (assoc :state (api-value (:state itm)))
    (contains? itm :state-type) (assoc :state-type (api-value (:state-type itm)))
    (contains? itm :last-change) (assoc :last-change (api-value (:last-change itm)))))

(defn status
  "Returns the public API view of a Thing status map."
  [status-map]
  (-> {:value (api-value (:value status-map))}
      (assoc-some :detail (some-> (:detail status-map) api-value))
      (assoc-some :description (:description status-map))))

(defn channel
  "Returns the public API view of a Channel read model."
  [ch]
  (cond-> {:channel-id (api-value (:channel-id ch))
           :access (api-value (:access ch))
           :channel-type (api-value (:channel-type ch))
           :linked-items (api-value (:linked-items ch))
           :pending? (:pending? ch)}
    (contains? ch :reported-value) (assoc :reported-value (api-value (:reported-value ch)))
    (contains? ch :desired-value) (assoc :desired-value (api-value (:desired-value ch)))
    (contains? ch :effective-value) (assoc :effective-value (api-value (:effective-value ch)))))

(defn thing
  "Returns the public API view of a Thing read model."
  [th]
  (cond-> {:thing-id (:thing-id th)
           :thing-type (api-value (:thing-type th))
           :bridge? (:bridge? th)
           :status (status (:status th))
           :channels (mapv channel (:channels th))}
    (contains? th :bridge-id) (assoc :bridge-id (:bridge-id th))
    (contains? th :properties) (assoc :properties (api-value (:properties th)))
    (contains? th :config) (assoc :config (api-value (:config th)))
    (contains? th :child-thing-ids) (assoc :child-thing-ids (api-value (:child-thing-ids th)))))

(defn system-snapshot
  "Returns the public API view of a query/system-snapshot map."
  [snapshot]
  {:things (mapv thing (:things snapshot))
   :items (mapv item (:items snapshot))
   :links (mapv link (:links snapshot))})
