(ns openhab.projection-test
  "Tests for channel-to-Item projection and multi-link guardrails."
  (:require [clojure.test :refer [deftest is]]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.projection :as projection]
            [openhab.registry :as registry]
            [openhab.thing :as thing]))

(defn- profiles []
  (-> (profile/make-registry)
      (profile/register-codec [:fan :fan-speed]
                              {:to-state   (fn [v] {:state (* v 10) :state-type :number})
                               :from-state identity})))

(defn- base-state []
  (let [t (-> (thing/make-thing "dev-1" :fan)
              (assoc :channels {:fan-speed {:access :rw :channel-type :number}
                                :temp      {:access :ro :channel-type :number}})
              (assoc-in [:runtime :reported] {:fan-speed 3 :temp 21})
              (assoc-in [:runtime :status] (thing/status :online)))]
    (-> (registry/empty-state)
        (registry/put-thing t)
        (registry/put-item (item/make-item "FanSpeed" "Number"))
        (registry/put-item (item/make-item "Temp" "Number"))
        (registry/put-link (link/make-link "FanSpeed" "dev-1" :fan-speed))
        (registry/put-link (link/make-link "Temp" "dev-1" :temp)))))

;; --- affected-item-names ---

(deftest affected-item-names-returns-linked-item-names
  (let [state (base-state)]
    (is (= #{"FanSpeed"} (projection/affected-item-names state "dev-1" [:fan-speed])))
    (is (= #{"Temp"} (projection/affected-item-names state "dev-1" [:temp])))
    (is (= #{"FanSpeed" "Temp"} (projection/affected-item-names state "dev-1" [:fan-speed :temp])))))

(deftest affected-item-names-returns-empty-for-unlinked-channel
  (is (empty? (projection/affected-item-names (base-state) "dev-1" [:mode]))))

(deftest affected-item-names-returns-empty-for-unknown-thing
  (is (empty? (projection/affected-item-names (registry/empty-state) "no-such" [:fan-speed]))))

(deftest affected-item-names-returns-a-set-not-a-seq
  (is (set? (projection/affected-item-names (base-state) "dev-1" [:fan-speed]))))

;; --- project-item-state ---

(deftest project-item-state-projects-via-codec
  ;; fan-speed codec multiplies channel value by 10
  (let [result (projection/project-item-state (base-state) (profiles) "FanSpeed")]
    (is (some? result))
    (is (= 30 (:state result)))
    (is (= :number (:state-type result)))))

(deftest project-item-state-uses-system-default-when-no-codec
  ;; temp has no registered codec; system:default passes value through
  (let [result (projection/project-item-state (base-state) (profiles) "Temp")]
    (is (= 21 (:state result)))
    (is (= :number (:state-type result)))))

(deftest project-item-state-returns-nil-for-unlinked-item
  (let [state (-> (registry/empty-state)
                  (registry/put-item (item/make-item "Orphan" "Number")))]
    (is (nil? (projection/project-item-state state (profiles) "Orphan")))))

(deftest project-item-state-throws-on-multi-link
  ;; Bypass add-link validation to force a multi-link state
  (let [state (-> (base-state)
                  (registry/put-thing (assoc (thing/make-thing "dev-2" :fan)
                                             :channels {:fan-speed {:access :rw :channel-type :number}}))
                  (registry/put-link (link/make-link "FanSpeed" "dev-2" :fan-speed)))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (projection/project-item-state state (profiles) "FanSpeed")))))

;; --- project-items ---

(deftest project-items-updates-item-state-and-records-changes
  (let [result (projection/project-items (base-state) (profiles) ["FanSpeed"] java.time.Instant/EPOCH)]
    (is (= 30 (:state (registry/get-item (:state result) "FanSpeed"))))
    (is (= 1 (count (:changes result))))
    (is (= "FanSpeed" (:item-name (first (:changes result)))))))

(deftest project-items-skips-unchanged-items
  ;; Pre-populate item state to match what the codec would produce; expect no changes.
  (let [state (registry/put-item (base-state)
                                 (item/set-state (item/make-item "FanSpeed" "Number") 30 :number java.time.Instant/EPOCH))
        result (projection/project-items state (profiles) ["FanSpeed"] java.time.Instant/EPOCH)]
    (is (empty? (:changes result)))))

(deftest project-items-processes-multiple-items-deterministically
  (let [result (projection/project-items (base-state) (profiles) ["Temp" "FanSpeed"] java.time.Instant/EPOCH)]
    (is (= 2 (count (:changes result))))
    (is (= 30 (:state (registry/get-item (:state result) "FanSpeed"))))
    (is (= 21 (:state (registry/get-item (:state result) "Temp"))))))

(deftest project-items-skips-missing-items
  (let [result (projection/project-items (base-state) (profiles) ["FanSpeed" "NoSuchItem"] java.time.Instant/EPOCH)]
    (is (= 1 (count (:changes result))))))

