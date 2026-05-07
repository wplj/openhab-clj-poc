(ns openhab.effects-test
  "Tests for post-commit effect dispatch and failure correction."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [openhab.effects :as effects]
            [openhab.events :as events]
            [openhab.item :as item]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.registry :as registry]
            [openhab.thing :as thing]))

(deftest assert-handler-coverage-validates-required-core-effects
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing effect handlers"
                        (effects/assert-handler-coverage! {})))
  (is (= {:openhab/send-command identity}
         (effects/assert-handler-coverage! {:openhab/send-command identity}))))

(deftest assert-handler-coverage-supports-addon-specific-effect-kinds
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing effect handlers"
                        (effects/assert-handler-coverage! {:openhab/send-command identity}
                                                          #{:openhab/send-command
                                                            :addon/custom})))
  (is (= {:openhab/send-command identity
          :addon/custom identity}
         (effects/assert-handler-coverage! {:openhab/send-command identity
                                            :addon/custom identity}
                                           #{:openhab/send-command
                                             :addon/custom}))))

(defn- suppress-expected-effect-warnings [f]
  (let [logger (java.util.logging.Logger/getLogger "openhab.effects")
        level  (.getLevel logger)]
    (try
      (.setLevel logger java.util.logging.Level/SEVERE)
      (f)
      (finally
        (.setLevel logger level)))))

(use-fixtures :once suppress-expected-effect-warnings)

;; --- send-command-handler ---

(defn- make-handler-context []
  (let [profiles (-> (profile/make-registry)
                     (profile/register-codec [:fan :fan-speed]
                                             {:to-state   (fn [v] {:state v :state-type :number})
                                              :from-state identity}))
        reg      (registry/make-registry)
        bus      (events/make-bus 32)
        thing    (-> (thing/make-thing "dev-1" :fan)
                     (assoc :channels {:fan-speed {:access :rw :channel-type :number}})
                     (assoc-in [:runtime :status] (thing/status :online))
                     (assoc-in [:runtime :reported] {:fan-speed 3}))
        itm      (item/make-item "FanSpeed" "Number")
        lnk      (link/make-link "FanSpeed" "dev-1" :fan-speed)]
    (swap! reg #(-> % (registry/put-thing thing) (registry/put-item itm) (registry/put-link lnk)))
    {:registry reg :bus bus :profiles profiles :effect-dispatcher nil}))

(defn- with-desired [ctx command-id]
  (swap! (:registry ctx) assoc-in [:things "dev-1" :runtime :desired :fan-speed]
         {:value 9 :age 0 :command-id command-id})
  ctx)

(defn- make-ctx-with-dispatcher [ctx send-fn]
  (let [disp* (atom nil)
        lazy  (fn [e] (@disp* e))
        ctx'  (assoc ctx :effect-dispatcher lazy)
        hmap  {:openhab/send-command (effects/send-command-handler send-fn)}
        _     (reset! disp* (effects/make-dispatcher hmap ctx'))]
    ctx'))

(defn- test-effect [command-id]
  {:kind :openhab/send-command :thing-id "dev-1" :command-id command-id :commands {:fan-speed 9}})

(deftest send-command-handler-success-leaves-desired-intact
  (let [seen (atom nil)
        ctx  (make-handler-context)
        ctx' (make-ctx-with-dispatcher ctx (fn [_ e] (reset! seen e)))
        _    (with-desired ctx' "cmd-1")
        _    ((get-in ctx' [:effect-dispatcher]) (test-effect "cmd-1"))]
    (is (some? @seen))
    (is (= {:value 9 :age 0 :command-id "cmd-1"}
           (get-in @(:registry ctx') [:things "dev-1" :runtime :desired :fan-speed])))))

(deftest send-command-handler-throwing-send-fn-fires-command-failed
  (let [ctx  (make-handler-context)
        reg  (:registry ctx)
        ctx' (make-ctx-with-dispatcher ctx (fn [_ _] (throw (ex-info "API error" {}))))
        _    (with-desired ctx' "cmd-1")
        _    ((get-in ctx' [:effect-dispatcher]) (test-effect "cmd-1"))]
    (is (empty? (get-in @reg [:things "dev-1" :runtime :desired])))))

(deftest send-command-handler-nil-thing-fires-command-failed
  ;; thing-id not in registry — guard must route to command-failed without calling send-fn
  (let [send-called (atom false)
        ctx         (make-handler-context)
        ctx'        (make-ctx-with-dispatcher ctx (fn [_ _] (reset! send-called true)))
        _           (with-desired ctx' "cmd-1")
        effect      {:kind :openhab/send-command :thing-id "nonexistent"
                     :command-id "cmd-1" :commands {}}
        _           ((get-in ctx' [:effect-dispatcher]) effect)]
    ;; send-fn must not be reached for a nonexistent thing
    (is (false? @send-called))
    ;; command-failed on a nonexistent thing-id is a no-op; real thing's desired is unaffected
    (is (= {:value 9 :age 0 :command-id "cmd-1"}
           (get-in @(:registry ctx') [:things "dev-1" :runtime :desired :fan-speed])))))

