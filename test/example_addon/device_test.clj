(ns example-addon.device-test
  "Unit tests for the example air-purifier Thing definition and codecs."
  (:require [clojure.test :refer [deftest is]]
            [example-addon.device :as device]
            [openhab.link :as link]
            [openhab.profile :as profile]
            [openhab.thing :as thing]))

(deftest make-device-builds-air-purifier-child-thing
  (let [dev (device/make-device "ap-1" "bridge-1")]
    (is (= "ap-1" (:thing-id dev)))
    (is (= :air-purifier (:thing-type dev)))
    (is (= "bridge-1" (:bridge-id dev)))
    (is (= (thing/make-runtime) (:runtime dev)))
    (is (= {:fan-speed {:access :rw :channel-type :number}
            :temp {:access :ro :channel-type :number}
            :mode {:access :rw :channel-type :string}}
           (:channels dev)))))

(deftest register-codecs-projects-air-purifier-channel-values
  (let [profiles (device/register-codecs (profile/make-registry))
        thing (device/make-device "ap-1" "bridge-1")
        item {:item-name "AP_FanSpeed"}]
    (is (= {:state 5 :state-type :number}
           (profile/project-state profiles
                                  {:thing thing
                                   :item item
                                   :link (link/make-link "AP_FanSpeed" "ap-1" :fan-speed)
                                   :channel-id :fan-speed
                                   :channel-value 5})))
    (is (= {:state "auto" :state-type :string}
           (profile/project-state profiles
                                  {:thing thing
                                   :item {:item-name "AP_Mode"}
                                   :link (link/make-link "AP_Mode" "ap-1" :mode)
                                   :channel-id :mode
                                   :channel-value "auto"})))))

(deftest register-codecs-encodes-item-commands-as-channel-values
  (let [profiles (device/register-codecs (profile/make-registry))
        thing (device/make-device "ap-1" "bridge-1")]
    (is (= 5
           (profile/encode-command profiles
                                   {:thing thing
                                    :item {:item-name "AP_FanSpeed"}
                                    :link (link/make-link "AP_FanSpeed" "ap-1" :fan-speed)
                                    :channel-id :fan-speed
                                    :item-value 5})))
    (is (= "manual"
           (profile/encode-command profiles
                                   {:thing thing
                                    :item {:item-name "AP_Mode"}
                                    :link (link/make-link "AP_Mode" "ap-1" :mode)
                                    :channel-id :mode
                                    :item-value "manual"})))))
