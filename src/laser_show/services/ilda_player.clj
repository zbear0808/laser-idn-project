(ns laser-show.services.ilda-player
  "Service for ILDA file playback.
   Manages loading and playback of ILDA files."
  (:require [clojure.tools.logging :as log]
            [laser-show.backend.ilda :as ilda]
            [laser-show.animation.types :as t]))

(defonce playback-state
  (atom {:file-path nil
         :frames []
         :fps 30
         :playing? false
         :paused? false
         :start-time-ms 0
         :paused-elapsed-ms 0
         :frame-count 0}))

(defn- now-ms []
  (System/currentTimeMillis))

(defn load-file!
  "Load an ILDA file for playback.
   Stops current playback."
  [filepath]
  (try
    (log/info "Loading ILDA file:" filepath)
    (let [frames (ilda/read-ilda-file filepath)]
      (if (seq frames)
        (do
          (reset! playback-state
                  {:file-path filepath
                   :frames frames
                   :frame-count (count frames)
                   :fps 30
                   :playing? false
                   :paused? false
                   :start-time-ms 0
                   :paused-elapsed-ms 0})
          (log/info "Loaded ILDA file:" filepath "with" (count frames) "frames"))
        (log/warn "ILDA file is empty or invalid:" filepath)))
    (catch Exception e
      (log/error "Failed to load ILDA file:" filepath (.getMessage e)))))

(defn play!
  "Start or resume playback of the loaded file."
  []
  (swap! playback-state
         (fn [s]
           (if (empty? (:frames s))
             (do (log/warn "Cannot play: no ILDA file loaded") s)
             (let [now (now-ms)]
               (if (:paused? s)
                 ;; Resume from pause
                 (assoc s
                        :playing? true
                        :paused? false
                        :start-time-ms (- now (:paused-elapsed-ms s)))
                 ;; Start fresh or restart if already playing (creates replay effect)
                 (assoc s
                        :playing? true
                        :paused? false
                        :start-time-ms now
                        :paused-elapsed-ms 0)))))))

(defn stop!
  "Stop playback and reset position."
  []
  (swap! playback-state assoc
         :playing? false
         :paused? false
         :paused-elapsed-ms 0))

(defn pause!
  "Pause playback at current position."
  []
  (swap! playback-state
         (fn [s]
           (if (:playing? s)
             (let [now (now-ms)
                   elapsed (- now (:start-time-ms s))]
               (assoc s
                      :playing? false
                      :paused? true
                      :paused-elapsed-ms elapsed))
             s))))

(defn is-playing?
  "Check if playback is active."
  []
  (:playing? @playback-state))

(defn get-current-frame
  "Get the current frame based on elapsed time.
   Returns nil if not playing or no file loaded.
   Playback loops automatically."
  []
  (let [{:keys [playing? frames frame-count fps start-time-ms]} @playback-state]
    (when (and playing? (pos? frame-count))
      (let [now (now-ms)
            elapsed (- now start-time-ms)
            frame-duration-ms (/ 1000.0 (max 1.0 (double fps))) ;; Avoid divide by zero
            total-duration-ms (* frame-count frame-duration-ms)]
        (if (<= total-duration-ms 0)
          (:points (first frames))
          (let [looped-elapsed (mod elapsed total-duration-ms)
                frame-index (int (/ looped-elapsed frame-duration-ms))
                ;; Ensure index is within bounds (floating point issues might cause it to hit frame-count)
                safe-index (min (dec frame-count) (max 0 frame-index))]
            (:points (nth frames safe-index))))))))

(defn set-fps!
  "Set the playback frame rate."
  [fps]
  (swap! playback-state assoc :fps fps))
