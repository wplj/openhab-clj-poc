(ns openhab.item
  "Item data shape and item-local state helpers.

   Items are cached views over linked Thing channels. Projection owns their
   runtime state; structural item registration owns identity and metadata."
  (:require [clojure.spec.alpha :as s]))

(s/def ::item-name (s/and string? seq))
(s/def ::item-type (s/and string? seq))
(s/def ::label string?)
(s/def ::category string?)
(s/def ::tags (s/coll-of string? :kind set? :into #{}))
(s/def ::group-names (s/coll-of ::item-name :kind set? :into #{}))
(s/def ::state any?)
(s/def ::state-type keyword?)
(s/def ::last-change (partial instance? java.time.Instant))
(s/def ::value string?)
(s/def ::config map?)
(s/def ::metadata-entry
  (s/keys :req-un [::value]
          :opt-un [::config]))
(s/def ::metadata (s/map-of string? ::metadata-entry))

(s/def ::item
  (s/keys :req-un [::item-name ::item-type]
          :opt-un [::label ::category ::tags ::group-names
                   ::state ::state-type ::last-change ::metadata]))

(s/def ::group-function keyword?)
(s/def ::members (s/coll-of ::item-name :kind set? :into #{}))
(s/def ::group-item
  (s/merge ::item
           (s/keys :opt-un [::group-function ::members])))

(defn make-item
  "Returns a minimal valid Item map."
  [item-name item-type]
  {:item-name item-name
   :item-type item-type
   :tags #{}
   :group-names #{}})

(defn make-group
  "Returns a minimal valid Group Item map."
  [item-name]
  (assoc (make-item item-name "Group") :members #{}))

(defn set-state
  "Returns item with projected state updated. :last-change only moves when state or state-type changes.
   changed-at is supplied by the runtime edge so projection and transition functions remain pure."
  [item state state-type changed-at]
  (let [changed? (or (not= state (:state item))
                     (not= state-type (:state-type item)))
        next-item (cond-> (assoc item :state state)
                    (some? state-type) (assoc :state-type state-type)
                    (nil? state-type) (dissoc :state-type))]
    (cond-> next-item
      changed? (assoc :last-change changed-at))))
