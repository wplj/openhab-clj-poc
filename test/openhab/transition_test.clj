(ns openhab.transition-test
  "Tests for pure domain transitions and their emitted events/effects/results."
  (:require [clojure.test :refer [deftest is]]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.registry :as registry]
            [openhab.thing :as thing]
            [openhab.transition :as transition]))

(defn- profiles []
  (-> (profile/make-registry)
      (profile/register-codec [:fan :fan-speed]
                              {:to-state (fn [v] {:state v :state-type :number})
                               :from-state identity})))

(defn- base-state []
  (let [thing (-> (thing/make-thing "dev-1" :fan)
                  (assoc :channels {:fan-speed {:access :rw :channel-type :number}})
                  (assoc-in [:runtime :status] (thing/status :online))
                  (assoc-in [:runtime :reported] {:fan-speed 3}))]
    (-> (registry/empty-state)
        (registry/put-thing thing)
        (registry/put-item (item/make-item "FanSpeed" "Number"))
        (registry/put-link (link/make-link "FanSpeed" "dev-1" :fan-speed)))))

(deftest channels-reported-corrects-item-when-desired-is-pruned-but-reported-is-unchanged
  (let [profiles (profiles)
        accepted (transition/command-accepted (base-state) profiles "cmd-1"
                                              [{:thing-id "dev-1" :channel-id :fan-speed :value 5}] java.time.Instant/EPOCH)
        state-after-command (:state accepted)
        settled (transition/channels-reported state-after-command profiles "dev-1" {:fan-speed 3} java.time.Instant/EPOCH)]
    (is (= 5 (:state (registry/get-item state-after-command "FanSpeed"))))
    (is (= {} (get-in (:state settled) [:things "dev-1" :runtime :desired])))
    (is (= 3 (:state (registry/get-item (:state settled) "FanSpeed"))))
    (is (some #(= :item/state-changed (:event/type %)) (:events settled)))))

(deftest command-failed-only-clears-the-matching-command-id
  (let [profiles (profiles)
        first-accepted (transition/command-accepted (base-state) profiles "cmd-1"
                                                    [{:thing-id "dev-1" :channel-id :fan-speed :value 5}] java.time.Instant/EPOCH)
        second-accepted (transition/command-accepted (:state first-accepted) profiles "cmd-2"
                                                     [{:thing-id "dev-1" :channel-id :fan-speed :value 6}] java.time.Instant/EPOCH)
        failed-first (transition/command-failed (:state second-accepted) profiles "dev-1" "cmd-1" java.time.Instant/EPOCH)]
    (is (= {:value 6 :age 0 :command-id "cmd-2"}
           (get-in (:state failed-first) [:things "dev-1" :runtime :desired :fan-speed])))
    (is (= 6 (:state (registry/get-item (:state failed-first) "FanSpeed"))))))

(deftest remove-thing-cascades-to-bridge-children
  (let [bridge (thing/make-bridge "bridge-1" :bridge)
        child  (assoc (thing/make-thing "child-1" :sensor) :bridge-id "bridge-1")
        state  (-> (registry/empty-state)
                   (registry/put-thing bridge)
                   (registry/put-thing child))
        result (transition/remove-thing state "bridge-1" java.time.Instant/EPOCH)]
    (is (nil? (registry/get-thing (:state result) "bridge-1")))
    (is (nil? (registry/get-thing (:state result) "child-1")))
    (is (empty? (registry/children-of (:state result) "bridge-1")))
    (is (some #(= "child-1" (:thing-id %)) (:events result)))
    (is (some #(= "bridge-1" (:thing-id %)) (:events result)))))

(deftest remove-child-rejects-child-from-different-bridge
  (let [state  (-> (registry/empty-state)
                   (registry/put-thing (thing/make-bridge "bridge-1" :bridge))
                   (registry/put-thing (thing/make-bridge "bridge-2" :bridge))
                   (registry/put-thing (assoc (thing/make-thing "child-1" :sensor)
                                              :bridge-id "bridge-2")))
        result (transition/remove-child state "bridge-1" "child-1" java.time.Instant/EPOCH)]
    (is (= {:ok false :reason :not-a-child} (:result result)))
    (is (some? (registry/get-thing (:state result) "child-1")))
    (is (= "bridge-2" (:bridge-id (registry/get-thing (:state result) "child-1"))))))

(deftest remove-child-rejects-non-bridge-parent
  (let [state  (-> (registry/empty-state)
                   (registry/put-thing (thing/make-thing "parent-1" :sensor))
                   (registry/put-thing (assoc (thing/make-thing "child-1" :sensor)
                                              :bridge-id "parent-1")))
        result (transition/remove-child state "parent-1" "child-1" java.time.Instant/EPOCH)]
    (is (= {:ok false :reason :not-a-bridge} (:result result)))
    (is (some? (registry/get-thing (:state result) "child-1")))))

(deftest remove-item-removes-item-and-its-links
  (let [result (transition/remove-item (base-state) "FanSpeed")]
    (is (= {:ok true :item-name "FanSpeed"} (:result result)))
    (is (nil? (registry/get-item (:state result) "FanSpeed")))
    (is (nil? (registry/get-link (:state result) "FanSpeed" "dev-1" :fan-speed)))
    (is (empty? (registry/link-keys-for-channel (:state result) "dev-1" :fan-speed)))
    (is (some #(= :item/removed (:event/type %)) (:events result)))))

(deftest set-thing-status-updates-status-and-emits-event
  (let [new-status (thing/status :offline :detail :communication-error)
        result     (transition/set-thing-status (base-state) "dev-1" new-status)]
    (is (= {:ok true :thing-id "dev-1"} (:result result)))
    (is (= new-status (get-in (registry/get-thing (:state result) "dev-1") [:runtime :status])))
    (is (= [{:event/type :thing/status-changed
             :thing-id "dev-1"
             :old-status (thing/status :online)
             :status new-status}]
           (:events result)))))

(deftest add-item-preserves-item-type-on-reregistration
  (let [state  (-> (registry/empty-state)
                   (registry/put-item (item/make-item "Light" "Switch")))
        result (transition/add-item state (item/make-item "Light" "Number"))]
    (is (= "Switch" (:item-type (registry/get-item (:state result) "Light"))))))

(deftest add-item-preserves-projection-fields-on-reregistration
  (let [existing (item/set-state (item/make-item "Light" "Switch") true :boolean java.time.Instant/EPOCH)
        state    (registry/put-item (registry/empty-state) existing)
        result   (transition/add-item state (item/make-item "Light" "Number"))
        stored   (registry/get-item (:state result) "Light")]
    (is (= "Switch" (:item-type stored)))
    (is (= true (:state stored)))
    (is (= :boolean (:state-type stored)))
    (is (some? (:last-change stored)))))

(deftest add-thing-preserves-thing-type-and-channels-on-reregistration
  (let [thing  (assoc (thing/make-thing "dev-1" :fan)
                      :channels {:fan-speed {:access :rw :channel-type :number}})
        state  (registry/put-thing (registry/empty-state) thing)
        result (transition/add-thing state (assoc (thing/make-thing "dev-1" :pump) :channels {}))]
    (is (= :fan (:thing-type (registry/get-thing (:state result) "dev-1"))))
    (is (= {:fan-speed {:access :rw :channel-type :number}}
           (:channels (registry/get-thing (:state result) "dev-1"))))))

(deftest add-link-rejects-unknown-profile
  (let [profiles (profiles)
        state    (-> (registry/empty-state)
                     (registry/put-thing (assoc (thing/make-thing "dev-1" :fan)
                                                :channels {:fan-speed {:access :rw :channel-type :number}}))
                     (registry/put-item (item/make-item "FanSpeed" "Number")))
        result   (transition/add-link state profiles
                                      (link/make-link "FanSpeed" "dev-1" :fan-speed "no-such:profile") java.time.Instant/EPOCH)]
    (is (= {:ok false :reason :unknown-profile} (:result result)))
    (is (empty? (registry/link-keys-for-item (:state result) "FanSpeed")))))

(deftest add-link-rejects-ambiguous-item-links
  (let [profiles (profiles)
        state (-> (registry/empty-state)
                  (registry/put-thing (assoc (thing/make-thing "dev-1" :fan)
                                             :channels {:fan-speed {:access :rw :channel-type :number}}))
                  (registry/put-thing (assoc (thing/make-thing "dev-2" :fan)
                                             :channels {:fan-speed {:access :rw :channel-type :number}}))
                  (registry/put-item (item/make-item "FanSpeed" "Number"))
                  (registry/put-link (link/make-link "FanSpeed" "dev-1" :fan-speed)))
        result (transition/add-link state profiles (link/make-link "FanSpeed" "dev-2" :fan-speed) java.time.Instant/EPOCH)]
    (is (= {:ok false :reason :ambiguous-item-links} (:result result)))))

(deftest add-thing-rejects-missing-bridge-parent
  (let [result (transition/add-thing (registry/empty-state)
                                     (assoc (thing/make-thing "child-1" :sensor)
                                            :bridge-id "missing-bridge"))]
    (is (= {:ok false :reason :bridge-not-found} (:result result)))
    (is (nil? (registry/get-thing (:state result) "child-1")))))

(deftest add-thing-rejects-non-bridge-parent
  (let [state  (registry/put-thing (registry/empty-state)
                                   (thing/make-thing "parent-1" :sensor))
        result (transition/add-thing state
                                     (assoc (thing/make-thing "child-1" :sensor)
                                            :bridge-id "parent-1"))]
    (is (= {:ok false :reason :not-a-bridge} (:result result)))
    (is (nil? (registry/get-thing (:state result) "child-1")))))

(deftest add-thing-under-offline-bridge-inherits-offline-status
  (let [state  (registry/put-thing (registry/empty-state)
                                   (assoc-in (thing/make-bridge "bridge-1" :bridge)
                                             [:runtime :status]
                                             (thing/status :offline)))
        result (transition/add-thing state
                                     (assoc (thing/make-thing "child-1" :sensor)
                                            :bridge-id "bridge-1"))]
    (is (= {:ok true :thing-id "child-1"} (:result result)))
    (is (= :offline (thing/status-value (registry/get-thing (:state result) "child-1"))))))

(deftest reparenting-under-offline-bridge-cascades-offline-through-subtree
  (let [state  (-> (registry/empty-state)
                   (registry/put-thing (assoc-in (thing/make-bridge "root-bridge" :bridge)
                                                 [:runtime :status]
                                                 (thing/status :offline)))
                   (registry/put-thing (assoc-in (thing/make-bridge "child-bridge" :bridge)
                                                 [:runtime :status]
                                                 (thing/status :online)))
                   (registry/put-thing (-> (thing/make-thing "grandchild" :sensor)
                                           (assoc :bridge-id "child-bridge")
                                           (assoc-in [:runtime :status] (thing/status :online)))))
        result (transition/add-thing state
                                     (assoc (thing/make-bridge "child-bridge" :bridge)
                                            :bridge-id "root-bridge"))]
    (is (= {:ok true :thing-id "child-bridge"} (:result result)))
    (is (= :offline (thing/status-value (registry/get-thing (:state result) "child-bridge"))))
    (is (= :offline (thing/status-value (registry/get-thing (:state result) "grandchild"))))))

(deftest offline-bridge-status-cascades-through-nested-children
  (let [state  (-> (registry/empty-state)
                   (registry/put-thing (assoc-in (thing/make-bridge "root-bridge" :bridge)
                                                 [:runtime :status]
                                                 (thing/status :online)))
                   (registry/put-thing (-> (thing/make-bridge "child-bridge" :bridge)
                                           (assoc :bridge-id "root-bridge")
                                           (assoc-in [:runtime :status] (thing/status :online))))
                   (registry/put-thing (-> (thing/make-thing "grandchild" :sensor)
                                           (assoc :bridge-id "child-bridge")
                                           (assoc-in [:runtime :status] (thing/status :online)))))
        result (transition/set-bridge-status state "root-bridge" (thing/status :offline))]
    (is (= :offline (thing/status-value (registry/get-thing (:state result) "root-bridge"))))
    (is (= :offline (thing/status-value (registry/get-thing (:state result) "child-bridge"))))
    (is (= :offline (thing/status-value (registry/get-thing (:state result) "grandchild"))))))

(deftest set-bridge-status-rejects-non-bridge-thing
  (let [state  (registry/put-thing (registry/empty-state)
                                   (assoc-in (thing/make-thing "dev-1" :sensor)
                                             [:runtime :status]
                                             (thing/status :online)))
        result (transition/set-bridge-status state "dev-1" (thing/status :offline))]
    (is (= {:ok false :reason :not-a-bridge} (:result result)))
    (is (= :online (thing/status-value (registry/get-thing (:state result) "dev-1"))))))

(deftest add-thing-rejects-self-parenting-bridge
  (let [result (transition/add-thing (registry/empty-state)
                                     (assoc (thing/make-bridge "bridge-1" :bridge)
                                            :bridge-id "bridge-1"))]
    (is (= {:ok false :reason :bridge-cycle} (:result result)))
    (is (nil? (registry/get-thing (:state result) "bridge-1")))))

(deftest add-thing-rejects-bridge-cycles-on-reparent
  (let [state  (-> (registry/empty-state)
                   (registry/put-thing (thing/make-bridge "bridge-1" :bridge))
                   (registry/put-thing (assoc (thing/make-bridge "bridge-2" :bridge)
                                              :bridge-id "bridge-1")))
        result (transition/add-thing state
                                     (assoc (thing/make-bridge "bridge-1" :bridge)
                                            :bridge-id "bridge-2"))]
    (is (= {:ok false :reason :bridge-cycle} (:result result)))
    (is (nil? (:bridge-id (registry/get-thing (:state result) "bridge-1"))))
    (is (= "bridge-1" (:bridge-id (registry/get-thing (:state result) "bridge-2"))))))

(deftest add-thing-reregistration-under-offline-bridge-emits-status-event
  ;; Existing online child re-registered under an offline bridge must go offline
  ;; and emit a status-changed event (not silently suppressed).
  (let [bridge (assoc-in (thing/make-bridge "bridge-1" :bridge)
                         [:runtime :status] (thing/status :offline))
        child  (assoc-in (assoc (thing/make-thing "child-1" :sensor)
                                :bridge-id "bridge-1")
                         [:runtime :status] (thing/status :online))
        state  (-> (registry/empty-state)
                   (registry/put-thing bridge)
                   (registry/put-thing child))
        result (transition/add-thing state child)]
    (is (= :offline (thing/status-value (registry/get-thing (:state result) "child-1"))))
    (is (some #(= :thing/status-changed (:event/type %)) (:events result)))))

(deftest channels-reported-throws-on-nil-reported
  (let [profiles (profiles)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (transition/channels-reported (base-state) profiles "dev-1" nil java.time.Instant/EPOCH)))))

(deftest channels-reported-expires-desired-after-threshold-polls
  ;; Desired entry ages each poll where the channel is not in the snapshot.
  ;; At age == expiry-threshold it is dropped (threshold=2 here for brevity).
  (let [profiles (profiles)
        after-cmd (:state (transition/command-accepted
                            (base-state) profiles "cmd-1"
                            [{:thing-id "dev-1" :channel-id :fan-speed :value 5}] java.time.Instant/EPOCH))
        after-poll-1 (transition/channels-reported after-cmd profiles "dev-1" {} 2 java.time.Instant/EPOCH)
        after-poll-2 (transition/channels-reported (:state after-poll-1) profiles "dev-1" {} 2 java.time.Instant/EPOCH)]
    ;; age=1 after first poll — not yet expired
    (is (= 1 (get-in (:state after-poll-1) [:things "dev-1" :runtime :desired :fan-speed :age])))
    ;; age=2 triggers expiry — desired cleared
    (is (empty? (get-in (:state after-poll-2) [:things "dev-1" :runtime :desired])))))

(deftest channels-reported-settles-reported-channels-and-ages-unreported-desired
  ;; Full snapshot intentionally omits mode: fan-speed desired is pruned; mode desired ages normally.
  (let [thing    (-> (thing/make-thing "dev-1" :fan)
                     (assoc :channels {:fan-speed {:access :rw :channel-type :number}
                                       :mode      {:access :rw :channel-type :string}})
                     (assoc-in [:runtime :status] (thing/status :online))
                     (assoc-in [:runtime :reported] {:fan-speed 3 :mode "auto"}))
        state    (-> (registry/empty-state)
                     (registry/put-thing thing)
                     (registry/put-item (item/make-item "FanSpeed" "Number"))
                     (registry/put-link (link/make-link "FanSpeed" "dev-1" :fan-speed)))
        profiles (profiles)
        ;; Two simultaneous commands: fan-speed and mode
        after-cmd (:state (transition/command-accepted
                            state profiles "cmd-1"
                            [{:thing-id "dev-1" :channel-id :fan-speed :value 9}
                             {:thing-id "dev-1" :channel-id :mode :value "turbo"}] java.time.Instant/EPOCH))
        ;; Snapshot contains only fan-speed (settles it); mode is absent and therefore ages
        result (transition/channels-reported after-cmd profiles "dev-1" {:fan-speed 9} 3 java.time.Instant/EPOCH)]
    ;; fan-speed desired pruned (channel appeared in poll)
    (is (nil? (get-in (:state result) [:things "dev-1" :runtime :desired :fan-speed])))
    ;; mode desired aged from 0 to 1
    (is (= 1 (get-in (:state result) [:things "dev-1" :runtime :desired :mode :age])))))

(deftest remove-link-returns-link-not-found-for-unknown-link
  (let [result (transition/remove-link (registry/empty-state) "NoItem" "dev-1" :fan-speed java.time.Instant/EPOCH)]
    (is (= {:ok false :reason :link-not-found} (:result result)))))

(deftest remove-link-clears-projected-item-state-when-last-link-removed
  ;; Pre-populate item with projected state to confirm clearing.
  ;; Removing the item's only link must nil out state, emit :link/removed and :item/state-changed.
  (let [state  (registry/put-item (base-state)
                                  (item/set-state (item/make-item "FanSpeed" "Number") 30 :number java.time.Instant/EPOCH))
        result (transition/remove-link state "FanSpeed" "dev-1" :fan-speed java.time.Instant/EPOCH)]
    (is (= {:ok true :link-key ["FanSpeed" "dev-1" :fan-speed]} (:result result)))
    (is (nil? (registry/get-link (:state result) "FanSpeed" "dev-1" :fan-speed)))
    (is (nil? (:state (registry/get-item (:state result) "FanSpeed"))))
    (is (some #(= :link/removed (:event/type %)) (:events result)))
    (is (some #(= :item/state-changed (:event/type %)) (:events result)))))

(deftest remove-link-preserves-item-state-when-item-still-linked
  ;; Bypass add-link validation to force a two-link item, then remove one link.
  ;; Item still has a source: state must not be cleared, no :item/state-changed emitted.
  (let [state  (-> (base-state)
                   (registry/put-thing (assoc (thing/make-thing "dev-2" :fan)
                                              :channels {:fan-speed {:access :rw :channel-type :number}}))
                   (registry/put-link (link/make-link "FanSpeed" "dev-2" :fan-speed))
                   (registry/put-item (item/set-state (item/make-item "FanSpeed" "Number") 30 :number java.time.Instant/EPOCH)))
        result (transition/remove-link state "FanSpeed" "dev-1" :fan-speed java.time.Instant/EPOCH)]
    (is (= {:ok true :link-key ["FanSpeed" "dev-1" :fan-speed]} (:result result)))
    (is (some? (registry/get-link (:state result) "FanSpeed" "dev-2" :fan-speed)))
    (is (= 30 (:state (registry/get-item (:state result) "FanSpeed"))))
    (is (some #(= :link/removed (:event/type %)) (:events result)))
    (is (not (some #(= :item/state-changed (:event/type %)) (:events result))))))

(deftest remove-item-emits-link-removed-events-for-removed-links
  (let [result (transition/remove-item (base-state) "FanSpeed")]
    (is (some #(= :link/removed (:event/type %)) (:events result)))
    (is (some #(= ["FanSpeed" "dev-1" :fan-speed] (:link-key %)) (:events result)))))

(deftest remove-thing-emits-link-removed-events-for-removed-links
  (let [result (transition/remove-thing (base-state) "dev-1" java.time.Instant/EPOCH)]
    (is (= {:ok true :thing-id "dev-1"} (:result result)))
    (is (nil? (registry/get-link (:state result) "FanSpeed" "dev-1" :fan-speed)))
    (is (some #(= :link/removed (:event/type %)) (:events result)))
    (is (some #(= ["FanSpeed" "dev-1" :fan-speed] (:link-key %)) (:events result)))))

(deftest add-link-is-idempotent-for-identical-link
  (let [lnk    (link/make-link "FanSpeed" "dev-1" :fan-speed)
        result (transition/add-link (base-state) (profiles) lnk java.time.Instant/EPOCH)]
    (is (= {:ok true :link-key ["FanSpeed" "dev-1" :fan-speed]} (:result result)))
    (is (empty? (:events result)))))

(deftest item-command-accepted-resolves-link-and-profile-inside-transition
  (let [result (transition/item-command-accepted (base-state) (profiles) "cmd-1" "FanSpeed" 8 java.time.Instant/EPOCH)]
    (is (= true (get-in result [:result :ok])))
    (is (= {:value 8 :age 0 :command-id "cmd-1"}
           (get-in (:state result) [:things "dev-1" :runtime :desired :fan-speed])))
    (is (= {:fan-speed 8} (:commands (first (:effects result)))))))

(deftest item-command-accepted-rejects-unlinked-items
  (let [state  (-> (registry/empty-state)
                   (registry/put-item (item/make-item "FanSpeed" "Number")))
        result (transition/item-command-accepted state (profiles) "cmd-1" "FanSpeed" 8 java.time.Instant/EPOCH)]
    (is (= {:ok false :reason :item-not-linked} (:result result)))
    (is (empty? (:effects result)))))

(deftest item-command-accepted-rejects-offline-thing
  (let [state  (assoc-in (base-state) [:things "dev-1" :runtime :status] (thing/status :offline))
        result (transition/item-command-accepted state (profiles) "cmd-1" "FanSpeed" 8 java.time.Instant/EPOCH)]
    (is (= {:ok false :reason :thing-offline} (:result result)))
    (is (empty? (:effects result)))))

(deftest item-command-accepted-rejects-read-only-channel-before-encoding
  (let [state    (-> (registry/empty-state)
                     (registry/put-thing (-> (thing/make-thing "dev-1" :fan)
                                             (assoc :channels {:temp {:access :ro :channel-type :number}})
                                             (assoc-in [:runtime :status] (thing/status :online))))
                     (registry/put-item (item/make-item "Temp" "Number"))
                     (registry/put-link (link/make-link "Temp" "dev-1" :temp)))
        profiles (profile/register-codec (profiles)
                                         [:fan :temp]
                                         {:from-state (fn [_]
                                                        (throw (ex-info "encoder should not run" {})))})
        result   (transition/item-command-accepted state profiles "cmd-1" "Temp" 99 java.time.Instant/EPOCH)]
    (is (= {:ok false :reason :channel-read-only} (:result result)))
    (is (empty? (:effects result)))))

(deftest command-failed-emits-command-failed-event
  (let [accepted (transition/command-accepted (base-state) (profiles) "cmd-1"
                                              [{:thing-id "dev-1" :channel-id :fan-speed :value 5}]
                                              java.time.Instant/EPOCH)
        failed   (transition/command-failed (:state accepted) (profiles) "dev-1" "cmd-1" java.time.Instant/EPOCH)]
    (is (some #(= {:event/type :command/failed
                   :thing-id "dev-1"
                   :command-id "cmd-1"
                   :channel-ids #{:fan-speed}}
                 %)
              (:events failed)))))

(deftest command-failed-emits-no-event-when-command-id-is-stale
  ;; cmd-2 overwrites cmd-1 for the same channel; failing cmd-1 clears nothing and emits no event.
  (let [profiles (profiles)
        first-accepted  (transition/command-accepted (base-state) profiles "cmd-1"
                                                     [{:thing-id "dev-1" :channel-id :fan-speed :value 5}]
                                                     java.time.Instant/EPOCH)
        second-accepted (transition/command-accepted (:state first-accepted) profiles "cmd-2"
                                                     [{:thing-id "dev-1" :channel-id :fan-speed :value 6}]
                                                     java.time.Instant/EPOCH)
        failed-stale    (transition/command-failed (:state second-accepted) profiles "dev-1" "cmd-1" java.time.Instant/EPOCH)]
    (is (not (some #(= :command/failed (:event/type %)) (:events failed-stale))))))

(deftest channels-reported-rejects-undeclared-channels
  (let [result (transition/channels-reported (base-state) (profiles) "dev-1" {:fan-speed 4 :ghost 99} java.time.Instant/EPOCH)]
    (is (= {:ok false :reason :unknown-reported-channel :channel-id :ghost} (:result result)))
    (is (= {:fan-speed 3}
           (get-in (:state result) [:things "dev-1" :runtime :reported])))))
