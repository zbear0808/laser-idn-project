(ns laser-show.timeline.playback
  "Playback helpers for timeline cues, used on the render threads (preview
   timer and streaming engines). Pure.
   
   - A timeline may lock its own tempo (:timeline/tempo-bpm); otherwise it
     follows the global BPM like any cue.
   - Each top-level clip renders with clip-local time, so modulators and
     keyframes start from the clip's start, not the cue trigger."
  (:require [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.timeline.model :as model]))


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
