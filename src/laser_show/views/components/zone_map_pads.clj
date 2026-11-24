(ns laser-show.views.components.zone-map-pads
  "The zone map pads (zones plan Z4, 06 §7.4), one per map: the Grid tab's
   strip, right of its title (map-strip), and the Perform tab's ZONE MAP row,
   under the faders' meters (map-pad-row). Both show while the show draws
   into a zone anywhere (Z4-Q1 A).

   A press (mouse or touch) switches to the pad's map: at once without a
   running clock, else on the next beat, or the bar with Shift or the pad's
   Switch on the bar (:zone-map/pointer, events/handlers/zone_maps.clj).
   The map in use is lit blue; one a switch waits for is amber, and blinks
   at 4 Hz from the show clock's newest tick (redraw-blink!), steady without
   a clock. Right-click: Switch on the bar, learn or clear its control, Edit
   maps…. A pad never takes the keyboard focus, so Space or Enter can't
   press it.

   After the maps, CYCLE (zones plan Z5, 07 §7.4): lit while a Map cycle
   cell picks the map, paused once a map pad's press lands, dim with no
   cycle cell on. Its press carries the cycle on, on the beat like any
   pad's. While it runs no map pad is the one in use, and the map it shows
   now is marked ↻ (the :cycling pseudo-class), from the newest tick."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.animation.show-time :as show-time]
            [laser-show.subs :as subs]
            [laser-show.views.components.applied-value :as applied-value]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.trigger-bank :as trigger-bank])
  (:import [javafx.css PseudoClass]
           [javafx.scene Node]
           [javafx.scene.control Labeled]))


;; The blink

(def ^:private blink-class (PseudoClass/getPseudoClass "blink"))

(def ^:private cycling-class (PseudoClass/getPseudoClass "cycling"))

(def ^:private blink-half-ns
  "Half a 4 Hz blink."
  125000000)

(defn- set-mark!
  "A Labeled's text, base, with ↻ before it while mark?."
  [^Labeled l base mark?]
  (when (string? base)
    (let [text (if mark? (str "↻ " base) base)]
      (when (not= text (.getText l))
        (.setText l text)))))

