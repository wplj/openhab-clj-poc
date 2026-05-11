(ns example-addon.api-test
  "Unit tests for the in-memory example appliance API stub."
  (:require [clojure.test :refer [deftest is]]
            [example-addon.api :as api]))

(deftest fetch-returns-normalized-device-state
  (api/reset-state! {"ap-1" {:fan-speed 3
                             :temp 21
                             :mode "auto"}})
  (is (= {:fan-speed 3
          :temp 21
          :mode "auto"}
         (api/fetch! "ap-1"))))

(deftest fetch-throws-when-device-is-missing
  (api/reset-state! {})
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Device not found"
                        (api/fetch! "missing"))))

(deftest send-merges-channel-commands-into-device-state
  (api/reset-state! {"ap-1" {:fan-speed 3
                             :temp 21
                             :mode "auto"}})
  (api/send! nil {:thing-id "ap-1"
                  :commands {:fan-speed 5
                             :mode "manual"}})
  (is (= {:fan-speed 5
          :temp 21
          :mode "manual"}
         (api/fetch! "ap-1"))))

(deftest reset-state-replaces-all-devices
  (api/reset-state! {"ap-1" {:fan-speed 3}})
  (api/reset-state! {"ap-2" {:fan-speed 7}})
  (is (thrown? clojure.lang.ExceptionInfo
               (api/fetch! "ap-1")))
  (is (= {:fan-speed 7}
         (api/fetch! "ap-2"))))
