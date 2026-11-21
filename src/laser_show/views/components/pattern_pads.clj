(ns laser-show.views.components.pattern-pads
  "The pattern pads (zones plan Z5, 07 §7.3): As set, Off and a pad per
   style, then STEP. The Grid tab's strip, under the zone map pads
   (pattern-strip, a flow pane that wraps when narrow), and the Perform
   tab's ZONE PATTERN row, under ZONE MAP (pattern-pad-row). Both show while
   the show has a zone pattern anywhere.

   A pad switches the style of every pattern that follows the pads (Z5-Q1
   A), as a map pad switches the map: at once without a running clock, else
   on the next beat, or the bar with Shift or the pad's Switch on the bar
   (:pattern-pads/pointer, events/handlers/zone_patterns.clj). The style in
   use is lit blue; one a switch waits for is amber, and blinks
   (zone-map-pads/redraw-blink!). STEP presses the Zone step key at once,
   flashes on each press as the vibe pads' pulse bars do, and shows the
   presses counted, from the newest tick. Right-click: Switch on the bar,
   learn or clear its control. A pad never takes the keyboard focus."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.animation.show-time :as show-time]
            [laser-show.routing.zone-patterns :as zone-patterns]
            [laser-show.subs :as subs]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.trigger-bank :as trigger-bank]
            [laser-show.views.components.vibe-pads :as vibe-pads]
            [laser-show.views.components.zone-map-pads :as zone-map-pads])
  (:import [javafx.scene.control Labeled]))


;; What a pad says

(def ^:private tooltips
  "Each pad's tooltip (07 §7.4's words)."
  {:as-set "Every pattern plays the style it was made with"
   :off "Patterns that follow the pads stop moving"
   :alternate "Every other zone, in turn"
   :chase "One zone after another"
   :ping-pong "Along the zones and back"
   :random "A different zone each step, never the same twice"
   :shift "What's in the zones moves along"
   :swap "The first two zones trade what's in them"
   :step "Steps the patterns that step on the Zone step key"})

(defn- keys-text
  "A pad's controls; a row of nine pads leaves room for a short hint."
  [bindings]
  (if (seq bindings)
    (str/join " · " (map trigger-bank/binding-label bindings))
    "Right-click to learn"))

(defn- tooltip [id bindings]
  (str (tooltips id) (when (seq bindings) (str " · " (keys-text bindings)))))

(defn- keys-label [id bindings]
  {:fx/type :label
   :id (str id "-keys")
   :text (keys-text bindings)
   :wrap-text true
   :text-alignment :center
   :style-class ["label" "pattern-pad-keys"]})

(defn- state-text
  "What a large pad says under its name."
  [{:keys [active? pending bar?]}]
  (cond
    pending (if (= :bar pending) "next bar" "next beat")
    active? "now"
    bar? "on the bar"
    :else ""))

(defn- pad-menu
  "A pad's menu: Switch on the bar (not STEP's: a press is at once), its
   control."
  [node-id pad {:keys [bar? learning? bindings]}]
  {:fx/type :context-menu
   :items (into (if (= :step pad)
                  []
                  [{:fx/type :check-menu-item
                    :id (str node-id "-bar")
                    :text "Switch on the bar"
                    :selected (boolean bar?)
                    :on-action {:event/type :pattern-pads/set-pad-bar :pad pad :bar? (not bar?)}}
                   {:fx/type :separator-menu-item}])
                [{:fx/type :menu-item
                  :id (str node-id "-learn")
                  :text "Learn control…"
                  :on-action {:event/type :input/start-learn
                              :target-action (if (= :step pad) {:type :zone-step} {:type :pattern-pad :target pad})}}
                 {:fx/type :menu-item
                  :id (str node-id "-cancel-learn")
                  :text "Cancel learning"
                  :disable (not learning?)
                  :on-action {:event/type :input/cancel-learn}}
                 {:fx/type :menu-item
                  :id (str node-id "-clear")
                  :text "Clear control"
                  :disable (empty? bindings)
                  :on-action {:event/type :pattern-pads/clear-control :pad pad}}])})

(defn- node-id [pad large?]
  (str (if large? "pattern-pad-" "pattern-chip-") (name pad)))

(defn- shell
  "What every pad shares: a press reaches the pad itself, it never takes
   the focus, its menu."
  [id pad classes data large?]
  (let [pointer {:event/type :pattern-pads/pointer :pad pad}]
    {:fx/type :label
     :id id
     :focus-traversable false
     :pick-on-bounds true
     :style-class (into ["pattern-pad" (if large? "pattern-pad-large" "pattern-pad-small")] classes)
     :on-mouse-pressed pointer
     :on-touch-pressed pointer
     :context-menu (pad-menu id pad data)}))

(defn- large
  "A Perform pad: its children don't take the mouse."
  [desc children]
  (assoc desc
         :min-width 90
         :pref-width 130
         :max-width Double/MAX_VALUE
         :min-height 64
         :alignment :center
         :content-display :graphic-only
         :graphic {:fx/type :v-box
                   :alignment :center
                   :spacing 2
                   :mouse-transparent true
                   :children children}))


;; A pad

