(ns openhab.thing-test
  "Tests for Thing data shapes and reported/desired effective channel helpers."
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]
            [openhab.thing :as thing]))

(deftest status-map-is-the-runtime-shape
  (is (s/valid? ::thing/status (thing/status :online)))
  (is (s/valid? ::thing/status (thing/status :offline :detail :communication-error)))
  (is (not (s/valid? ::thing/status {:value :connected}))))

(deftest make-thing-produces-valid-runtime-shape
  (let [t (thing/make-thing "dev-1" :fan)]
    (is (s/valid? ::thing/thing t))
    (is (= :initializing (thing/status-value t)))
    (is (= {} (get-in t [:runtime :reported])))
    (is (= {} (get-in t [:runtime :desired])))))

(deftest make-bridge-is-a-thing-without-children-state
  (let [b (thing/make-bridge "bridge-1" :bridge)]
    (is (s/valid? ::thing/bridge b))
    (is (= :initializing (thing/status-value b)))
    (is (nil? (:children b)))))

(deftest effective-channels-overlay-pending-desired-values
  (let [t (-> (thing/make-thing "dev-1" :fan)
              (assoc :channels {:fan-speed {:access :rw :channel-type :number}})
              (assoc-in [:runtime :reported] {:fan-speed 3 :temp 21})
              (assoc-in [:runtime :desired]
                        {:fan-speed {:value 5 :age 1 :command-id "cmd-1"}}))]
    (is (= {:fan-speed 5 :temp 21} (thing/effective-channels t)))
    (is (= 5 (thing/effective-channel t :fan-speed)))))

(deftest merge-reported-builds-a-full-snapshot-from-partial-device-updates
  (let [t (-> (thing/make-thing "dev-1" :fan)
              (assoc-in [:runtime :reported] {:fan-speed 3 :temp 21}))]
    (is (= {:fan-speed 5 :temp 21}
           (thing/merge-reported t {:fan-speed 5})))
    (is (= {:fan-speed 3 :temp 21}
           (thing/merge-reported t nil)))))
