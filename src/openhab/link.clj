(ns openhab.link
  "Item-to-channel link data shape.

   A Link is structural metadata: it names the Item, Thing channel, and profile
   used when projecting channel state or encoding item commands."
  (:require [clojure.spec.alpha :as s]
            [openhab.item :as item]
            [openhab.thing :as thing]))

(s/def ::channel-id keyword?)
(s/def ::profile (s/and string? seq))

(s/def ::link
  (s/keys :req-un [::item/item-name ::thing/thing-id ::channel-id ::profile]))

(defn make-link
  "Returns a valid Link map. Defaults to the system default profile."
  ([item-name thing-id channel-id] (make-link item-name thing-id channel-id "system:default"))
  ([item-name thing-id channel-id profile] {:item-name item-name
                                            :thing-id thing-id
                                            :channel-id channel-id
                                            :profile profile}))

(defn link-key
  "Returns the composite key for a Link."
  [{:keys [item-name thing-id channel-id]}]
  [item-name thing-id channel-id])
