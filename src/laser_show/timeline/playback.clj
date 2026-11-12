(ns laser-show.timeline.playback
  "Playback helpers for timeline cues, used on the render threads (preview
   timer and streaming engines). Pure.
   
   - A timeline may lock its own tempo (:timeline/tempo-bpm); otherwise it
     follows the global BPM like any cue.
   - Each top-level clip renders with clip-local time, so modulators and
     keyframes start from the clip's start, not the cue trigger.
   - A timeline with :timeline/chase :mtc follows incoming MIDI timecode
     (apply-chase)."
  (:require [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.animation.cue-timing :as cue-timing]
            [laser-show.timeline.model :as model]
            [laser-show.timeline.timecode :as tc]))


(defn cue-bpm
  "Tempo a cue plays at: a timeline's locked tempo, else the global BPM."
  [cue global-bpm]
  (or (when (cue-chains/timeline-cue? cue) (:timeline/tempo-bpm cue))
      global-bpm))

(defn timeline-cfg
  "What update-cue-timing needs to know about a timeline cue: {:length beats}."
  [cue]
  {:length (model/effective-length cue)})

(defn clip-timing-ctx
  "The cue's timing context made local to a clip: beats and ms count from the
   clip's start. trigger-time is 0 and time-ms is the local ms, so once-mode
   modulators measure their progress from the clip start too.
   :window-beats keeps the absolute position, because nested clips and
   effects keep absolute windows (decision D5)."
  [timing-ctx clip bpm]
  (let [absolute (double (or (:window-beats timing-ctx) (:effective-beats timing-ctx 0.0)))
        local (max 0.0 (- absolute (double (or (model/clip-start clip) 0.0))))
        ms (* local (/ 60000.0 (double bpm)))]
    (assoc timing-ctx
           :window-beats absolute
           :accumulated-beats local
           :effective-beats local
           :phase-offset 0.0
           :accumulated-ms ms
           :time-ms ms
           :trigger-time 0
           :bpm bpm)))

(defn clip-elapsed-ms
  "elapsed-ms for a clip's effects (ms since the clip started)."
  ^long [clip-ctx]
  (long (:accumulated-ms clip-ctx 0.0)))


;; Chasing MIDI timecode


(defn chases-mtc?
  "Does this cue follow incoming MIDI timecode?"
  [cue]
  (and (cue-chains/timeline-cue? cue) (= :mtc (:timeline/chase cue))))

(defn chase-beat
  "Timeline beat for show time `seconds`: beat 0 sits at :timeline/tc-start."
  [cue seconds global-bpm]
  (tc/seconds->beats (- (double seconds) (double (or (:timeline/tc-start cue) 0.0)))
                     (cue-bpm cue global-bpm)))

(defn apply-chase
  "Set every chasing timeline in cue-chains from the MTC position
   (laser-show.input.mtc/position):
   - running or freewheeling inside [0, length): active, at the chased beat
   - otherwise (stopped, before beat 0, at or past the end): inactive
   The position is set, not integrated, so the playhead is always exactly
   the timecode. Other cues are untouched."
  [playback cue-chains {:keys [state seconds]} global-bpm now-ms]
  (reduce-kv
   (fn [pb cell cue]
     (if-not (chases-mtc? cue)
       pb
       (let [bpm (cue-bpm cue global-bpm)
             beat (when (and seconds (not= :stopped state))
                    (chase-beat cue seconds global-bpm))
             inside? (and beat (>= beat 0.0) (< beat (double (model/effective-length cue))))
             active? (some? (get-in pb [:active-cues cell]))]
         (cond
           inside?
           (-> (if active?
                 pb
                 (cue-timing/activate-cue pb cell now-ms 0.0 {:timeline? true :start-beat beat}))
               (update-in [:active-cues cell]
                          (fn [t]
                            (-> (cue-timing/seek t beat)
                                (assoc :timeline? true
                                       :chasing? true
                                       :paused? false
                                       :accumulated-ms (* beat (/ 60000.0 (double bpm)))
                                       :last-frame-time now-ms)))))

           active? (cue-timing/deactivate-cue pb cell)
           :else pb))))
   playback
   (or cue-chains {})))
