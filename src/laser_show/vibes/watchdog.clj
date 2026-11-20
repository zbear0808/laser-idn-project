(ns laser-show.vibes.watchdog
  "The stuck-key guard (plans/2026-11-18-vibe-mode/07 §7): a role held for
   :max-hold-secs or longer is let go of as if its release had come, and the
   pad says its key stuck. A release that never comes (a pad pulled out
   mid-hold, a lost note-off) mustn't hold a cue, a flash, a tap reaction
   or the rotation for ever.

   A hold is the engine's ([:vibe-runtime :held role], timed by :at), or the
   pulse of a press that played nothing ([:playback :pulses role], which
   vibes.rotation also counts as a key down): its age is the show clock's
   pulse view's. A :sustain voice whose key was let go is no hold.

   Pure. The show clock asks due on every tick (services/show_clock.clj)
   and sends what it returns with auto-advance's reports, at most every
   report-interval-ms (show-time/due-reports, the runtime's :vibe-sent);
   engine/release asks again (stuck?) before it lets go, so a report that
   comes late, or twice, changes nothing. Times are app state's: wall-clock
   ms."
  (:require [laser-show.triggers.janitor :as janitor]
            [laser-show.vibes.library :as library]))

(def ^:private default-secs
  (get-in library/empty-library [:settings :max-hold-secs]))

(defn limit-ms
  "How long a role may be held, in ms: [:config :vibes :settings
   :max-hold-secs], the default when the project has none or one that is no
   positive number."
  ^double [state]
  (let [v (-> state :config :vibes :settings :max-hold-secs)]
    (* 1000.0 (double (if (and (number? v) (pos? (double v))) v default-secs)))))

(def ^:private pulse-roles [:hit :accent :flash])

(defn- pulse-only?
  "Does role's pulse hold without the engine's hold: a press that played
   nothing?"
  [state role]
  (and (true? (-> state :playback :pulses (get role) :held?))
       (nil? (-> state :vibe-runtime :held (get role)))))

(defn- any-pulse-held? [state]
  (let [pulses (-> state :playback :pulses)]
    (boolean (or (-> pulses :hit :held?) (-> pulses :accent :held?) (-> pulses :flash :held?)))))

(defn due
  "What the show clock tells app state at now-ms: nil, or a report
   {:event/type :vibe/release :role r :reason :watchdog} for each role the
   engine has held for limit-ms or more, and for each press that played
   nothing whose pulse has been held that long by pulses (the tick's pulse
   view, show-time/pulse-view; nil leaves those out), with that press's
   :pulse id. Allocates nothing while no key is down."
  [state now-ms pulses]
  (let [held (-> state :vibe-runtime :held)]
    (when (or (pos? (count held)) (any-pulse-held? state))
      (let [limit (limit-ms state)
            now (double now-ms)]
        (not-empty
         (into (vec (for [[role {:keys [at]}] held
                          :when (janitor/expired? now limit at)]
                      {:event/type :vibe/release :role role :reason :watchdog}))
               (for [role pulse-roles
                     :let [{:keys [held? ms]} (get pulses role)]
                     :when (and (pulse-only? state role) held? ms (>= (double ms) limit))]
                 {:event/type :vibe/release :role role :reason :watchdog
                  :pulse (-> state :playback :pulses (get role) :id)})))))))

(defn stuck?
  "Is the hold a watchdog report names still stuck at t: the engine's hold
   of role, held for limit-ms or more, or, for a press that played nothing,
   the same press's pulse (the report's :pulse id), still held? A hold that
   ended, or began again since the report, isn't."
  [state {:keys [role pulse]} t]
  (if-let [{:keys [at]} (-> state :vibe-runtime :held (get role))]
    (janitor/expired? (double t) (limit-ms state) at)
    (and (some? pulse)
         (pulse-only? state role)
         (= pulse (-> state :playback :pulses (get role) :id)))))
