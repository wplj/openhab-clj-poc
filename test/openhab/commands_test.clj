(ns openhab.commands-test
  "Tests for public command dispatch and command-context validation."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [openhab.commands :as commands]
            [openhab.effects :as effects]
            [openhab.events :as events]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.registry :as registry]
            [openhab.runtime :as runtime]
            [openhab.thing :as thing]
            [openhab.transition :as transition]))

(defn- base-profiles []
  (-> (profile/make-registry)
      (profile/register-codec [:fan :fan-speed]
                              {:to-state (fn [v] {:state v :state-type :number})
                               :from-state identity})
      (profile/register-codec [:fan :temp]
                              {:to-state (fn [v] {:state v :state-type :number})
                               :from-state identity})))

(defn- make-context
  ([send-fn]
   (make-context send-fn (base-profiles)))
  ([send-fn profiles]
   (let [reg (registry/make-registry)
         bus (events/make-bus 64)
         handler-map {:openhab/send-command (effects/send-command-handler send-fn)}]
     (letfn [(dispatch-effect! [effect]
               (effects/dispatch! handler-map
                                  {:registry reg
                                   :bus bus
                                   :effect-dispatcher dispatch-effect!
                                   :profiles profiles}
                                  effect))]
       {:registry reg
        :bus bus
        :profiles profiles
        :effect-dispatcher dispatch-effect!}))))

(defn- apply! [context transition-fn & args]
  (apply runtime/apply-transition!
         (:registry context)
         (:bus context)
         (:effect-dispatcher context)
         transition-fn
         args))

(defn- suppress-expected-effect-warnings [f]
  (let [logger (java.util.logging.Logger/getLogger "openhab.effects")
        level  (.getLevel logger)]
    (try
      (.setLevel logger java.util.logging.Level/SEVERE)
      (f)
      (finally
        (.setLevel logger level)))))

(use-fixtures :once suppress-expected-effect-warnings)

(defn- install-online-thing! [context]
  (apply! context
          transition/add-thing
          (-> (thing/make-thing "dev-1" :fan)
              (assoc :channels {:fan-speed {:access :rw :channel-type :number}
                                :temp {:access :ro :channel-type :number}})
              (assoc-in [:runtime :status] (thing/status :online))
              (assoc-in [:runtime :reported] {:fan-speed 3 :temp 21}))))

(defn- install-item-link! [context]
  (apply! context transition/add-item (item/make-item "FanSpeed" "Number"))
  (apply! context transition/add-link (:profiles context) (link/make-link "FanSpeed" "dev-1" :fan-speed) java.time.Instant/EPOCH))

(deftest item-command-returns-item-not-found
  (let [context (make-context (fn [_ _] true))]
    (install-online-thing! context)
    (is (= {:ok false :reason :item-not-found}
           (commands/dispatch! context {:item-name "NoSuchItem" :value 9})))))

(deftest item-command-returns-item-not-linked
  (let [context (make-context (fn [_ _] true))]
    (install-online-thing! context)
    (apply! context transition/add-item (item/make-item "FanSpeed" "Number"))
    (is (= {:ok false :reason :item-not-linked}
           (commands/dispatch! context {:item-name "FanSpeed" :value 9})))))

(deftest direct-command-rejects-offline-things
  (let [context (make-context (fn [_ _] true))]
    (apply! context transition/add-thing
            (assoc (thing/make-thing "dev-1" :fan)
                   :channels {:fan-speed {:access :rw :channel-type :number}}))
    (is (= {:ok false :reason :thing-offline}
           (commands/dispatch! context {:thing-id "dev-1" :channel-id :fan-speed :value 9})))))

(deftest accepted-command-updates-desired-and-projects-item-optimistically
  (let [seen-effect (atom nil)
        context (make-context (fn [_ effect] (reset! seen-effect effect) true))]
    (install-online-thing! context)
    (install-item-link! context)
    (is (= true (:ok (commands/dispatch! context {:item-name "FanSpeed" :value 9}))))
    (is (= 9 (:state (registry/get-item (:registry context) "FanSpeed"))))
    (is (= {:value 9 :age 0 :command-id (:command-id @seen-effect)}
           (get-in (registry/get-thing (:registry context) "dev-1") [:runtime :desired :fan-speed])))
    (is (= {:fan-speed 9} (:commands @seen-effect)))))

(deftest send-failure-clears-desired-and-corrects-item-state
  (let [context (make-context (fn [_ _] (throw (ex-info "send failed" {}))))]
    (install-online-thing! context)
    (install-item-link! context)
    (is (= true (:ok (commands/dispatch! context {:item-name "FanSpeed" :value 9}))))
    (is (= {} (get-in (registry/get-thing (:registry context) "dev-1") [:runtime :desired])))
    (is (= 3 (:state (registry/get-item (:registry context) "FanSpeed"))))))

(deftest item-command-uses-profile-encoding
  (let [profiles (-> (profile/make-registry)
                     (profile/register-codec [:fan :fan-speed]
                                             {:to-state (fn [v] {:state (/ v 2) :state-type :number})
                                              :from-state (fn [v] (* 2 v))}))
        seen-effect (atom nil)
        context (make-context (fn [_ effect] (reset! seen-effect effect) true) profiles)]
    (install-online-thing! context)
    (install-item-link! context)
    (is (= true (:ok (commands/dispatch! context {:item-name "FanSpeed" :value 9}))))
    (is (= {:fan-speed 18} (:commands @seen-effect)))
    (is (= 9 (:state (registry/get-item (:registry context) "FanSpeed"))))))

(deftest missing-effect-dispatcher-is-a-programming-error
  (let [setup-context (make-context (fn [_ _] true))
        context       (dissoc setup-context :effect-dispatcher)]
    (install-online-thing! setup-context)
    (install-item-link! setup-context)
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Missing :effect-dispatcher"
                          (commands/dispatch! context {:item-name "FanSpeed" :value 9})))))

