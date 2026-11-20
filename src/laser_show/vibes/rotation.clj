(ns laser-show.vibes.rotation
  "Auto-advance (plans/2026-11-18-vibe-mode/06; beyond-vibe's three-state
   rotation, engine/mod.rs): while auto is on, the vibe that plays is
   :active for :seconds, then :armed, and changes to the next of :order at
   the first phrase gap (no key down, and no HIT for :pause-beats beats) or
   when FLASH is let go (vibes.engine). A VIBE hold locks it, and nothing
   changes by itself during blackout or with one vibe.

   Pure. The show clock asks due on every tick (services/show_clock.clj) and
   sends what it returns as an event, at most every report-interval-ms
   (show-time/due-reports); engine/arm and engine/advance ask again before
   they act, so a report that comes late, or twice, changes nothing. Times
   are app state's: wall-clock ms."
  (:require [laser-show.vibes.library :as library]))

(def ^:private defaults (get-in library/empty-library [:settings :rotation]))

(defn settings
  "The rotation settings ([:config :vibes :settings :rotation], saved):
   :mode (:auto or :off), :seconds a vibe plays before it is armed and the
   :pause-beats of a phrase gap; a key the project lacks as its default."
  [state]
  (merge defaults (get-in state [:config :vibes :settings :rotation])))

(defn auto?
  "Is auto-advance on: the Perform tab's switch ([:vibe-runtime :auto?])
   once it has been used, else the saved :mode."
  [state]
  (let [switch (-> state :vibe-runtime :auto?)]
    (if (nil? switch)
      (= :auto (or (-> state :config :vibes :settings :rotation :mode) (:mode defaults)))
      (boolean switch))))

(defn- setting
  "A number from the saved rotation settings, or its default."
  ^double [rotation-settings k]
  (let [v (get rotation-settings k)]
    (if (number? v) (double v) (double (get defaults k)))))

(defn seconds
  "How long a vibe plays before the rotation is armed, in seconds."
  ^double [state]
  (setting (-> state :config :vibes :settings :rotation) :seconds))

(defn key-down?
  "Is a role held: by the engine, or by the pulse of a press that played
   nothing?"
  [state]
  (let [pulses (-> state :playback :pulses)]
    (boolean (or (pos? (count (-> state :vibe-runtime :held)))
                 (-> pulses :hit :held?)
                 (-> pulses :accent :held?)
                 (-> pulses :flash :held?)))))

(defn- turning?
  "Can the vibe that plays change by itself: auto on, not locked, no
   blackout, a vibe entered and another to go to."
  [state]
  (let [{:keys [current entered-at rotation blackout?]} (:vibe-runtime state)
        vibes (-> state :config :vibes)]
    (and (some? current)
         (number? entered-at)
         (not blackout?)
         (not (identical? :locked rotation))
         (some? (get (:library vibes) current))
         (< 1 (count (:order vibes)))
         (auto? state))))

(defn may-advance?
  "Would the vibe change now if it were up to the keys: armed, turning and
   no key down (a FLASH release asks, after the release)."
  [state]
  (and (identical? :armed (-> state :vibe-runtime :rotation))
       (turning? state)
       (not (key-down? state))))

(defn due
  "What the show clock tells app state at now-ms, at bpm: nil, or
   {:event/type :vibe/arm :from id} once the vibe id has played its :seconds,
   or, armed, {:event/type :vibe/advance :reason :gap :from id} once no key is
   down and HIT hasn't been pressed for :pause-beats beats. Allocates nothing
   when nothing is due."
  [state now-ms bpm]
  (when (turning? state)
    (let [{:keys [current entered-at rotation last-hit-at]} (:vibe-runtime state)
          rotation-settings (-> state :config :vibes :settings :rotation)
          now (double now-ms)]
      (if (identical? :armed rotation)
        (when (and (not (key-down? state))
                   (or (nil? last-hit-at)
                       (>= (- now (double last-hit-at))
                           (* (setting rotation-settings :pause-beats) (/ 60000.0 (double bpm))))))
          {:event/type :vibe/advance :reason :gap :from current})
        (when (>= (- now (double entered-at)) (* 1000.0 (setting rotation-settings :seconds)))
          {:event/type :vibe/arm :from current})))))
