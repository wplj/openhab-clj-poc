(ns openhab.transition
  (:require [clojure.set :as set]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.projection :as projection]
            [openhab.registry :as registry]
            [openhab.thing :as thing]))

(def ^:private desired-expiry-threshold 3)

(defn- result
  ([state]
   {:state state
    :events []
    :effects []})
  ([state extra]
   (merge (result state) extra)))

(defn- err-result [state reason]
  (result state {:result {:ok false :reason reason}}))

(defn- normalize-status [status]
  (if (map? status)
    status
    (thing/status status)))

(defn- thing-added-event [thing]
  {:event/type :thing/added
   :thing-id (:thing-id thing)
   :thing thing})

(defn- thing-removed-event [thing-id]
  {:event/type :thing/removed
   :thing-id thing-id})

(defn- thing-status-event [thing-id old-status new-status]
  {:event/type :thing/status-changed
   :thing-id thing-id
   :old-status old-status
   :status new-status})

(defn- item-added-event [itm]
  {:event/type :item/added
   :item-name (:item-name itm)
   :item itm})

(defn- item-removed-event [item-name]
  {:event/type :item/removed
   :item-name item-name})

(defn- item-change-event [{:keys [item-name old-item item]}]
  {:event/type :item/state-changed
   :item-name item-name
   :old-state (:state old-item)
   :state (:state item)
   :state-type (:state-type item)})

(defn- link-added-event [lnk]
  {:event/type :link/added
   :link-key (link/link-key lnk)
   :link lnk})

(defn- link-removed-event [link-key]
  {:event/type :link/removed
   :link-key link-key})

(defn- link-removed-events [link-keys]
  (mapv link-removed-event (sort-by pr-str link-keys)))

(defn- command-failed-event [thing-id command-id cleared-channel-ids]
  {:event/type :command/failed
   :thing-id thing-id
   :command-id command-id
   :channel-ids cleared-channel-ids})

(defn- clear-item-state [state item-name changed-at]
  (if-let [existing (registry/get-item state item-name)]
    (let [cleared (item/set-state existing nil nil changed-at)]
      (if (or (not= (:state existing) (:state cleared))
              (not= (:state-type existing) (:state-type cleared)))
        {:state (registry/put-item state cleared)
         :events [(item-change-event {:item-name item-name
                                      :old-item existing
                                      :item cleared})]}
        {:state state
         :events []}))
    {:state state
     :events []}))

(defn- clear-items [state item-names changed-at]
  (reduce (fn [{:keys [state events]} item-name]
            (let [{next-state :state next-events :events} (clear-item-state state item-name changed-at)]
              {:state next-state
               :events (into events next-events)}))
          {:state state
           :events []}
          (sort item-names)))

(defn- reproject-items [state profile-registry item-names changed-at]
  (let [projected (projection/project-items state profile-registry item-names changed-at)]
    {:state (:state projected)
     :events (mapv item-change-event (:changes projected))}))

(defn- grouped-channel-commands [channel-commands]
  (->> channel-commands
       (group-by :thing-id)
       (sort-by key)
       (mapv val)))

(defn- child-thing-ids [state thing-id]
  (mapv :thing-id (registry/children-of state thing-id)))

