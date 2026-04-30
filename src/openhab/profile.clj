(ns openhab.profile)

(defn infer-state-type
  "Infers a coarse state type keyword for internal item state values."
  [value]
  (cond
    (keyword? value) :keyword
    (string? value) :string
    (number? value) :number
    (boolean? value) :boolean
    (nil? value) :nil
    :else :edn))

(defn- normalize-projected-state [value]
  (if (and (map? value) (contains? value :state))
    (cond-> value
      (not (contains? value :state-type))
      (assoc :state-type (infer-state-type (:state value))))
    {:state value
     :state-type (infer-state-type value)}))

(defn- system-default-project-state [{:keys [profile-registry thing channel-id channel-value]}]
  (if-let [codec (get-in profile-registry [:codecs [(:thing-type thing) channel-id]])]
    (normalize-projected-state
     (if-let [to-state (:to-state codec)]
       (to-state channel-value)
       channel-value))
    {:state channel-value
     :state-type (infer-state-type channel-value)}))

(defn- system-default-encode-command [{:keys [profile-registry thing channel-id item-value]}]
  (if-let [codec (get-in profile-registry [:codecs [(:thing-type thing) channel-id]])]
    (if-let [from-state (:from-state codec)]
      (from-state item-value)
      item-value)
    item-value))

(defn- valid-profile? [profile]
  (and (map? profile)
       (ifn? (:project-state profile))
       (ifn? (:encode-command profile))))

(defn- valid-codec? [codec]
  (and (map? codec)
       (or (nil? (:to-state codec)) (ifn? (:to-state codec)))
       (or (nil? (:from-state codec)) (ifn? (:from-state codec)))))

(defn make-registry
  "Returns a new profile/codec registry value with the system default profile installed."
  []
  {:profiles {"system:default"
              {:project-state system-default-project-state
               :encode-command system-default-encode-command}}
   :codecs {}})

(defn known-profile?
  "Returns true when profile-name is registered."
  [profile-registry profile-name]
  (contains? (:profiles profile-registry) profile-name))

(defn register-profile
  "Registers a named profile in the registry value. Profile fns receive one context map."
  [profile-registry profile-name profile]
  (when-not (valid-profile? profile)
    (throw (ex-info "Invalid profile registration"
                    {:profile profile-name
                     :required [:project-state :encode-command]})))
  (assoc-in profile-registry [:profiles profile-name] profile))

(defn register-codec
  "Registers a channel codec in the registry value. dispatch-key is [thing-type channel-id].
   Codec :to-state may return a raw value (auto-wrapped) or a {:state … :state-type …} map."
  [profile-registry dispatch-key codec]
  (when-not (valid-codec? codec)
    (throw (ex-info "Invalid channel codec registration"
                    {:dispatch-key dispatch-key
                     :allowed-keys [:to-state :from-state]})))
  (assoc-in profile-registry [:codecs dispatch-key] codec))

(defn project-state
  "Projects a channel value into an item state via the Link's configured profile."
  [profile-registry {:keys [link] :as context}]
  (let [profile-name (:profile link)
        profile (get-in profile-registry [:profiles profile-name])]
    (when-not profile
      (throw (ex-info "Unknown profile" {:profile profile-name})))
    (normalize-projected-state
     ((:project-state profile) (assoc context :profile-registry profile-registry)))))

(defn encode-command
  "Encodes an item command into a channel command value via the Link's configured profile."
  [profile-registry {:keys [link] :as context}]
  (let [profile-name (:profile link)
        profile (get-in profile-registry [:profiles profile-name])]
    (when-not profile
      (throw (ex-info "Unknown profile" {:profile profile-name})))
    ((:encode-command profile) (assoc context :profile-registry profile-registry))))