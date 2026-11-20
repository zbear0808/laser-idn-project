(ns laser-show.views.components.load-warning
  "What a laser's load says (plans/2026-11-19-zones/05 §7): pure texts the
   rig cards, the toolbar's LOW FPS chip and the cue warnings share. The
   numbers come rounded from the output status (output-runtime/status :load)
   and the show clock's (:cue-warnings)."
  (:require [clojure.string :as str]))

(defn grouped
  "n with a comma between thousands (\"25,000\"), whatever the machine's
   locale."
  [n]
  (let [n (long (Math/round (double n)))
        digits (str (Math/abs n))
        groups (reverse (map #(apply str (reverse %)) (partition-all 3 (reverse digits))))]
    (str (when (neg? n) "-") (str/join "," groups))))


;; The toolbar's chip

(defn low-fps-text
  "\"LOW FPS · Laser R 14 fps\", the lowest of lows (subs/low-fps-projectors),
   with \" +1\" for each other low laser; nil when none is low."
  [lows]
  (when-let [{laser :name fps :fps} (first lows)]
    (str "LOW FPS · " laser " " fps " fps"
         (when (< 1 (count lows)) (str " +" (dec (count lows)))))))

(defn low-fps-tooltip
  "One line per low laser, \"Laser R: 14 fps (minimum 20) · −40% pts\", then
   \"Click to see it.\""
  [lows]
  (str/join "\n" (concat (for [{laser :name :keys [fps min-fps reduced-pct]} lows]
                           (str laser ": " fps " fps (minimum " min-fps ")"
                                (when (pos? (or reduced-pct 0)) (str " · −" reduced-pct "% pts"))))
                         ["Click to see it."])))


;; A rig card's load

(defn load-note-text
  "What the guard did, for a card (subs/projector-load: {:load :low?}): the
   parts that apply, joined by \" · \": \"min 20 fps\" while low, \"split · 2
   regions\", \"Safe area too small to split\" (S-9), \"−40% pts\", \"−12%
   bright\". nil while it isn't streaming."
  [{:keys [load low?] :as l}]
  (when l
    (let [{:keys [min-fps regions reduced-pct dim-pct no-room?]} load
          regions (or regions 0)]
      (str/join " · " (cond-> []
                        low? (conj (str "min " min-fps " fps"))
                        (pos? regions) (conj (str "split · " regions (if (= 1 regions) " region" " regions")))
                        no-room? (conj "Safe area too small to split")
                        (pos? (or reduced-pct 0)) (conj (str "−" reduced-pct "% pts"))
                        (pos? (or dim-pct 0)) (conj (str "−" dim-pct "% bright")))))))

(defn load-percent
  "The load meter (Q-Z3-4 A): the points a laser is asked for over its
   budget, the points that still reach its minimum fps, in whole percent;
   past 100 the guard acts. nil without a budget."
  [{:keys [points-in budget]}]
  (when (and (number? points-in) (number? budget) (pos? budget))
    (Math/round (* 100.0 (/ (double points-in) (double budget))))))

(defn meter-level
  "The meter's colour for pct: :ok (green) under 80, :near (amber) to 100,
   :over (red) past it."
  [pct]
  (cond
    (< pct 80) :ok
    (<= pct 100) :near
    :else :over))

(defn meter-tooltip
  "\"3,000 points a frame. 1,250 fit 20 fps at 25,000 pts/s\"."
  [{:keys [points-in budget min-fps scan-rate]}]
  (str (grouped points-in) " points a frame. " (grouped budget) " fit " min-fps " fps at "
       (grouped scan-rate) " pts/s"))
