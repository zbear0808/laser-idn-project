(ns laser-show.triggers.tap-tempo
  "Pure tap-tempo calculation from timestamped taps.")

(def default-options {:max-taps 6 :timeout-ms 2000 :min-bpm 20.0 :max-bpm 300.0})

(defn make
  ([] (make {}))
  ([options]
   (merge default-options options {:taps [] :bpm nil})))

(defn- valid-interval? [interval options]
  (let [bpm (/ 60000.0 interval)]
    (<= (:min-bpm options) bpm (:max-bpm options))))

(defn tap
  "Adds a tap at timestamp-ms and returns updated state."
  [state timestamp-ms]
  (let [options state
        taps (if (and (seq (:taps state))
                      (> (- timestamp-ms (last (:taps state)))
                         (:timeout-ms state)))
               []
               (:taps state))
        taps (conj taps timestamp-ms)
        taps (vec (take-last (:max-taps state) taps))
        intervals (map #(- %1 %2) (rest taps) taps)
        intervals (filter #(valid-interval? % options) intervals)
        bpm (when (seq intervals)
              (/ 60000.0 (double (/ (reduce + intervals) (count intervals)))))]
    (assoc state :taps taps :bpm bpm :last-tap timestamp-ms)))

(defn bpm [state] (:bpm state))
(defn reset [state] (assoc state :taps [] :bpm nil :last-tap nil))

(defn beats-per-minute
  "Calculates BPM from timestamps, or nil when fewer than two taps exist."
  ([timestamps] (beats-per-minute timestamps default-options))
  ([timestamps options]
   (let [intervals (->> (map #(- %1 %2) (rest timestamps) timestamps)
                        (filter #(valid-interval? % options)))]
     (when (seq intervals)
       (/ 60000.0 (double (/ (reduce + intervals) (count intervals))))))))
