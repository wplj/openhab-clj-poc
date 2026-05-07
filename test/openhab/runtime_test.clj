(ns openhab.runtime-test
  "Tests for atomic transition application, event publication, and effect dispatch."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [openhab.runtime :as runtime])
  (:import [java.util.logging Level Logger]))

(defn- with-runtime-logs-suppressed [f]
  (let [logger (Logger/getLogger "openhab.runtime")
        old-level (.getLevel logger)]
    (try
      (.setLevel logger Level/OFF)
      (f)
      (finally
        (.setLevel logger old-level)))))

(use-fixtures :each with-runtime-logs-suppressed)

(defn- effectful-transition [state]
  {:state (assoc state :applied true)
   :events []
   :effects [{:kind :test/failing}]})

(defn- nested-effectful-transition [state]
  {:state (assoc state :nested true)
   :events []
   :effects [{:kind :test/recursive}]})

(deftest apply-transition-catches-effect-dispatch-failures
  (let [registry (atom {})
        calls    (atom 0)
        result   (runtime/apply-transition! registry
                                            nil
                                            (fn [_]
                                              (swap! calls inc)
                                              (throw (ex-info "boom" {})))
                                            effectful-transition)]
    (is (= {:applied true} @registry))
    (is (= 1 @calls))
    (is (= {:applied true} (:state result)))))

(deftest apply-transition-requires-dispatcher-for-effectful-transitions
  (let [registry (atom {})
        ex       (try
                   (runtime/apply-transition! registry nil nil effectful-transition)
                   nil
                   (catch clojure.lang.ExceptionInfo ex
                     ex))]
    (is (= {:applied true} @registry))
    (is (= "Missing :effect-dispatcher for effectful transition" (.getMessage ex)))
    (is (= {:effect-count 1} (ex-data ex)))))

(deftest effect-dispatch-rejects-nested-effectful-transitions-before-commit
  (let [registry (atom {})
        calls (atom 0)
        result (runtime/apply-transition! registry
                                          nil
                                          (fn [_]
                                            (swap! calls inc)
                                            (runtime/apply-transition! registry
                                                                       nil
                                                                       (fn [_] (swap! calls inc))
                                                                       nested-effectful-transition))
                                          effectful-transition)]
    (is (= {:applied true} @registry))
    (is (= 1 @calls))
    (is (= {:applied true} (:state result)))))
