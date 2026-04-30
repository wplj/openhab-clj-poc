(ns openhab.thing
  (:require [clojure.spec.alpha :as s]))

(def status-values #{:initializing :online :offline :unknown})

(s/def ::value any?)
(s/def ::detail (s/nilable keyword?))
(s/def ::description (s/nilable string?))
(s/def ::status
  (s/and
    (s/keys :req-un [::value]
            :opt-un [::detail ::description])
    #(contains? status-values (:value %))))

(s/def ::channel-id keyword?)
(s/def ::access #{:ro :rw})
(s/def ::channel-type keyword?)
(s/def ::channel
  (s/keys :req-un [::access ::channel-type]))
(s/def ::channels (s/map-of ::channel-id ::channel))

(s/def ::thing-id (s/and string? seq))
(s/def ::thing-type keyword?)
(s/def ::reported (s/map-of ::channel-id any?))
(s/def ::age nat-int?)
(s/def ::command-id (s/and string? seq))
(s/def ::desired-entry
  (s/keys :req-un [::value ::age ::command-id]))
(s/def ::desired (s/map-of ::channel-id ::desired-entry))
(s/def ::runtime
  (s/keys :req-un [::status ::reported ::desired]))
(s/def ::properties (s/map-of keyword? string?))
(s/def ::config map?)
(s/def ::bridge-id ::thing-id)
(s/def ::bridge? boolean?)

(s/def ::thing
  (s/keys :req-un [::thing-id ::thing-type ::runtime]
          :opt-un [::channels ::properties ::config ::bridge-id ::bridge?]))

(s/def ::bridge
  (s/and ::thing
         (s/keys :req-un [::bridge?])
         #(true? (:bridge? %))))

(defn bridge?
  "Returns true when thing is a bridge."
  [thing]
  (true? (:bridge? thing)))

(defn status
  "Returns a status map for Thing runtime state."
  [value & {:keys [detail description]}]
  (cond-> {:value value}
    (some? detail) (assoc :detail detail)
    (some? description) (assoc :description description)))

(defn make-runtime
  "Returns a minimal runtime map for a new Thing."
  []
  {:status (status :initializing)
   :reported {}
   :desired {}})

(defn make-thing
  "Returns a minimal valid Thing map."
  [thing-id thing-type]
  {:thing-id thing-id
   :thing-type thing-type
   :runtime (make-runtime)})

(defn make-bridge
  "Returns a minimal valid Bridge Thing map."
  [thing-id thing-type]
  (assoc (make-thing thing-id thing-type) :bridge? true))

(defn status-value
  "Returns the status keyword from a Thing or status map. Returns nil for nil input."
  [thing-or-status]
  (if (contains? thing-or-status :runtime)
    (get-in thing-or-status [:runtime :status :value])
    (:value thing-or-status)))

(defn online?
  "Returns true when the Thing status value is :online."
  [thing]
  (= :online (status-value thing)))

(defn merge-reported
  "Returns a full reported snapshot by overlaying partial-reported onto thing's current reported map."
  [thing partial-reported]
  (merge (get-in thing [:runtime :reported] {})
         (or partial-reported {})))

(defn effective-channels
  "Returns the effective channel map with pending desired values overlaying reported values."
  [thing]
  (merge (get-in thing [:runtime :reported] {})
         (update-vals (get-in thing [:runtime :desired] {}) :value)))

(defn effective-channel
  "Returns the effective value for channel-id on thing."
  [thing channel-id]
  (if-let [entry (get-in thing [:runtime :desired channel-id])]
    (:value entry)
    (get-in thing [:runtime :reported channel-id])))
