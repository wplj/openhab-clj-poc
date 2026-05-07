(ns openhab.query
  "Pure read-model boundary over registry state.

   Query functions hide raw indexes and runtime internals while preserving typed
   EDN values for API/view layers to serialize later."
  (:require [openhab.link :as link]
            [openhab.registry :as registry]
            [openhab.thing :as thing]))

(defn- snapshot [registry-or-state]
  ;; Deref once at the public boundary so nested read-model derivation is consistent.
  (if (instance? clojure.lang.IDeref registry-or-state)
    @registry-or-state
    registry-or-state))

(defn- sorted-vec [xs]
  (vec (sort-by pr-str xs)))

(defn- link-view [lnk]
  (select-keys lnk [:item-name :thing-id :channel-id :profile]))

(defn- links* [state]
  (->> (registry/all-links state)
       (sort-by link/link-key)
       (mapv link-view)))

(defn- links-for-item* [state item-name]
  (mapv link-view (registry/links-for-item state item-name)))

(defn- links-for-channel* [state thing-id channel-id]
  (mapv link-view (registry/links-for-channel state thing-id channel-id)))

(defn links
  "Returns all links as stable, API-facing maps sorted by link key."
  [registry-or-state]
  (links* (snapshot registry-or-state)))

(defn links-for-item
  "Returns links for item-name sorted by link key."
  [registry-or-state item-name]
  (links-for-item* (snapshot registry-or-state) item-name))

(defn links-for-channel
  "Returns links for thing-id/channel-id sorted by link key."
  [registry-or-state thing-id channel-id]
  (links-for-channel* (snapshot registry-or-state) thing-id channel-id))

(defn- item-view [state itm]
  (let [item-name (:item-name itm)]
    (cond-> (-> (select-keys itm [:item-name :item-type :label :category :metadata])
                (assoc :tags (sorted-vec (:tags itm))
                       :group-names (sorted-vec (:group-names itm))
                       :links (links-for-item* state item-name)))
      (contains? itm :state)       (assoc :state (:state itm))
      (contains? itm :state-type)  (assoc :state-type (:state-type itm))
      (contains? itm :last-change) (assoc :last-change (:last-change itm)))))

(defn item
  "Returns one Item read model, or nil when item-name is unknown.

   The read model keeps typed EDN state but uses sorted vectors for collection fields so
   callers see a stable shape that does not expose registry index internals. :tags and
   :group-names are always present as vectors; :label, :category, and :metadata are present
   only when stored on the Item."
  [registry-or-state item-name]
  (let [state (snapshot registry-or-state)]
    (some->> (registry/get-item state item-name)
             (item-view state))))

(defn items
  "Returns all Item read models sorted by item name."
  [registry-or-state]
  (let [state (snapshot registry-or-state)]
    (->> (registry/all-items state)
         vals
         (sort-by :item-name)
         (mapv #(item-view state %)))))

(defn- linked-item-names [state thing-id channel-id]
  (->> (registry/links-for-channel state thing-id channel-id)
       (map :item-name)
       sorted-vec))

(defn- channel-view [state thing channel-id channel]
  (let [thing-id (:thing-id thing)
        reported (get-in thing [:runtime :reported] {})
        desired  (get-in thing [:runtime :desired] {})
        desired? (contains? desired channel-id)
        reported? (contains? reported channel-id)]
    (cond-> (assoc channel
                   :channel-id channel-id
                   :linked-items (linked-item-names state thing-id channel-id)
                   :pending? desired?)
      reported? (assoc :reported-value (get reported channel-id))
      desired?  (assoc :desired-value (get-in desired [channel-id :value]))
      (or reported? desired?) (assoc :effective-value (thing/effective-channel thing channel-id)))))

(defn- channel-views [state thing]
  (->> (:channels thing)
       (sort-by (comp pr-str key))
       (mapv (fn [[channel-id channel]]
               (channel-view state thing channel-id channel)))))

(defn- child-thing-ids [state thing-id]
  (mapv :thing-id (registry/children-of state thing-id)))

(defn thing
  "Returns one Thing read model, or nil when thing-id is unknown.

   Runtime internals such as desired ages and command ids are intentionally hidden.
   Channels expose reported, desired, and effective values only when a value exists."
  [registry-or-state thing-id]
  (let [state (snapshot registry-or-state)]
    (when-let [th (registry/get-thing state thing-id)]
      (cond-> (-> (select-keys th [:thing-id :thing-type :bridge-id :properties :config])
                  (assoc :bridge? (thing/bridge? th)
                         :status (get-in th [:runtime :status])
                         :channels (channel-views state th)))
        (thing/bridge? th) (assoc :child-thing-ids (child-thing-ids state thing-id))))))

(defn things
  "Returns all Thing read models sorted by thing id."
  [registry-or-state]
  (let [state (snapshot registry-or-state)]
    (->> (registry/all-things state)
         vals
         (sort-by :thing-id)
         (mapv #(thing state (:thing-id %))))))

(defn system-snapshot
  "Returns a stable public snapshot of the registry without raw indexes."
  [registry-or-state]
  (let [state (snapshot registry-or-state)]
    {:things (things state)
     :items  (items state)
     :links  (links* state)}))
