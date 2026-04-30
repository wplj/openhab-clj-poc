(ns openhab.projection
  (:require [openhab.item :as item]
            [openhab.profile :as profile]
            [openhab.registry :as registry]
            [openhab.thing :as thing]))

(defn affected-item-names
  "Returns the set of Item names affected by channel changes on thing-id."
  [state thing-id channel-ids]
  (->> channel-ids
       (mapcat #(registry/link-keys-for-channel state thing-id %))
       (map first)
       set))

(defn project-item-state
  "Returns the projected state map for item-name, or nil when the Item has no Link.
   Throws when the Item has more than one Link — the single-link invariant must hold."
  [state profile-registry item-name]
  (let [links           (registry/links-for-item state item-name)
        [lnk second-lnk] links]
    ;; Fail loudly if a caller bypasses add-link; reducer profiles can replace this later.
    (when second-lnk
      (throw (ex-info "Multi-link item violates single-link invariant"
                      {:item-name item-name})))
    (when lnk
      (let [thing (registry/get-thing state (:thing-id lnk))
            itm   (registry/get-item state item-name)]
        (when (and thing itm)
          (profile/project-state profile-registry
                                 {:thing         thing
                                  :item          itm
                                  :link          lnk
                                  :channel-id    (:channel-id lnk)
                                  :channel-value (thing/effective-channel thing (:channel-id lnk))}))))))

(defn project-items
  "Reprojects the supplied Items. Returns {:state ... :changes [...]}.
   Each change includes :item-name, :old-item, and :item. changed-at is written to
   :last-change for Items whose projected state changes."
  [state profile-registry item-names changed-at]
  (reduce (fn [{:keys [state changes]} item-name]
            (if-let [current-item (registry/get-item state item-name)]
              (if-let [projected (project-item-state state profile-registry item-name)]
                (let [updated-item (item/set-state current-item
                                                   (:state projected)
                                                   (:state-type projected)
                                                   changed-at)]
                  (if (or (not= (:state current-item) (:state updated-item))
                          (not= (:state-type current-item) (:state-type updated-item)))
                    {:state (registry/put-item state updated-item)
                     :changes (conj changes {:item-name item-name
                                             :old-item current-item
                                             :item updated-item})}
                    {:state state
                     :changes changes}))
                {:state state
                 :changes changes})
              {:state state
               :changes changes}))
          {:state state
           :changes []}
          (sort item-names)))
