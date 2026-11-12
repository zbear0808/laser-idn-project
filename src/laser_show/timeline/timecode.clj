(ns laser-show.timeline.timecode
  "Timecode maths for timelines: beats <-> seconds at a tempo, and seconds <->
   SMPTE timecode (24, 25, 29.97 drop-frame, 30 fps). Pure.
   
   Used by the editor ruler and readout, the inspector, and (Phase 5) the MTC
   chase.")


(def frame-rates
  "Supported frame rates. 29.97 is drop-frame."
  [24 25 29.97 30])

(defn drop-frame? [fps] (== 29.97 (double fps)))

(defn- nominal-fps
  "Frames per timecode second: 30 for 29.97."
  ^long [fps]
  (long (Math/round (double fps))))

(defn beats->seconds [beats bpm] (* (double beats) (/ 60.0 (double bpm))))

(defn seconds->beats [seconds bpm] (* (double seconds) (/ (double bpm) 60.0)))


;; Frame counts <-> timecode fields
;;
;; Drop-frame (29.97): frame numbers 0 and 1 are skipped at the start of
;; every minute except multiples of 10, so 10 minutes hold 17982 frames.


(defn- frames->tc [^long frames fps]
  (let [n (nominal-fps fps)
        frames (if (drop-frame? fps)
                 (let [per-10min 17982
                       per-min 1798
                       d (quot frames per-10min)
                       r (mod frames per-10min)]
                   (+ frames (* 18 d) (if (> r 1) (* 2 (quot (- r 2) per-min)) 0)))
                 frames)
        f (mod frames n)
        total-s (quot frames n)]
    {:h (quot total-s 3600)
     :m (mod (quot total-s 60) 60)
     :s (mod total-s 60)
     :f f
     :drop? (drop-frame? fps)}))

(defn- tc->frames ^long [{:keys [h m s f]} fps]
  (let [n (nominal-fps fps)
        total-min (+ (* 60 (long h)) (long m))
        frames (+ (* n (+ (* 3600 (long h)) (* 60 (long m)) (long s))) (long f))]
    (if (drop-frame? fps)
      (- frames (* 2 (- total-min (quot total-min 10))))
      frames)))

(defn seconds->tc
  "Timecode {:h :m :s :f :drop?} of the frame containing `seconds`."
  [seconds fps]
  (frames->tc (long (Math/floor (+ 1e-9 (* (max 0.0 (double seconds)) (double fps))))) fps))

(defn tc->seconds
  "Seconds at the start of the frame for timecode tc."
  [tc fps]
  (/ (double (tc->frames tc fps)) (double fps)))

(defn format-tc
  "\"HH:MM:SS:FF\", with ';' before the frames for drop-frame."
  [{:keys [h m s f drop?]}]
  (format "%02d:%02d:%02d%s%02d" h m s (if drop? ";" ":") f))

(defn parse-tc
  "Parse \"HH:MM:SS:FF\" (or with ';') into a timecode map, or nil."
  [text fps]
  (when-let [[_ h m s f] (re-matches #"\s*(\d{1,2})[:;](\d{1,2})[:;](\d{1,2})[:;](\d{1,2})\s*" (str text))]
    (let [tc {:h (parse-long h) :m (parse-long m) :s (parse-long s) :f (parse-long f) :drop? (drop-frame? fps)}]
      (when (and (< (:m tc) 60) (< (:s tc) 60) (< (:f tc) (nominal-fps fps)))
        tc))))


;; Show time


(defn show-seconds
  "Show time at a timeline beat: :timeline/tc-start + the beats at the cue's
   tempo. Without a locked tempo (:timeline/tempo-bpm) it's an estimate at
   the current global BPM, so :approx? is true."
  [cue beats global-bpm]
  (let [locked (:timeline/tempo-bpm cue)
        bpm (or locked global-bpm 120.0)]
    {:seconds (+ (double (or (:timeline/tc-start cue) 0.0)) (beats->seconds beats bpm))
     :approx? (nil? locked)}))

(defn cue-fps [cue] (or (:timeline/tc-fps cue) 30))

(defn format-clock
  "\"mm:ss\" (or \"h:mm:ss\" past an hour) for ruler labels."
  [seconds]
  (let [s (long (Math/floor (double seconds)))
        h (quot s 3600) m (mod (quot s 60) 60) sec (mod s 60)]
    (if (pos? h)
      (format "%d:%02d:%02d" h m sec)
      (format "%02d:%02d" m sec))))
