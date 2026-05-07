(ns example-addon.device
  "Example air-purifier Thing definition and profile codec registration."
  (:require [openhab.profile :as profile]
            [openhab.thing :as thing]))

(def ^:private channel-specs
  {:fan-speed {:access :rw :channel-type :number}
   :temp      {:access :ro :channel-type :number}
   :mode      {:access :rw :channel-type :string}})

(defn make-device
  "Returns a minimal valid air-purifier Thing registered under bridge-id."
  [device-id bridge-id]
  (assoc (thing/make-thing device-id :air-purifier)
         :channels  channel-specs
         :bridge-id bridge-id))

(defn register-codecs
  "Installs :air-purifier channel codecs into profile-registry. Returns the updated registry value."
  [profile-registry]
  (-> profile-registry
      (profile/register-codec [:air-purifier :fan-speed]
                              {:to-state   (fn [v] {:state v :state-type :number})
                               :from-state identity})
      (profile/register-codec [:air-purifier :temp]
                              {:to-state   (fn [v] {:state v :state-type :number})
                               :from-state identity})
      (profile/register-codec [:air-purifier :mode]
                              {:to-state   (fn [v] {:state v :state-type :string})
                               :from-state identity})))
