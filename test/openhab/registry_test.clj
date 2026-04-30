(ns openhab.registry-test
  (:require [clojure.test :refer [deftest is]]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.registry :as registry]
            [openhab.thing :as thing]))

(deftest empty-state-has-primary-collections-and-indexes
  (is (= {:things {}
          :items {}
          :links {}
          :item->links {}
          :channel->links {}
          :bridge->things {}}
         (registry/empty-state))))

(deftest put-thing-maintains-reverse-bridge-index
  (let [state (-> (registry/empty-state)
                  (registry/put-thing (assoc (thing/make-thing "dev-1" :sensor) :bridge-id "bridge-1")))]
    (is (= "bridge-1" (:bridge-id (registry/get-thing state "dev-1"))))
    (is (= #{{:thing-id "dev-1" :thing-type :sensor :runtime {:status {:value :initializing}, :reported {}, :desired {}}, :bridge-id "bridge-1"}}
           (set (registry/children-of state "bridge-1"))))))

(deftest remove-thing-cascades-links-and-indexes
  (let [thing (assoc (thing/make-thing "dev-1" :sensor)
                     :channels {:power {:access :rw :channel-type :boolean}})
        item (item/make-item "Lamp" "Switch")
        lnk (link/make-link "Lamp" "dev-1" :power)
        state (-> (registry/empty-state)
                  (registry/put-thing thing)
                  (registry/put-item item)
                  (registry/put-link lnk))
        removed (registry/remove-thing state "dev-1")]
    (is (nil? (registry/get-thing removed "dev-1")))
    (is (empty? (registry/link-keys-for-item removed "Lamp")))
    (is (empty? (registry/link-keys-for-channel removed "dev-1" :power)))))

(deftest put-link-maintains-item-and-channel-indexes
  (let [state (-> (registry/empty-state)
                  (registry/put-thing (assoc (thing/make-thing "dev-1" :sensor)
                                             :channels {:power {:access :rw :channel-type :boolean}}))
                  (registry/put-item (item/make-item "Lamp" "Switch"))
                  (registry/put-link (link/make-link "Lamp" "dev-1" :power)))]
    (is (= #{["Lamp" "dev-1" :power]} (registry/link-keys-for-item state "Lamp")))
    (is (= #{["Lamp" "dev-1" :power]} (registry/link-keys-for-channel state "dev-1" :power)))))

(deftest remove-item-cascades-links
  (let [state (-> (registry/empty-state)
                  (registry/put-thing (assoc (thing/make-thing "dev-1" :sensor)
                                             :channels {:power {:access :rw :channel-type :boolean}}))
                  (registry/put-item (item/make-item "Lamp" "Switch"))
                  (registry/put-link (link/make-link "Lamp" "dev-1" :power)))
        removed (registry/remove-item state "Lamp")]
    (is (nil? (registry/get-item removed "Lamp")))
    (is (empty? (registry/link-keys-for-channel removed "dev-1" :power)))))

(deftest pure-update-helpers-are-safe-on-missing-ids
  (let [state (registry/empty-state)]
    (is (= state (registry/update-thing state "missing" assoc :foo 1)))
    (is (= state (registry/update-item state "missing" assoc :foo 1)))))
