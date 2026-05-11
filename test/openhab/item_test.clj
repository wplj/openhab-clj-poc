(ns openhab.item-test
  "Tests for Item constructors, specs, and projection-owned state updates."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [openhab.item :as item]))

(deftest make-item-produces-valid-item
  (let [i (item/make-item "Lamp" "Switch")]
    (is (s/valid? ::item/item i))
    (is (= "Lamp" (:item-name i)))
    (is (= #{} (:tags i)))
    (is (nil? (:state i)))))

(deftest make-group-produces-valid-group-item
  (let [g (item/make-group "AllLights")]
    (is (s/valid? ::item/group-item g))
    (is (= "Group" (:item-type g)))
    (is (= #{} (:members g)))))

(deftest set-state-supports-typed-edn-values
  (let [i (item/make-item "FanSpeed" "Number")
        updated (item/set-state i 5 :number java.time.Instant/EPOCH)]
    (is (= 5 (:state updated)))
    (is (= :number (:state-type updated)))
    (is (instance? java.time.Instant (:last-change updated)))))

(deftest set-state-does-not-bump-last-change-when-state-is-unchanged
  (let [i1 (item/set-state (item/make-item "FanSpeed" "Number") 5 :number java.time.Instant/EPOCH)
        i2 (item/set-state i1 5 :number (java.time.Instant/parse "2026-04-29T00:00:01Z"))]
    (is (= (:last-change i1) (:last-change i2)))))

(deftest metadata-entry-still-validates
  (let [i (assoc (item/make-item "Temp" "Number")
                 :metadata {"unit" {:value "C" :config {}}})]
    (is (s/valid? ::item/item i))))
