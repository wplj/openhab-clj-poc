(ns openhab.registry
  (:require [clojure.spec.alpha :as s]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.thing :as thing]))

(defn empty-state
  "Returns the empty registry value."
  []
  {:things {}
   :items {}
   :links {}
   :item->links {}
   :channel->links {}
   :bridge->things {}})

(defn make-registry
  "Returns a new registry atom."
  []
  (atom (empty-state)))

(defn- snapshot [registry-or-state]
  (if (instance? clojure.lang.IDeref registry-or-state)
    @registry-or-state
    registry-or-state))

(defn- prune-empty-set [m k]
  (if (seq (get m k))
    m
    (dissoc m k)))

(defn- remove-link-key* [state link-key]
  (if-let [lnk (get-in state [:links link-key])]
    (let [{:keys [item-name thing-id channel-id]} lnk]
      (-> state
          (update :links dissoc link-key)
          (update :item->links
                  (fn [idx]
                    (-> idx
                        (update item-name disj link-key)
                        (prune-empty-set item-name))))
          (update :channel->links
                  (fn [idx]
                    (let [channel-key [thing-id channel-id]]
                      (-> idx
                          (update channel-key disj link-key)
                          (prune-empty-set channel-key)))))))
    state))

(defn get-thing [registry-or-state thing-id]
  (get-in (snapshot registry-or-state) [:things thing-id]))

(defn all-things [registry-or-state]
  (:things (snapshot registry-or-state)))

(defn get-item [registry-or-state item-name]
  (get-in (snapshot registry-or-state) [:items item-name]))

(defn all-items [registry-or-state]
  (:items (snapshot registry-or-state)))

(defn get-link [registry-or-state item-name thing-id channel-id]
  (get-in (snapshot registry-or-state) [:links [item-name thing-id channel-id]]))

(defn all-links [registry-or-state]
  (vals (:links (snapshot registry-or-state))))

(defn link-keys-for-item [registry-or-state item-name]
  (get-in (snapshot registry-or-state) [:item->links item-name] #{}))

(defn links-for-item [registry-or-state item-name]
  (let [state (snapshot registry-or-state)]
    (map #(get-in state [:links %]) (sort (link-keys-for-item state item-name)))))

(defn link-keys-for-channel [registry-or-state thing-id channel-id]
  (get-in (snapshot registry-or-state) [:channel->links [thing-id channel-id]] #{}))

(defn links-for-channel [registry-or-state thing-id channel-id]
  (let [state (snapshot registry-or-state)]
    (map #(get-in state [:links %]) (sort (link-keys-for-channel state thing-id channel-id)))))

(defn children-of [registry-or-state bridge-id]
  (let [state (snapshot registry-or-state)]
    (->> (get-in state [:bridge->things bridge-id] #{})
         sort
         (map #(get-in state [:things %]))
         (remove nil?))))

(defn put-thing
  "Associates a Thing into the registry state and maintains reverse bridge indexes.
   When a Thing with this thing-id already exists:
   - :runtime is preserved (never overwritten by structural updates)
   - :thing-type, :bridge?, and :channels are immutable after first registration"
  [state thing]
  (let [thing-id  (:thing-id thing)
        old-thing (get-in state [:things thing-id])
        stored    (if old-thing
                    (cond-> (assoc thing :runtime (:runtime old-thing))
                      (contains? old-thing :thing-type) (assoc :thing-type (:thing-type old-thing))
                      (contains? old-thing :bridge?)    (assoc :bridge?    (:bridge?    old-thing))
                      (contains? old-thing :channels)   (assoc :channels   (:channels   old-thing)))
                    thing)
        old-bridge (:bridge-id old-thing)
        new-bridge (:bridge-id stored)
        state' (assoc-in state [:things thing-id] stored)
        state'' (if old-bridge
                  (update state' :bridge->things
                          (fn [idx]
                            (-> idx
                                (update old-bridge disj thing-id)
                                (prune-empty-set old-bridge))))
                  state')]
    (if new-bridge
      (update-in state'' [:bridge->things new-bridge] (fnil conj #{}) thing-id)
      state'')))

(defn remove-thing
  "Removes a Thing and any Links that point at it."
  [state thing-id]
  (if-let [thing (get-in state [:things thing-id])]
    (let [link-keys (->> (:links state)
                         keys
                         (filter #(= thing-id (second %))))
          state' (reduce remove-link-key* state link-keys)
          state'' (update state' :things dissoc thing-id)
          bridge-id (:bridge-id thing)]
      (if bridge-id
        (update state'' :bridge->things
                (fn [idx]
                  (-> idx
                      (update bridge-id disj thing-id)
                      (prune-empty-set bridge-id))))
        state''))
    state))

(defn put-item
  "Associates an Item into the registry state."
  [state item]
  (assoc-in state [:items (:item-name item)] item))

(defn remove-item
  "Removes an Item and any Links that point at it."
  [state item-name]
  (if (get-in state [:items item-name])
    (let [link-keys (get-in state [:item->links item-name] #{})]
      (-> (reduce remove-link-key* state link-keys)
          (update :items dissoc item-name)))
    state))

(defn put-link
  "Associates a Link into the registry state and updates indexes."
  [state lnk]
  (let [link-key (link/link-key lnk)
        {:keys [item-name thing-id channel-id]} lnk]
    (-> state
        (assoc-in [:links link-key] lnk)
        (update-in [:item->links item-name] (fnil conj #{}) link-key)
        (update-in [:channel->links [thing-id channel-id]] (fnil conj #{}) link-key))))

(defn remove-link
  "Removes a Link from the registry state."
  [state item-name thing-id channel-id]
  (remove-link-key* state [item-name thing-id channel-id]))


(s/def ::things (s/map-of ::thing/thing-id ::thing/thing))
(s/def ::items (s/map-of ::item/item-name ::item/item))
(s/def ::links (s/map-of vector? ::link/link))
(s/def ::item->links (s/map-of ::item/item-name set?))
(s/def ::channel->links (s/map-of vector? set?))
(s/def ::bridge->things (s/map-of ::thing/thing-id set?))
(s/def ::registry
  (s/keys :req-un [::things ::items ::links ::item->links ::channel->links ::bridge->things]))
