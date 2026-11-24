(ns laser-show.views.components.vibe-pads
  "The Perform tab's five pads: HIT, ACCENT, FLASH, VIBE and BLACKOUT. A pad
   shows its role, what it plays now (VIBE: the vibe a tap goes to) and the
   controls it answers to, and lights while its role is held, by any control.
   Under that, the tap reactions that follow it, and a pulse bar that flashes
   on each press (from the show clock's newest tick, so even a short tap
   shows). A role that plays no cue but moves effects says so.
   
   A primary-button press or a touch presses the role and its release lets
   go (:vibe/pointer, events/handlers/vibes.clj, keeps one finger to one press
   and one release); a right-click opens the pad's menu, to learn or clear its
   control. A pad holds nothing itself, and never takes the keyboard focus, so
   Space or Enter can't press it a second time."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.views.components.clock-view :as clock-view]
            [laser-show.views.components.grid-cell :as grid-cell]
            [laser-show.views.components.trigger-bank :as trigger-bank])
  (:import [javafx.scene Node]))

(def ^:private roles
  "The pads, left to right."
  [:hit :accent :flash :vibe :blackout])

(def ^:private pulse-beats
  "How long a press's flash takes to fade, in beats."
  0.5)

(defn redraw-pulse!
  "A pulse bar, whose user data is the source it follows, from the newest
   tick's presses (show-time snapshot :pulses): full at a press, gone
   pulse-beats later; dark before the first press."
  [^Node node snapshot source]
  (let [beats (get-in snapshot [:pulses source :beats])]
    (.setOpacity node (if beats (max 0.0 (- 1.0 (/ (double beats) pulse-beats))) 0.0))))

(defn- now-text
  "What the pad says under its role."
  [context {:keys [role playing problem next on? effects-only?]}]
  (cond
    (= :stuck-key problem) "Stuck key released"
    effects-only? "Effects only"
    problem "Nothing to play"
    (= :vibe role) (if next (str "→ " next) "–")
    (= :blackout role) (if on? "ON" "")
    playing (let [[col row] playing]
              (grid-cell/cell-label (fx/sub-ctx context subs/cell-display-data col row)))
    :else "–"))

(defn- badge-text [{:keys [role learning? locked?]}]
  (cond
    learning? "LEARN"
    (and (= :vibe role) locked?) "LOCKED"))

(defn pad
  "One pad (props: :role). Its children are the same on every render and
   don't take the mouse, so a press and its release reach the pad itself.
   It says AUDIENCE while its press may start a cue that reaches the crowd,
   and when the watchdog let go of its stuck key."
  [{:keys [fx/context role]}]
  (let [{:keys [label bindings held? learning? dim? on? reactions] :as data} (fx/sub-ctx context subs/vibe-pad role)
        audience? (fx/sub-ctx context subs/vibe-pad-audience? role)
        learn (fx/sub-ctx context subs/learn-for-target {:type :vibe :role role})
        document-id (fx/sub-ctx context subs/document-id)
        id (str "vibe-pad-" (name role))
        pointer {:event/type :vibe/pointer :role role}
        badge (badge-text data)]
    {:fx/type :label
     :id id
     :focus-traversable false
     :pick-on-bounds true
     :min-width 120
     :pref-width 120
     :min-height 140
     :max-width Double/MAX_VALUE
     :max-height 300
     :alignment :center
     :content-display :graphic-only
     :style-class (cond-> ["vibe-pad"]
                    held? (conj "vibe-pad-held")
                    on? (conj "vibe-pad-on")
                    learning? (conj "vibe-pad-learning")
                    dim? (conj "vibe-pad-dim"))
     :on-mouse-pressed pointer
     :on-mouse-released pointer
     :on-touch-pressed pointer
     :on-touch-released pointer
     :context-menu {:fx/type :context-menu
                    :items [{:fx/type :menu-item
                             :id (str id "-learn")
                             :text "Learn control…"
                             :on-action {:event/type :input/start-learn
                                         :target-action {:type :vibe :role role}
                                         :document-id document-id}}
                            {:fx/type :menu-item
                             :id (str id "-cancel-learn")
                             :text "Cancel learning"
                             :disable (not learning?)
                             :on-action (merge {:event/type :input/cancel-learn} learn)}
                            {:fx/type :separator-menu-item}
                            {:fx/type :menu-item
                             :id (str id "-clear")
                             :text "Clear control"
                             :disable (empty? bindings)
                             :on-action {:event/type :vibe/clear-control :role role}}]}
     :graphic {:fx/type :v-box
               :alignment :center
               :spacing 6
               :mouse-transparent true
               :children [{:fx/type :label
                           :id (str id "-badge")
                           :text (or badge "")
                           :visible (some? badge)
                           :managed (some? badge)
                           :style-class "vibe-pad-badge"}
                          {:fx/type :label
                           :id (str id "-audience")
                           :text "AUDIENCE"
                           :visible audience?
                           :managed audience?
                           :style-class "vibe-pad-audience"}
                          {:fx/type :label
                           :text label
                           :style-class "vibe-pad-role"}
                          {:fx/type :label
                           :id (str id "-now")
                           :text (now-text context data)
                           :style-class "vibe-pad-now"}
                          {:fx/type :label
                           :id (str id "-reactions")
                           :text (str/join " · " reactions)
                           :visible (boolean (seq reactions))
                           :managed (boolean (seq reactions))
                           :wrap-text true
                           :style-class "vibe-pad-reactions"}
                          {:fx/type :label
                           :id (str id "-keys")
                           :text (if (seq bindings)
                                   (str/join " · " (map trigger-bank/binding-label bindings))
                                   "Right-click to learn a control")
                           :wrap-text true
                           :style-class "vibe-pad-keys"}
                          {:fx/type clock-view/ext-clock-redraw
                           :redraw! #'redraw-pulse!
                           :desc {:fx/type :region
                                  :id (str id "-pulse")
                                  :user-data role
                                  :opacity 0.0
                                  :min-width 90
                                  :pref-width 90
                                  :min-height 8
                                  :pref-height 8
                                  :max-height 8
                                  :style-class "vibe-pad-pulse"}}]}}))

(defn pad-row
  "The five pads, side by side, sharing the width."
  [_]
  {:fx/type :h-box
   :id "vibe-pad-row"
   :spacing 12
   :children (mapv (fn [role] {:fx/type pad :role role :h-box/hgrow :always}) roles)})