(defn- descendant-thing-ids [state thing-id]
  (loop [queue (child-thing-ids state thing-id)
         seen  #{}
         ids   []]
    (if-let [child-id (peek queue)]
      (let [queue' (pop queue)]
        (if (contains? seen child-id)
          (recur queue' seen ids)
          (recur (into queue' (child-thing-ids state child-id))
                 (conj seen child-id)
                 (conj ids child-id))))
      ids)))

(defn- set-offline
  ([state thing-ids]
   (set-offline state thing-ids #{}))
  ([state thing-ids suppress-events-for]
   (reduce (fn [{:keys [state events]} thing-id]
             (if-let [thing (registry/get-thing state thing-id)]
               (let [old-status (get-in thing [:runtime :status])
                     new-status (thing/status :offline)
                     changed?   (not= old-status new-status)]
                 {:state (assoc-in state [:things thing-id :runtime :status] new-status)
                  :events (cond-> events
                            (and changed? (not (contains? suppress-events-for thing-id)))
                            (conj (thing-status-event thing-id old-status new-status)))})
               {:state state
                :events events}))
           {:state state
            :events []}
           thing-ids)))

(defn- bridge-cycle?
  [state thing-id bridge-id]
  (loop [current-id bridge-id
         seen       #{}]
    (cond
      (nil? current-id)
      false

      (= current-id thing-id)
      true

      (contains? seen current-id)
      true

      :else
      (recur (:bridge-id (registry/get-thing state current-id))
             (conj seen current-id)))))

(defn- validate-bridge-parent
  [state thing]
  (if-let [bridge-id (:bridge-id thing)]
    (let [thing-id (:thing-id thing)
          parent   (registry/get-thing state bridge-id)]
      (cond
        (= bridge-id thing-id)
        {:ok false :reason :bridge-cycle}

        (nil? parent)
        {:ok false :reason :bridge-not-found}

        (not (thing/bridge? parent))
        {:ok false :reason :not-a-bridge}

        (bridge-cycle? state thing-id bridge-id)
        {:ok false :reason :bridge-cycle}

        :else
        {:ok true}))
    {:ok true}))

(defn add-thing
  "Registers or replaces a Thing. Emits an added event only when the Thing is new.
   Validates :bridge-id on add and re-registration so children cannot attach to
   missing or non-bridge parents, and rejects self-parenting / bridge cycles.
   Children attached to an offline bridge inherit offline status immediately."
  [state thing]
  (let [exists?     (some? (registry/get-thing state (:thing-id thing)))
        validation  (validate-bridge-parent state thing)]
    (if-not (:ok validation)
      (result state {:result validation})
      (let [thing-id      (:thing-id thing)
            state'        (registry/put-thing state thing)
            stored        (registry/get-thing state' thing-id)
            parent        (some->> (:bridge-id stored) (registry/get-thing state'))
            ;; Offline parent state is inherited immediately; no poll is needed to make topology safe.
            inherited     (if (= :offline (thing/status-value parent))
                            (set-offline state'
                                         (cons thing-id (descendant-thing-ids state' thing-id))
                                         (if exists? #{} #{thing-id}))
                            {:state state'
                             :events []})
            stored'       (registry/get-thing (:state inherited) thing-id)]
        (result (:state inherited)
                {:events (cond-> []
                           (not exists?) (conj (thing-added-event stored'))
                           (seq (:events inherited)) (into (:events inherited)))
                 :result {:ok true
                          :thing-id thing-id}})))))

(defn remove-thing
  "Removes a Thing, its Links, and any now-unlinked Item projections.
   When the Thing is a bridge, all children are cascade-removed first so no
   child is left with a dangling :bridge-id reference."
  [state thing-id changed-at]
  (if-let [thing (registry/get-thing state thing-id)]
    (let [child-ids (when (thing/bridge? thing)
                      (map :thing-id (registry/children-of state thing-id)))
          [state' child-events] (reduce (fn [[s evts] child-id]
                                          (let [r (remove-thing s child-id changed-at)]
                                            [(:state r) (into evts (:events r))]))
                                        [state []]
                                        child-ids)
          removed-link-keys (->> (registry/all-links state')
                                 (filter #(= thing-id (:thing-id %)))
                                 (map link/link-key)
                                 set)
          affected-item-names (set (map first removed-link-keys))
          removed-state (registry/remove-thing state' thing-id)
          cleared (clear-items removed-state affected-item-names changed-at)]
      (result (:state cleared)
              {:events (-> child-events
                           (into (link-removed-events removed-link-keys))
                           (into (:events cleared))
                           (conj (thing-removed-event thing-id)))
               :result {:ok true
                        :thing-id thing-id}}))
    (err-result state :thing-not-found)))

(defn remove-child
  "Removes child-id only when it currently belongs to bridge-id."
  [state bridge-id child-id changed-at]
  (let [bridge (registry/get-thing state bridge-id)
        child  (registry/get-thing state child-id)]
    ;; The bridge-scoped API must not become a backdoor for deleting unrelated Things.
    (cond
      (nil? bridge)
      (err-result state :bridge-not-found)

      (not (thing/bridge? bridge))
      (err-result state :not-a-bridge)

      (nil? child)
      (err-result state :thing-not-found)

      (not= bridge-id (:bridge-id child))
      (err-result state :not-a-child)

      :else
      (remove-thing state child-id changed-at))))

(defn add-item
  "Registers or replaces an Item. Emits an added event only when the Item is new.
   On re-registration: :item-type is immutable; projection-owned fields
   (:state :state-type :last-change) are preserved from the existing entry."
  [state itm]
  (let [item-name (:item-name itm)
        existing  (registry/get-item state item-name)
        stored    (if existing
                    (cond-> (assoc itm :item-type (:item-type existing))
                      (contains? existing :state)       (assoc :state       (:state       existing))
                      (contains? existing :state-type)  (assoc :state-type  (:state-type  existing))
                      (contains? existing :last-change) (assoc :last-change (:last-change existing)))
                    itm)]
    (result (registry/put-item state stored)
            {:events (if existing
                       []
                       [(item-added-event stored)])
             :result {:ok true
                      :item-name item-name}})))

(defn remove-item
  "Removes an Item and any Links that point at it."
  [state item-name]
  (if (registry/get-item state item-name)
    (let [removed-link-keys (registry/link-keys-for-item state item-name)]
      (result (registry/remove-item state item-name)
              {:events (conj (link-removed-events removed-link-keys)
                             (item-removed-event item-name))
               :result {:ok true
                        :item-name item-name}}))
    (err-result state :item-not-found)))

(defn add-link
  "Adds a Link when the Item, Thing, and Channel exist.
   Many-to-one Item links are rejected until an explicit reducer exists."
  [state profile-registry lnk changed-at]
  (let [item-name (:item-name lnk)
        thing-id (:thing-id lnk)
        channel-id (:channel-id lnk)
        thing (registry/get-thing state thing-id)
        itm (registry/get-item state item-name)
        existing-keys (registry/link-keys-for-item state item-name)
        link-key (link/link-key lnk)
        existing-link (registry/get-link state item-name thing-id channel-id)]
    (cond
      (nil? itm)
      (err-result state :item-not-found)

      (nil? thing)
      (err-result state :thing-not-found)

      (nil? (get-in thing [:channels channel-id]))
      (err-result state :channel-not-found)

      (and (seq existing-keys) (not (contains? existing-keys link-key)))
      (err-result state :ambiguous-item-links)

      (not (profile/known-profile? profile-registry (:profile lnk)))
      (err-result state :unknown-profile)

      (= existing-link lnk)
      (result state {:result {:ok true
                              :link-key link-key}})

      :else
      (let [state' (registry/put-link state lnk)
            projected (reproject-items state' profile-registry [item-name] changed-at)]
        (result (:state projected)
                {:events (into [(link-added-event lnk)] (:events projected))
                 :result {:ok true
                          :link-key link-key}})))))

(defn remove-link
  "Removes a Link and clears the Item projection cache when it no longer has a source."
  [state item-name thing-id channel-id changed-at]
  (let [link-key [item-name thing-id channel-id]]
    (if (registry/get-link state item-name thing-id channel-id)
      (let [state' (registry/remove-link state item-name thing-id channel-id)
            cleared (if (empty? (registry/link-keys-for-item state' item-name))
                      (clear-item-state state' item-name changed-at)
                      {:state state' :events []})]
        (result (:state cleared)
                {:events (into [(link-removed-event link-key)] (:events cleared))
                 :result {:ok true
                          :link-key link-key}}))
      (err-result state :link-not-found))))

(defn- validate-channel-command [state {:keys [thing-id channel-id]}]
  (let [thing (registry/get-thing state thing-id)]
    (cond
      (nil? thing)
      {:ok false :reason :thing-not-found}

      (not (thing/online? thing))
      {:ok false :reason :thing-offline}

      (nil? (get-in thing [:channels channel-id]))
      {:ok false :reason :channel-not-found}

      (not= :rw (get-in thing [:channels channel-id :access]))
      {:ok false :reason :channel-read-only}

      :else
      {:ok true :thing thing})))

(defn- item-command->channel-commands [state profile-registry item-name item-value]
  (if-let [itm (registry/get-item state item-name)]
    (let [links (vec (registry/links-for-item state item-name))]
      (case (count links)
        0
        {:ok false :reason :item-not-linked}

        1
        (let [lnk (first links)
              thing (registry/get-thing state (:thing-id lnk))]
          (cond
            (nil? thing)
            {:ok false :reason :thing-not-found}

            (nil? (get-in thing [:channels (:channel-id lnk)]))
            {:ok false :reason :channel-not-found}

            (not (thing/online? thing))
            {:ok false :reason :thing-offline}

            (not= :rw (get-in thing [:channels (:channel-id lnk) :access]))
            {:ok false :reason :channel-read-only}

            :else
            (try
              {:ok true
               :channel-commands [{:thing-id (:thing-id lnk)
                                   :channel-id (:channel-id lnk)
                                   :value (profile/encode-command
                                           profile-registry
                                           {:thing thing
                                            :item itm
                                            :link lnk
                                            :channel-id (:channel-id lnk)
                                            :channel-value (thing/effective-channel thing (:channel-id lnk))
                                            :item-value item-value})}]}
              (catch clojure.lang.ExceptionInfo ex
                (if (:profile (ex-data ex))
                  {:ok false :reason :unknown-profile}
                  {:ok false :reason :encoding-failed}))
              (catch Exception _
                {:ok false :reason :encoding-failed}))))

        {:ok false :reason :ambiguous-item-links}))
    {:ok false :reason :item-not-found}))

(defn command-accepted
  "Applies optimistic desired state for channel commands and emits send effects.
   channel-commands is a vector of {:thing-id ... :channel-id ... :value ...}."
  [state profile-registry command-id channel-commands changed-at]
  (if (empty? channel-commands)
    (err-result state :no-commands)
    (let [validation-results (map #(validate-channel-command state %) channel-commands)
          invalid (some #(when-not (:ok %) (select-keys % [:reason])) validation-results)]
      (if invalid
        (err-result state (:reason invalid))
        (let [grouped (grouped-channel-commands channel-commands)
              state' (reduce (fn [s commands-for-thing]
                               (let [thing-id (:thing-id (first commands-for-thing))]
                                 (update-in s [:things thing-id :runtime :desired]
                                            (fn [desired]
                                              (reduce (fn [m {:keys [channel-id value]}]
                                                        (assoc m channel-id {:value value
                                                                             :age 0
                                                                             :command-id command-id}))
                                                      (or desired {})
                                                      commands-for-thing)))))
                             state
                             grouped)
              affected-items (->> channel-commands
                                  (mapcat (fn [{:keys [thing-id channel-id]}]
                                            (projection/affected-item-names state' thing-id [channel-id])))
                                  set)
              projected (reproject-items state' profile-registry affected-items changed-at)
              effects (mapv (fn [commands-for-thing]
                              {:kind :openhab/send-command
                               :thing-id (:thing-id (first commands-for-thing))
                               :command-id command-id
                               :commands (into {}
                                               (map (juxt :channel-id :value))
                                               commands-for-thing)})
                            grouped)]
          (result (:state projected)
                  {:events (:events projected)
                   :effects effects
                   :result {:ok true
                            :command-id command-id}}))))))

(defn item-command-accepted
  "Resolves an Item command inside the transition so link/profile reads and writes share one registry snapshot."
  [state profile-registry command-id item-name item-value changed-at]
  (let [resolved (item-command->channel-commands state profile-registry item-name item-value)]
    (if-not (:ok resolved)
      (result state {:result (select-keys resolved [:ok :reason])})
      (command-accepted state profile-registry command-id (:channel-commands resolved) changed-at))))

(defn command-failed
  "Clears desired entries for thing-id that still belong to command-id."
  [state profile-registry thing-id command-id changed-at]
  (if-let [thing (registry/get-thing state thing-id)]
    (let [desired (get-in thing [:runtime :desired] {})
          ;; Stale failure callbacks must not clear a newer command's desired overlay.
          cleared-channel-ids (->> desired
                                   (keep (fn [[channel-id entry]]
                                           (when (= command-id (:command-id entry))
                                             channel-id)))
                                   set)
          state' (if (seq cleared-channel-ids)
                   (update-in state [:things thing-id :runtime :desired]
                              (fn [current]
                                (apply dissoc current cleared-channel-ids)))
                   state)
          projected (reproject-items state'
                                     profile-registry
                                     (projection/affected-item-names state' thing-id cleared-channel-ids)
                                     changed-at)]
      (result (:state projected)
              {:events (into (if (seq cleared-channel-ids)
                               [(command-failed-event thing-id command-id cleared-channel-ids)]
                               [])
                             (:events projected))
               :result {:ok true
                        :thing-id thing-id
                        :cleared cleared-channel-ids}}))
    (err-result state :thing-not-found)))

(defn channels-reported
  "Replaces reported channel state for a Thing, settles matching desired entries,
   ages pending desired entries, expires stale ones, and reprojects affected Items.

   reported must be a non-nil full snapshot map whose keys are declared channels.
   Pass {} when the device reports no channels. Passing nil is a programming error;
   use transition/set-thing-status when the device is unreachable.

   The 5-arity overload uses the default expiry threshold (3 polls). Use the
   6-arity overload to override: desired entries are dropped when their age
   reaches expiry-threshold without being settled by a poll."
  ([state profile-registry thing-id reported changed-at]
   (channels-reported state profile-registry thing-id reported desired-expiry-threshold changed-at))
  ([state profile-registry thing-id reported expiry-threshold changed-at]
   (when (nil? reported)
     (throw (ex-info "channels-reported requires a non-nil reported map; pass {} for no channels"
                     {:thing-id thing-id})))
   (if-let [thing (registry/get-thing state thing-id)]
     (let [declared-channel-ids (set (keys (:channels thing)))
           reported-channel-ids (set (keys reported))
           unknown-channel-id (first (sort-by pr-str (set/difference reported-channel-ids declared-channel-ids)))]
       (if unknown-channel-id
         (result state {:result {:ok false
                                 :reason :unknown-reported-channel
                                 :channel-id unknown-channel-id}})
         (let [old-runtime (:runtime thing)
               old-status (:status old-runtime)
               old-reported (or (:reported old-runtime) {})
               old-desired (or (:desired old-runtime) {})
               reported-changed-channel-ids (->> (set/union (set (keys old-reported))
                                                            reported-channel-ids)
                                                 (filter #(not= (get old-reported % ::missing)
                                                                (get reported % ::missing)))
                                                 set)
               ;; Reported is a full snapshot: a present channel is authoritative, even if value differs.
               desired-pruned-channel-ids (set/intersection reported-channel-ids (set (keys old-desired)))
               remaining-desired (apply dissoc old-desired desired-pruned-channel-ids)
               aged-desired (update-vals remaining-desired #(update % :age inc))
               desired-expired-channel-ids (->> aged-desired
                                                (keep (fn [[channel-id entry]]
                                                        (when (>= (:age entry) expiry-threshold)
                                                          channel-id)))
                                                set)
               final-desired (apply dissoc aged-desired desired-expired-channel-ids)
               new-status (if (thing/online? thing)
                            old-status
                            (thing/status :online))
               new-runtime (assoc old-runtime
                                  :status new-status
                                  :reported reported
                                  :desired final-desired)
               state' (assoc-in state [:things thing-id :runtime] new-runtime)
               affected-channel-ids (set/union reported-changed-channel-ids
                                               desired-pruned-channel-ids
                                               desired-expired-channel-ids)
               projected (reproject-items state'
                                          profile-registry
                                          (projection/affected-item-names state' thing-id affected-channel-ids)
                                          changed-at)
               status-events (if (not= old-status new-status)
                               [(thing-status-event thing-id old-status new-status)]
                               [])]
           (result (:state projected)
                   {:events (into (vec status-events) (:events projected))
                    :result {:ok true
                             :thing-id thing-id
                             :affected-channel-ids affected-channel-ids}}))))
     (err-result state :thing-not-found))))

(defn set-thing-status
  "Sets a Thing status map directly."
  [state thing-id status]
  (if-let [thing (registry/get-thing state thing-id)]
    (let [new-status (normalize-status status)
          old-status (get-in thing [:runtime :status])
          state' (assoc-in state [:things thing-id :runtime :status] new-status)]
      (result state'
              {:events (if (not= old-status new-status)
                         [(thing-status-event thing-id old-status new-status)]
                         [])
               :result {:ok true
                        :thing-id thing-id}}))
    (err-result state :thing-not-found)))

(defn set-bridge-status
  "Sets bridge status. When going offline, all child Things are marked offline too."
  [state bridge-id status]
  (let [bridge (registry/get-thing state bridge-id)]
    (cond
      (nil? bridge)
      (err-result state :thing-not-found)

      (not (thing/bridge? bridge))
      (err-result state :not-a-bridge)

      :else
      (let [new-status (normalize-status status)
            old-status (get-in bridge [:runtime :status])
            child-ids (descendant-thing-ids state bridge-id)
            state' (assoc-in state [:things bridge-id :runtime :status] new-status)
            offline? (= :offline (:value new-status))
            offline-result (if offline?
                             (set-offline state' child-ids)
                             {:state state'
                              :events []})
            state'' (:state offline-result)
            child-events (:events offline-result)
            bridge-event (when (not= old-status new-status)
                           (thing-status-event bridge-id old-status new-status))]
        (result state''
                {:events (vec (concat (when bridge-event [bridge-event]) child-events))
                 :result {:ok true
                          :thing-id bridge-id}})))))
