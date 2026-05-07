(ns openhab.events
  "Small core.async event bus with OpenHAB-style external event names and topics.

   Producers publish domain event maps. The bus decorates them once, then supports
   subscription by event type, exact topic, or all events for SSE-style streams."
  (:require [clojure.core.async :refer [chan close! mult pub put! sub tap unsub untap]]))

(defn- event-name [{:keys [event/type]}]
  (case type
    :thing/added "ThingAddedEvent"
    :thing/removed "ThingRemovedEvent"
    :thing/status-changed "ThingStatusInfoChangedEvent"
    :item/added "ItemAddedEvent"
    :item/removed "ItemRemovedEvent"
    :item/state-changed "ItemStateChangedEvent"
    :link/added "ItemChannelLinkAddedEvent"
    :link/removed "ItemChannelLinkRemovedEvent"
    :command/failed "CommandFailedEvent"
    (str (some-> (namespace type) (str ".")) (name type))))

(defn- event-topic [{:keys [event/type thing-id item-name link-key command-id]}]
  (case type
    :thing/added (str "openhab/things/" thing-id "/added")
    :thing/removed (str "openhab/things/" thing-id "/removed")
    :thing/status-changed (str "openhab/things/" thing-id "/statuschanged")
    :item/added (str "openhab/items/" item-name "/added")
    :item/removed (str "openhab/items/" item-name "/removed")
    :item/state-changed (str "openhab/items/" item-name "/statechanged")
    :link/added (let [[item-name thing-id ch-id] link-key]
                  (str "openhab/links/" item-name ":" thing-id ":" (name ch-id) "/added"))
    :link/removed (let [[item-name thing-id ch-id] link-key]
                    (str "openhab/links/" item-name ":" thing-id ":" (name ch-id) "/removed"))
    :command/failed (str "openhab/things/" thing-id "/commands/" command-id "/failed")
    (when (keyword? type)
      (str "openhab/events/"
           (some-> (namespace type) (str "/"))
           (name type)))))

(defn- decorate-event [event]
  (assoc event
         :event/name  (event-name event)
         :event/topic (event-topic event)))

(defn make-bus
  "Returns a new keyword/event bus. Subscribers can listen by :event/type or exact topic."
  ([] (make-bus 256))
  ([buf-size]
   ;; One source channel fans out to separate pubs so type/topic subscribers cannot
   ;; consume events from each other; subscribe-all! taps the same mult directly.
   (let [ch (chan buf-size)
         type-ch (chan buf-size)
         topic-ch (chan buf-size)
         bus-mult (mult ch)]
     (tap bus-mult type-ch)
     (tap bus-mult topic-ch)
     {:ch ch
      :mult bus-mult
      :type-pub (pub type-ch :event/type)
      :topic-pub (pub topic-ch :event/topic)})))

(defn publish!
  "Puts event onto the bus after enriching it with external name/topic metadata."
  [{:keys [ch]} event]
  (put! ch (decorate-event event)))

(defn subscribe!
  "Returns a new channel subscribed to a keyword event type."
  [{:keys [type-pub]} event-type buf-or-n]
  (let [sub-ch (chan buf-or-n)]
    (sub type-pub event-type sub-ch)
    sub-ch))

(defn unsubscribe!
  [{:keys [type-pub]} event-type sub-ch]
  (unsub type-pub event-type sub-ch)
  (close! sub-ch))

(defn subscribe-topic!
  [{:keys [topic-pub]} topic buf-or-n]
  (let [sub-ch (chan buf-or-n)]
    (sub topic-pub topic sub-ch)
    sub-ch))

(defn unsubscribe-topic!
  [{:keys [topic-pub]} topic sub-ch]
  (unsub topic-pub topic sub-ch)
  (close! sub-ch))

(defn subscribe-all!
  "Returns a new channel subscribed to all decorated events on the bus."
  [{:keys [mult]} buf-or-n]
  (let [sub-ch (chan buf-or-n)]
    (tap mult sub-ch)
    sub-ch))

(defn unsubscribe-all!
  [{:keys [mult]} sub-ch]
  (untap mult sub-ch)
  (close! sub-ch))

(defn close-bus!
  [{:keys [ch]}]
  (close! ch))
