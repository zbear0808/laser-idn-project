(ns laser-show.views.components.load-warning
  "What a laser's load and a heavy cue say (plans/2026-11-19-zones/05 §7):
   pure texts the rig cards, the toolbar's LOW FPS chip, the grid's cue cells
   and the cue editors share, and the line above the editors' footers
   (cue-load-warning). The numbers come rounded from the output status
   (output-runtime/status :load) and the show clock's (:cue-warnings)."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]))

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

(defn- metered
  "The points the meter counts: what the laser draws when its projector
   effects changed the count (:effect-points, Decimate in its calibration
   chain: Q-D-1 B), else what it is asked for (Q-Z3-4 A)."
  [{:keys [points-in effect-points]}]
  (or effect-points points-in))

(defn load-percent
  "The load meter (Q-Z3-4 A): the points a laser is asked for over its
   budget, the points that still reach its minimum fps, in whole percent;
   past 100 the guard acts. When its projector effects changed the count
   (Decimate in its calibration chain), the points they left instead, what
   it draws (metered, Q-D-1 B). nil without a budget."
  [{:keys [budget] :as load}]
  (let [points (metered load)]
    (when (and (number? points) (number? budget) (pos? budget))
      (Math/round (* 100.0 (/ (double points) (double budget)))))))

(defn meter-level
  "The meter's colour for pct: :ok (green) under 80, :near (amber) to 100,
   :over (red) past it."
  [pct]
  (cond
    (< pct 80) :ok
    (<= pct 100) :near
    :else :over))

(defn meter-tooltip
  "\"3,000 points a frame. 1,250 fit 20 fps at 25,000 pts/s\": the points
   the meter counts (metered)."
  [{:keys [budget min-fps scan-rate] :as load}]
  (str (grouped (metered load)) " points a frame. " (grouped budget) " fit " min-fps " fps at "
       (grouped scan-rate) " pts/s"))


;; A cue too heavy for a laser (subs/cue-warning)

(defn- kpps
  "25000 as \"25\", 27500 as \"27.5\"."
  [scan-rate]
  (let [k (/ (double scan-rate) 1000.0)]
    (if (== k (Math/rint k)) (str (long k)) (str k))))

(defn- decimate-advice
  "What the line and the tooltip say when the laser's Decimate keeps the
   cue's count (Q-D-4 B), from :decimate, the settings to suggest, whole
   numbers that work (routing.compose/cue-warnings): \"Laser C's Decimate
   keeps ~830 points: set its scan rate to Laser C's 12 kpps, or lower
   Laser C's minimum.\"; \"27 kpps\" alone for a 27.5 kpps laser, as 27 isn't
   its own; \"raise its target fps to 23\" for a target under the minimum;
   both joined by \"and\"."
  [{:keys [laser points scan-rate decimate]}]
  (let [{:keys [kpps fps]} decimate]
    (str laser "'s Decimate keeps ~" (grouped points) " points: "
         (str/join " and " (cond-> []
                             kpps (conj (str "set its scan rate to "
                                             (when (== (* 1000.0 (double kpps)) (double scan-rate)) (str laser "'s "))
                                             kpps " kpps"))
                             fps (conj (str "raise its target fps to " fps))))
         ", or lower " laser "'s minimum.")))

(defn cue-warning-text
  "A cue cell's tooltip: \"Too heavy for Laser C: about 12 fps (minimum
   20)\", and under it, when the laser's Decimate keeps the cue's count,
   what to change of it (decimate-advice); nil for no warning."
  [{:keys [laser fps min-fps decimate] :as w}]
  (when w
    (str "Too heavy for " laser ": about " fps " fps (minimum " min-fps ")"
         (when (seq decimate) (str "\n" (decimate-advice w))))))

(defn cue-line-text
  "The line above a cue editor's footer: \"⚠ ~2,010 points: about 12 fps on
   Laser C (25 kpps). Most points: Circle (1,999). Simplify it, or lower
   Laser C's minimum.\" \"Most points\" only once the item counts arrive
   (the render counts them while the editor is open). When the laser's
   Decimate keeps the cue's count, what to change of it instead
   (decimate-advice), and no \"Most points\": the cue's items aren't why.
   nil for no warning."
  [{:keys [laser points fps scan-rate item item-name decimate] :as w}]
  (when w
    (str "⚠ ~" (grouped points) " points: about " fps " fps on " laser " (" (kpps scan-rate) " kpps)."
         (if (seq decimate)
           (str " " (decimate-advice w))
           (str (when (and item item-name) (str " Most points: " item-name " (" (grouped (:points item)) ")."))
                " Simplify it, or lower " laser "'s minimum.")))))

(defn cue-load-warning
  "The line above the footer of the editor of the cue playing under
   :cue-key (both editors; a grid cell's [col row] or a show's [:show id])
   while the cue is too heavy for a laser; out of the layout otherwise."
  [{:keys [fx/context cue-key]}]
  (let [text (cue-line-text (fx/sub-ctx context subs/key-cue-warning cue-key))]
    {:fx/type :label
     :id "cue-load-warning"
     :text (or text "")
     :wrap-text true
     :max-width Double/MAX_VALUE
     :style-class "dialog-warning-line"
     :visible (some? text)
     :managed (some? text)}))