(defn pattern-pad
  "The pad of pad (one of zone-patterns/pad-ids; props :pad :size):
   :large on Perform (its name, now / next beat / next bar, its control),
   :small in the Grid tab's strip (\"● Chase\" in use, \"Chase ◌\"
   waiting)."
  [{:keys [fx/context pad size]}]
  (let [{:keys [name active? pending learning? bindings] :as data} (fx/sub-ctx context subs/pattern-pad pad)
        large? (= :large size)
        id (node-id pad large?)
        desc (-> (shell id pad (cond-> []
                                 active? (conj "pattern-pad-active")
                                 pending (conj "pattern-pad-pending")
                                 learning? (conj "pattern-pad-learning"))
                        data large?)
                 (assoc :user-data (some? pending)
                        :tooltip {:fx/type :tooltip :text (tooltip pad bindings)}))]
    {:fx/type clock-view/ext-clock-redraw
     :redraw! #'zone-map-pads/redraw-blink!
     :every-ms 40
     :desc (if large?
             (large desc [{:fx/type :label :text (str/upper-case name) :style-class ["label" "pattern-pad-name"]}
                          {:fx/type :label :id (str id "-state") :text (state-text data) :style-class ["label" "pattern-pad-state"]}
                          (keys-label id bindings)])
             (assoc desc
                    :min-width :use-pref-size
                    :text (cond active? (str "● " name) pending (str name " ◌") :else name)))}))


;; STEP

(defn redraw-count!
  "STEP's count: the Zone step key's presses in the newest tick's pulse
   view (0 before the first), while the tick is fresh."
  [^Labeled node snapshot _]
  (let [n (when (and snapshot (not (show-time/stale? snapshot (System/nanoTime))))
            (get-in snapshot [:pulses :zone-step :count]))
        text (str (or n 0))]
    (when (not= text (.getText node))
      (.setText node text))))

(defn- pulse
  "A bar that flashes on each press of the Zone step key (vibe-pads/redraw-pulse!)."
  [id width height]
  {:fx/type clock-view/ext-clock-redraw
   :redraw! #'vibe-pads/redraw-pulse!
   :desc {:fx/type :region
          :id id
          :user-data :zone-step
          :opacity 0.0
          :mouse-transparent true
          :min-width width :pref-width width :max-width width
          :min-height height :pref-height height :max-height height
          :style-class "pattern-step-pulse"}})

(defn step-pad
  "STEP (props :size): presses the Zone step key at once; :large shows the
   presses counted and its control, and both flash on each press."
  [{:keys [fx/context size]}]
  (let [{:keys [learning? bindings] :as data} (fx/sub-ctx context subs/step-pad)
        large? (= :large size)
        id (node-id :step large?)
        desc (assoc (shell id :step (cond-> ["pattern-step-pad"] learning? (conj "pattern-pad-learning")) data large?)
                    :tooltip {:fx/type :tooltip :text (tooltip :step bindings)})]
    (if large?
      (large desc [{:fx/type :label :text "STEP" :style-class ["label" "pattern-pad-name"]}
                   {:fx/type clock-view/ext-clock-redraw
                    :redraw! #'redraw-count!
                    :desc {:fx/type :label :id (str id "-count") :text "0" :style-class ["label" "pattern-step-count"]}}
                   (pulse (str id "-pulse") 48 4)
                   (keys-label id bindings)])
      (assoc desc
             :min-width :use-pref-size
             :text "STEP"
             :content-display :right
             :graphic-text-gap 5
             :graphic (pulse (str id "-pulse") 6 6)))))


;; The strip and the row

(defn pattern-strip
  "The Grid tab's strip, under the zone map pads: \"Pattern:\", a small pad
   for As set, Off and each style, then STEP; it wraps when narrow."
  [{:keys [fx/context]}]
  (let [shown? (fx/sub-ctx context subs/zone-patterns-in-use?)]
    {:fx/type :flow-pane
     :id "pattern-strip"
     :hgap 6
     :vgap 4
     :alignment :center-right
     :visible shown?
     :managed shown?
     :children (if shown?
                 (-> [{:fx/type :label :text "Pattern:" :style-class ["label" "zone-map-strip-label"]}]
                     (into (map (fn [pad] {:fx/type pattern-pad :fx/key pad :pad pad :size :small}))
                           zone-patterns/pad-ids)
                     (conj {:fx/type :label :text "│" :style-class ["label" "zone-map-strip-label"]}
                           {:fx/type step-pad :fx/key :step :size :small}))
                 [])}))

(defn pattern-pad-row
  "The Perform tab's ZONE PATTERN row, under ZONE MAP: a pad for As set,
   Off and each style, then STEP, sharing the width, while the show has a
   zone pattern anywhere. Always there, hidden while it has nothing to
   show."
  [{:keys [fx/context]}]
  (let [shown? (fx/sub-ctx context subs/zone-patterns-in-use?)]
    {:fx/type :v-box
     :id "pattern-row"
     :spacing 6
     :visible shown?
     :managed shown?
     :children (if shown?
                 [{:fx/type :label :text "ZONE PATTERN" :style-class ["label" "zone-map-row-title"]}
                  {:fx/type :h-box
                   :spacing 10
                   :children (conj (mapv (fn [pad] {:fx/type pattern-pad :fx/key pad :pad pad :size :large :h-box/hgrow :always})
                                         zone-patterns/pad-ids)
                                   {:fx/type step-pad :fx/key :step :size :large :h-box/hgrow :always})}]
                 [])}))
