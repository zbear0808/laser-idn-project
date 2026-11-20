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
   press it."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.animation.show-time :as show-time]
            [laser-show.subs :as subs]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.trigger-bank :as trigger-bank])
  (:import [javafx.css PseudoClass]
           [javafx.scene Node]))


;; The blink

(def ^:private blink-class (PseudoClass/getPseudoClass "blink"))

(def ^:private blink-half-ns
  "Half a 4 Hz blink."
  125000000)

(defn redraw-blink!
  "A pad a switch waits for (its user data true) blinks at 4 Hz from the
   newest tick's time; with no tick, or a stale one (no clock), it stays lit."
  [^Node node snapshot pending?]
  (.pseudoClassStateChanged node blink-class
                            (boolean (and pending? snapshot
                                          (not (show-time/stale? snapshot (System/nanoTime)))
                                          (odd? (quot (long (:at-ns snapshot)) blink-half-ns))))))


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

(defn- pad-menu
  "A pad's menu: Switch on the bar, its control, Edit maps…."
  [node-id {:keys [id bar? learning? bindings]}]
  {:fx/type :context-menu
   :items [{:fx/type :check-menu-item
            :id (str node-id "-bar")
            :text "Switch on the bar"
            :selected (boolean bar?)
            :on-action {:event/type :zone-map/set-pad-bar :map id :bar? (not bar?)}}
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
        common {:fx/type :label
                :id node-id
                :user-data (some? pending)
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
                    :text (cond active? (str "● " name) pending (str name " ◌") :else name)
                    :tooltip {:fx/type :tooltip :text (chip-tooltip pad)}))}))


;; The strip and the row

(defn map-strip
  "The Grid tab's strip, right of its title: \"Zone map:\" and a small pad
   per map, while the show draws into a zone anywhere (Z4-Q1 A)."
  [{:keys [fx/context]}]
  (let [shown? (fx/sub-ctx context subs/zones-in-use?)]
    {:fx/type :h-box
     :id "zone-map-strip"
     :spacing 6
     :alignment :center-right
     :visible shown?
     :managed shown?
     :children (if shown?
                 (into [{:fx/type :label :text "Zone map:" :style-class ["label" "zone-map-strip-label"]}]
                       (map (fn [id] {:fx/type map-pad :fx/key id :map-id id :size :small}))
                       (fx/sub-ctx context subs/map-pad-ids))
                 [])}))

(defn map-pad-row
  "The Perform tab's ZONE MAP row, under the faders' meters: a pad per map,
   sharing the width, while the show draws into a zone anywhere (Z4-Q1 A).
   Always there, hidden while it has nothing to show, so it never makes new
   nodes of the rest of the tab."
  [{:keys [fx/context]}]
  (let [shown? (fx/sub-ctx context subs/zones-in-use?)]
    {:fx/type :v-box
     :id "zone-map-row"
     :spacing 6
     :visible shown?
     :managed shown?
     :children (if shown?
                 [{:fx/type :label :text "ZONE MAP" :style-class ["label" "zone-map-row-title"]}
                  {:fx/type :h-box
                   :spacing 12
                   :children (mapv (fn [id] {:fx/type map-pad :fx/key id :map-id id :size :large :h-box/hgrow :always})
                                   (fx/sub-ctx context subs/map-pad-ids))}]
                 [])}))