(defn redraw-blink!
  "A pad a switch waits for blinks at 4 Hz from the newest tick's time; with
   no tick, or a stale one (no clock), it stays lit. Its user data: pending?,
   or {:pending? :map-id :text :name-text} for a map pad, which is :cycling,
   its text (a chip's) or its name's (a large pad's) marked ↻, while the
   tick's Map cycle shows its map (zones plan Z5)."
  [^Node node snapshot data]
  (let [{:keys [pending? map-id text name-text]} (if (map? data) data {:pending? data})
        fresh? (and snapshot (not (show-time/stale? snapshot (System/nanoTime))))
        {cycle-map :map paused? :paused?} (when fresh? (get-in snapshot [:routing :cycle]))
        cycling? (boolean (and map-id (= map-id cycle-map) (not paused?)))]
    (.pseudoClassStateChanged node blink-class
                              (boolean (and pending? fresh?
                                            (odd? (quot (long (:at-ns snapshot)) blink-half-ns)))))
    (when map-id
      (.pseudoClassStateChanged node cycling-class cycling?)
      (when text (set-mark! node text cycling?))
      (when-let [name-label (when name-text (.lookup node ".zone-map-pad-name"))]
        (set-mark! name-label name-text cycling?)))))


;; A pad

(defn- state-text
  "What a pad says under its map's name."
  [{:keys [active? pending bar?]}]
  (cond
    pending (if (= :bar pending) "next bar" "next beat")
    active? "now"
    bar? "on the bar"
    :else ""))

(defn- keys-text [bindings]
  (if (seq bindings)
    (str/join " · " (map trigger-bank/binding-label bindings))
    "Right-click to learn a control"))

(defn- chip-tooltip [{:keys [name active? pending bar? bindings]}]
  (str (cond
         active? (str name ": the zone map in use")
         pending (str name ": switching on the next " (if (= :bar pending) "bar" "beat"))
         :else (str "Switch to " name " on the next " (if bar? "bar" "beat (Shift: the bar)")))
       (when (seq bindings) (str " · " (keys-text bindings)))))

(defn- bar-item
  "Switch on the bar, for the pad of map-id (:cycle for CYCLE): a check
   menu item that shows the pad's setting, after a refused click too
   (applied-value)."
  [node-id map-id bar?]
  {:fx/type applied-value/applied-control
   :property :selected
   :address [:zone-map-pad map-id :bar?]
   :value bar?
   :on-change (fn [on?] {:event/type :zone-map/set-pad-bar :map map-id :bar? on?})
   :desc {:fx/type :check-menu-item
          :id (str node-id "-bar")
          :text "Switch on the bar"}})

(defn- pad-menu
  "A pad's menu: Switch on the bar, its control, Edit maps…."
  [node-id {:keys [id bar? learning? bindings]}]
  {:fx/type :context-menu
   :items [(bar-item node-id id bar?)
           {:fx/type :separator-menu-item}
           {:fx/type :menu-item
            :id (str node-id "-learn")
            :text "Learn control…"
            :on-action {:event/type :input/start-learn :target-action {:type :zone-map :target id}}}
           {:fx/type :menu-item
            :id (str node-id "-cancel-learn")
            :text "Cancel learning"
            :disable (not learning?)
            :on-action {:event/type :input/cancel-learn}}
           {:fx/type :menu-item
            :id (str node-id "-clear")
            :text "Clear control"
            :disable (empty? bindings)
            :on-action {:event/type :zone-map/clear-control :map id}}
           {:fx/type :separator-menu-item}
           {:fx/type :menu-item
            :id (str node-id "-edit")
            :text "Edit maps…"
            :on-action {:event/type :ui/set-active-tab :tab-id :zones}}]})

(defn map-pad
  "The pad of the map map-id (props :map-id :size): :large on Perform (its
   name, now / next beat / next bar, its control), :small in the Grid tab's
   strip (\"● Left / Right\" in use, \"Odd / Even ◌\" waiting). Its children
   don't take the mouse, so a press reaches the pad itself."
  [{:keys [fx/context map-id size]}]
  (let [{:keys [name active? pending learning? bindings] :as pad} (fx/sub-ctx context subs/map-pad map-id)
        large? (= :large size)
        node-id (str (if large? "zone-map-pad-" "zone-map-chip-") (clojure.core/name map-id))
        pointer {:event/type :zone-map/pointer :map map-id}
        small-text (cond active? (str "● " name) pending (str name " ◌") :else name)
        common {:fx/type :label
                :id node-id
                :user-data (if large?
                             {:pending? (some? pending) :map-id map-id :name-text (str/upper-case (or name ""))}
                             {:pending? (some? pending) :map-id map-id :text small-text})
                :focus-traversable false
                :pick-on-bounds true
                :style-class (cond-> ["zone-map-pad" (if large? "zone-map-pad-large" "zone-map-pad-small")]
                               active? (conj "zone-map-pad-active")
                               pending (conj "zone-map-pad-pending")
                               learning? (conj "zone-map-pad-learning"))
                :on-mouse-pressed pointer
                :on-touch-pressed pointer
                :context-menu (pad-menu node-id pad)}]
    {:fx/type clock-view/ext-clock-redraw
     :redraw! #'redraw-blink!
     :every-ms 40
     :desc (if large?
             (assoc common
                    :min-width 110
                    :pref-width 160
                    :max-width Double/MAX_VALUE
                    :min-height 64
                    :alignment :center
                    :content-display :graphic-only
                    :graphic {:fx/type :v-box
                              :alignment :center
                              :spacing 2
                              :mouse-transparent true
                              :children [{:fx/type :label
                                          :id (str node-id "-name")
                                          :text (str/upper-case (or name ""))
                                          :style-class ["label" "zone-map-pad-name"]}
                                         {:fx/type :label
                                          :id (str node-id "-state")
                                          :text (state-text pad)
                                          :style-class ["label" "zone-map-pad-state"]}
                                         {:fx/type :label
                                          :id (str node-id "-keys")
                                          :text (keys-text bindings)
                                          :style-class ["label" "zone-map-pad-keys"]}]})
             (assoc common
                    :min-width :use-pref-size
                    :text small-text
                    :tooltip {:fx/type :tooltip :text (chip-tooltip pad)}))}))


;; CYCLE (zones plan Z5, 07 §7.4)

(defn- cycle-state-text
  "What CYCLE says under its name."
  [{:keys [on? running? pending]}]
  (cond
    pending (if (= :bar pending) "next bar" "next beat")
    running? "cycling"
    on? "paused"
    :else "off"))

(defn- cycle-tooltip [{:keys [on? running? pending bar? bindings description]}]
  (str (cond
         (not on?) "Switch on a Map cycle cell on the Effects grid"
         pending (str "Carrying the Map cycle on, on the next " (if (= :bar pending) "bar" "beat"))
         running? description
         :else (str "Paused: a map pad's press holds its map. Press to carry the Map cycle on, on the next "
                    (if bar? "bar" "beat (Shift: the bar)")))
       (when (seq bindings) (str " · " (keys-text bindings)))))

(defn- cycle-menu
  "CYCLE's menu: Switch on the bar, its control."
  [node-id {:keys [bar? learning? bindings]}]
  {:fx/type :context-menu
   :items [(bar-item node-id :cycle bar?)
           {:fx/type :separator-menu-item}
           {:fx/type :menu-item
            :id (str node-id "-learn")
            :text "Learn control…"
            :on-action {:event/type :input/start-learn :target-action {:type :zone-map :target :cycle}}}
           {:fx/type :menu-item
            :id (str node-id "-cancel-learn")
            :text "Cancel learning"
            :disable (not learning?)
            :on-action {:event/type :input/cancel-learn}}
           {:fx/type :menu-item
            :id (str node-id "-clear")
            :text "Clear control"
            :disable (empty? bindings)
            :on-action {:event/type :zone-map/clear-control :map :cycle}}]})

(defn cycle-pad
  "CYCLE (props :size): ↻ CYCLE, lit while a Map cycle picks the map,
   \"paused\" once a map pad's press landed, dim without a cycle cell on;
   a press carries the cycle on (:zone-map/pointer :map :cycle)."
  [{:keys [fx/context size]}]
  (let [{:keys [on? running? paused? pending learning? bindings] :as state} (fx/sub-ctx context subs/map-cycle-state)
        large? (= :large size)
        node-id (if large? "zone-map-pad-cycle" "zone-map-chip-cycle")
        pointer {:event/type :zone-map/pointer :map :cycle}
        common {:fx/type :label
                :id node-id
                :user-data (some? pending)
                :focus-traversable false
                :pick-on-bounds true
                :style-class (cond-> ["zone-map-pad" "zone-map-cycle-pad" (if large? "zone-map-pad-large" "zone-map-pad-small")]
                               running? (conj "zone-map-pad-active")
                               pending (conj "zone-map-pad-pending")
                               learning? (conj "zone-map-pad-learning")
                               (not on?) (conj "zone-map-pad-dim"))
                :on-mouse-pressed pointer
                :on-touch-pressed pointer
                :tooltip {:fx/type :tooltip :text (cycle-tooltip state)}
                :context-menu (cycle-menu node-id state)}]
    {:fx/type clock-view/ext-clock-redraw
     :redraw! #'redraw-blink!
     :every-ms 40
     :desc (if large?
             (assoc common
                    :min-width 110
                    :pref-width 160
                    :max-width Double/MAX_VALUE
                    :min-height 64
                    :alignment :center
                    :content-display :graphic-only
                    :graphic {:fx/type :v-box
                              :alignment :center
                              :spacing 2
                              :mouse-transparent true
                              :children [{:fx/type :label
                                          :text "↻ CYCLE"
                                          :style-class ["label" "zone-map-pad-name"]}
                                         {:fx/type :label
                                          :id (str node-id "-state")
                                          :text (cycle-state-text state)
                                          :style-class ["label" "zone-map-pad-state"]}
                                         {:fx/type :label
                                          :id (str node-id "-keys")
                                          :text (keys-text bindings)
                                          :style-class ["label" "zone-map-pad-keys"]}]})
             (assoc common
                    :min-width :use-pref-size
                    :text (cond pending "↻ CYCLE ◌" paused? "↻ CYCLE · paused" :else "↻ CYCLE")))}))


;; The strip and the row

(defn map-strip
  "The Grid tab's strip, right of its title: \"Zone map:\", a small pad
   per map, then CYCLE, while the show draws into a zone anywhere (Z4-Q1
   A), or has a zone pattern or a Map cycle (zones plan Z5)."
  [{:keys [fx/context]}]
  (let [shown? (fx/sub-ctx context subs/zone-map-pads-shown?)]
    {:fx/type :h-box
     :id "zone-map-strip"
     :spacing 6
     :alignment :center-right
     :visible shown?
     :managed shown?
     :children (if shown?
                 (-> [{:fx/type :label :text "Zone map:" :style-class ["label" "zone-map-strip-label"]}]
                     (into (map (fn [id] {:fx/type map-pad :fx/key id :map-id id :size :small}))
                           (fx/sub-ctx context subs/map-pad-ids))
                     (conj {:fx/type cycle-pad :fx/key :cycle :size :small}))
                 [])}))

(defn map-pad-row
  "The Perform tab's ZONE MAP row, under the faders' meters: a pad per map
   and CYCLE, sharing the width, while the map pads show (map-strip's
   rule). Always there, hidden while it has nothing to show, so it never
   makes new nodes of the rest of the tab."
  [{:keys [fx/context]}]
  (let [shown? (fx/sub-ctx context subs/zone-map-pads-shown?)]
    {:fx/type :v-box
     :id "zone-map-row"
     :spacing 6
     :visible shown?
     :managed shown?
     :children (if shown?
                 [{:fx/type :label :text "ZONE MAP" :style-class ["label" "zone-map-row-title"]}
                  {:fx/type :h-box
                   :spacing 12
                   :children (conj (mapv (fn [id] {:fx/type map-pad :fx/key id :map-id id :size :large :h-box/hgrow :always})
                                         (fx/sub-ctx context subs/map-pad-ids))
                                   {:fx/type cycle-pad :fx/key :cycle :size :large :h-box/hgrow :always})}]
                 [])}))
