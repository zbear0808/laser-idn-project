(ns laser-show.views.components.vibe-pads
  "The Perform tab's five pads: HIT, ACCENT, FLASH, VIBE and BLACKOUT. A pad
   shows its role, what it plays now (VIBE: the vibe a tap goes to) and the
   controls it answers to, and lights while its role is held, by any control.
   
   A primary-button press or a touch presses the role and its release lets
   go (:vibe/pointer, events/handlers/vibes.clj, keeps one finger to one press
   and one release); a right-click opens the pad's menu, to learn or clear its
   control. A pad holds nothing itself, and never takes the keyboard focus, so
   Space or Enter can't press it a second time."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.views.components.grid-cell :as grid-cell]
            [laser-show.views.components.trigger-bank :as trigger-bank]))

(def ^:private roles
  "The pads, left to right."
  [:hit :accent :flash :vibe :blackout])

(defn- now-text
  "What the pad says under its role."
  [context {:keys [role playing problem next on?]}]
  (cond
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
   don't take the mouse, so a press and its release reach the pad itself."
  [{:keys [fx/context role]}]
  (let [{:keys [label bindings held? learning? dim? on?] :as data} (fx/sub-ctx context subs/vibe-pad role)
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
                                         :target-action {:type :vibe :role role}}}
                            {:fx/type :menu-item
                             :id (str id "-cancel-learn")
                             :text "Cancel learning"
                             :disable (not learning?)
                             :on-action {:event/type :input/cancel-learn}}
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
                           :text label
                           :style-class "vibe-pad-role"}
                          {:fx/type :label
                           :id (str id "-now")
                           :text (now-text context data)
                           :style-class "vibe-pad-now"}
                          {:fx/type :label
                           :id (str id "-keys")
                           :text (if (seq bindings)
                                   (str/join " · " (map trigger-bank/binding-label bindings))
                                   "Right-click to learn a control")
                           :wrap-text true
                           :style-class "vibe-pad-keys"}]}}))

(defn pad-row
  "The five pads, side by side, sharing the width."
  [_]
  {:fx/type :h-box
   :id "vibe-pad-row"
   :spacing 12
   :children (mapv (fn [role] {:fx/type pad :role role :h-box/hgrow :always}) roles)})
