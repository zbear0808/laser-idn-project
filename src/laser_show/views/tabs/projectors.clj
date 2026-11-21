(ns laser-show.views.tabs.projectors
  "Projectors tab - for managing IDN laser projectors.
   
   Features:
   - The rig strip: every output a scan finds is a card, in rig order; drag
     the cards into the order the lasers hang in (views/components/rig_strip)
   - Configure the selected card's projector (the inspector): its settings,
     its test pattern, its effect chain (color calibration, corner pin), its
     safety zone, in three columns that each scroll on their own
   - A divider between the strip and the inspector, kept where it is left
     for the session (plans/2026-11-19-zones/13)"
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.effects :as effects]
            [laser-show.animation.fit-guard :as fit-guard]
            [laser-show.state.clipboard :as clipboard]
            [laser-show.common.util :as u]
            [laser-show.css.core :as css]
            [laser-show.idn.stream :as stream]
            [laser-show.routing.rig :as rig]
            [laser-show.views.components.visual-editors.custom-param-renderers :as custom-renderers]
            [laser-show.views.components.parameter-controls :as param-controls]
            [laser-show.views.components.safety-editor :as safety-editor]
            [laser-show.views.components.list :as list]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.rig-file-status :as rig-file-status]
            [laser-show.views.components.rig-strip :as rig-strip])
  (:import [javafx.beans.value ChangeListener]
           [javafx.event Event]
           [javafx.scene Node]
           [javafx.scene.control SplitPane]
           [javafx.scene.input MouseEvent]
           [javafx.scene.layout Region]))


;; What the text fields take (they commit on Enter or focus loss)


(defn- parse-port
  "The port typed into the Port field, or nil unless it is 1-65535."
  [text]
  (let [port (parse-long (str/trim text))]
    (when (and port (<= 1 port 65535))
      port)))

(defn- parse-scan-rate
  "Points per second typed into the Scan rate field: digits, with spaces or
   commas between them if you like (\"25 000\", \"25,000\"), or thousands with
   a k (\"25k\", \"27.5k\"). nil for anything else."
  [text]
  (let [t (str/lower-case (str/trim text))]
    (if-let [[_ thousands] (re-matches #"(\d+(?:\.\d+)?)\s*k" t)]
      (Math/round (* 1000.0 (parse-double thousands)))
      (when (re-matches #"\d[\d ,]*" t)
        (parse-long (str/replace t #"[ ,]" ""))))))


;; Projector Configuration Panel


(defn- projector-basic-settings
  "Basic projector settings (name, host, port, scan rate, service info)."
  [{:keys [projector]}]
  (let [{:keys [id name host port scan-rate service-id service-name]} projector
        stored-rate (or scan-rate stream/DEFAULT_SCAN_RATE)]
    {:fx/type :v-box
     :spacing 8
     :children (vec
                 (concat
                   ;; Name field
                   [{:fx/type :h-box
                     :spacing 8
                     :alignment :center-left
                     :children [{:fx/type :label
                                 :text "Name:"
                                 :pref-width 50
                                 :style-class ["label-secondary"]}
                                {:fx/type :text-field
                                 :id "projector-name-field"
                                 :text (or name "")
                                 :pref-width 200
                                 :style-class ["text-field-dark"]
                                 :on-text-changed {:event/type :projectors/update-settings
                                                   :projector-id id
                                                   :updates {:name :fx/event}}}]}
                    ;; Host and port
                    {:fx/type :h-box
                     :spacing 8
                     :alignment :center-left
                     :children [{:fx/type :label
                                 :text "Host:"
                                 :pref-width 50
                                 :style-class ["label-secondary"]}
                                {:fx/type commit-field/commit-field
                                 :id "projector-host-field"
                                 :text (or host "")
                                 :pref-width 150
                                 :on-commit (fn [typed]
                                              (let [new-host (str/trim typed)]
                                                (when-not (str/blank? new-host)
                                                  {:event/type :projectors/update-settings
                                                   :projector-id id
                                                   :updates {:host new-host}})))}
                                {:fx/type :label
                                 :id "projector-port-label"
                                 :text "Port:"
                                 :min-width :use-pref-size
                                 :style-class ["label-secondary"]}
                                {:fx/type commit-field/commit-field
                                 :id "projector-port-field"
                                 :text (str (or port 7255))
                                 :pref-width 60
                                 :on-commit (fn [typed]
                                              (when-let [new-port (parse-port typed)]
                                                {:event/type :projectors/update-settings
                                                 :projector-id id
                                                 :updates {:port new-port}}))}]}
                    ;; Scan rate: sets how long each frame takes to draw
                    {:fx/type :h-box
                     :spacing 8
                     :alignment :center-left
                     :children [{:fx/type :label
                                 :text "Scan rate:"
                                 :style-class ["label-secondary"]}
                                {:fx/type commit-field/commit-field
                                 :id "projector-scan-rate-field"
                                 :text (str stored-rate)
                                 :pref-width 80
                                 :on-commit (fn [typed]
                                              (let [rate (stream/clamp-scan-rate (parse-scan-rate typed))]
                                                (when (and rate (not= rate stored-rate))
                                                  {:event/type :projectors/update-settings
                                                   :projector-id id
                                                   :updates {:scan-rate rate}})))}
                                {:fx/type :label
                                 :text "pts/s"
                                 :style-class ["label-secondary"]}]}]
                   ;; Service info (if multi-output device)
                   (when (and service-id (pos? service-id))
                     [{:fx/type :h-box
                       :spacing 8
                       :alignment :center-left
                       :children [{:fx/type :label
                                   :text "Output:"
                                   :pref-width 50
                                   :style-class ["label-secondary"]}
                                  {:fx/type :label
                                   :text (str "Service #" service-id
                                             (when service-name (str " - " service-name)))
                             :style (str "-fx-text-fill: " (css/selection-bg) "; -fx-font-size: 11;")}]}])))}))


;; The rig: identity, tags and Exclude from All (plans/2026-11-19-zones/04 §7)


(defn- projector-rig-settings
  "How the rig knows the selected projector (its identity), its tags
   (Graphics; Scans the audience, which vibes read) and Exclude from All,
   with an offer to exclude an audience laser that's still in All (Q-Z2-2:
   nothing happens by itself)."
  [{:keys [projector]}]
  (let [{:keys [id tags]} projector
        tags (set tags)
        excluded? (true? (:exclude-from-all? projector))
        tag-box (fn [tag text tip]
                  {:fx/type :check-box
                   :id (str "projector-tag-" (name tag))
                   :text text
                   :selected (contains? tags tag)
                   :tooltip {:fx/type :tooltip :text tip :wrap-text true :max-width 320}
                   :on-selected-changed {:event/type :projectors/set-tag :projector-id id :tag tag :on? :fx/event}})]
    {:fx/type :v-box
     :spacing 6
     :children (cond-> [{:fx/type :label
                         :id "projector-identity-label"
                         :text (str "Identity: " (rig/identity-label projector ", so a new address makes it a new laser"))
                         :wrap-text true
                         :max-width 300
                         :min-height :use-pref-size
                         :style-class ["text-small" "label-secondary"]}
                        {:fx/type :h-box
                         :spacing 12
                         :children [(tag-box :graphics "Graphics" "Graphics: a label for lasers that draw text and logos")
                                    (tag-box :crowd-scanning "Scans the audience"
                                             "Vibes treat every cue that can reach this laser as an audience cue, and only play it when the vibe allows the audience.")]}]
                 (and (contains? tags :crowd-scanning) (not excluded?))
                 (conj {:fx/type :h-box
                        :spacing 8
                        :alignment :center-left
                        :children [{:fx/type :label
                                    :id "projector-audience-in-all"
                                    :text "This laser is in All, so that's every cue that plays on All."
                                    :wrap-text true
                                    :max-width 180
                                    :min-height :use-pref-size
                                    :style (str "-fx-text-fill: " (css/orange) "; -fx-font-size: 11;")}
                                   {:fx/type :button
                                    :id "projector-audience-exclude-button"
                                    :text "Exclude from All"
                                    :min-width :use-pref-size
                                    :style-class ["button-secondary"]
                                    :on-action {:event/type :projectors/set-exclude-from-all :projector-id id :exclude? true}}]})
                 true
                 (conj {:fx/type :check-box
                        :id "projector-exclude-from-all"
                        :text "Exclude from All"
                        :selected excluded?
                        :tooltip {:fx/type :tooltip
                                  :text "Cues that go to All won't play here, and neither will Left, Right, Center, Odd or Even. It still plays its own group and your groups."
                                  :wrap-text true
                                  :max-width 320}
                        :on-selected-changed {:event/type :projectors/set-exclude-from-all :projector-id id :exclude? :fx/event}}))}))


;; The fit guard (plans/2026-11-19-zones/05 §7)


(defn- parse-whole
  "A whole number typed into a field, or nil."
  [text]
  (parse-long (str/trim (str text))))

(defn- fit-number-field
  "A Fit guard number field: shows stored (in the field's units), commits a
   whole number clamped to lo-hi as (to-value n), or puts the stored one back
   when that changes nothing."
  [{:keys [id projector-id fit-key stored lo hi to-value disable]}]
  {:fx/type commit-field/commit-field
   :id id
   :text (str stored)
   :pref-width 50
   :disable disable
   :on-commit (fn [typed]
                (when-let [n (parse-whole typed)]
                  (let [n (max lo (min hi n))]
                    (when (not= n stored)
                      {:event/type :projectors/set-fit :projector-id projector-id
                       :key fit-key :value (to-value n)}))))})

(defn- fit-guard-section
  "FIT GUARD: the selected projector's minimum fps, and what it may do below
   it: draw fewer points (never below a floor), then dim (never below a
   floor). Each control is one :projectors/set-fit, one undo step."
  [{:keys [projector]}]
  (let [{:keys [id]} projector
        {:keys [min-fps reduce-points? point-floor dim? dim-floor]} (fit-guard/settings (:fit projector))
        percent #(Math/round (* 100.0 (double %)))
        row (fn [& children] {:fx/type :h-box :spacing 8 :alignment :center-left :children (vec children)})
        floor (fn [field-id fit-key stored lo disable]
                [{:fx/type :label :text "never below" :min-width :use-pref-size :style-class ["label-secondary"]}
                 {:fx/type fit-number-field :id field-id :projector-id id :fit-key fit-key :stored stored
                  :lo lo :hi 100 :to-value #(/ % 100.0) :disable disable}
                 {:fx/type :label :text "%" :min-width :use-pref-size :style-class ["label-secondary"]}])]
    {:fx/type :v-box
     :id "projector-fit-guard"
     :spacing 6
     :children [{:fx/type :label
                 :text "FIT GUARD"
                 :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                (row {:fx/type :label :text "Minimum fps" :min-width :use-pref-size :style-class ["label-secondary"]}
                     {:fx/type fit-number-field :id "projector-fit-min-fps" :projector-id id :fit-key :min-fps
                      :stored (long min-fps) :lo 5 :hi 60 :to-value identity}
                     {:fx/type :label :text "5–60" :style-class ["label-hint"]})
                (apply row
                       {:fx/type :check-box
                        :id "projector-fit-reduce"
                        :text "Draw fewer points below it"
                        :min-width :use-pref-size
                        :selected reduce-points?
                        :on-selected-changed {:event/type :projectors/set-fit :projector-id id
                                              :key :reduce-points? :value :fx/event}}
                       {:fx/type :region :h-box/hgrow :always}
                       (floor "projector-fit-point-floor" :point-floor (percent point-floor) 30 (not reduce-points?)))
                (apply row
                       {:fx/type :check-box
                        :id "projector-fit-dim"
                        :text "Then dim"
                        :min-width :use-pref-size
                        :selected dim?
                        :on-selected-changed {:event/type :projectors/set-fit :projector-id id
                                              :key :dim? :value :fx/event}}
                       {:fx/type :region :h-box/hgrow :always}
                       (floor "projector-fit-dim-floor" :dim-floor (percent dim-floor) 20 (not dim?)))
                {:fx/type :label
                 :id "projector-fit-note"
                 :text "Below its minimum fps a laser draws fewer points, then dims if you allow it. Its card shows what it did."
                 :wrap-text true
                 :max-width 300
                 :min-height :use-pref-size
                 :style-class ["label-hint"]}]}))


;; Calibration effect helpers


(defn- get-calibration-effects
  "Get all registered calibration effects from the effect registry."
  []
  (effects/list-effects-by-category :calibration))

(defn- calibration-effect-labels
  "Dynamically build map of calibration effect IDs to display labels."
  []
  (u/map-into :id :name (get-calibration-effects)))


;; Effect Parameter Editors
;; Uses shared param-controls with projector-specific event templates


(defn- make-projector-param-events
  "Event templates for a projector effect's parameters: the :chain/* param
   events, as the curves and corner pin editors use, at the effect's full
   path in the chain (an effect inside a group is the one edited).
   Returns map with :on-change-event and :on-text-event."
  [projector-id effect-path]
  {:on-change-event {:event/type :chain/update-param
                     :domain :projector-effects
                     :entity-key projector-id
                     :effect-path effect-path}
   :on-text-event {:event/type :chain/update-param-from-text
                   :domain :projector-effects
                   :entity-key projector-id
                   :effect-path effect-path}})

(defn- standard-effect-params-editor
  "Generic parameter editor using effect registry parameters.
   
   Uses param-controls-list to render all parameters from the effect definition.
   This replaces individual effect-specific editors like rgb-calibration-editor,
   gamma-correction-editor, axis-flip-editor, and rotation-offset-editor."
  [{:keys [projector-id effect-path effect-def params]}]
  (let [{:keys [on-change-event on-text-event]} (make-projector-param-events projector-id effect-path)
        params-map (param-controls/params-vector->map (:parameters effect-def []))]
    {:fx/type param-controls/param-controls-list
     :params-map params-map
     :current-params params
     :on-change-event on-change-event
     :on-text-event on-text-event}))

(defn- effect-parameter-editor
  "Renders the appropriate editor for a selected effect.
   Checks for custom ui-hints renderer, falls back to generic params editor.
   
   Props:
   - :projector-id - ID of the projector
   - :effect-path - The effect's full path in the chain ([1], or [2 :items 0] inside a group)
   - :effect - The effect map
   - :ui-state - UI state for this projector (from projector-effect-ui-state sub)"
  [{:keys [projector-id effect-path effect ui-state]}]
  (let [{:keys [effect-id params]} effect
        effect-def (when effect-id (effects/get-effect effect-id))
        effect-name (or (:name effect-def)
                        (when effect-id (name effect-id))
                        "Unknown Effect")
        renderer-type (get-in effect-def [:ui-hints :renderer])]
    {:fx/type :v-box
     :spacing 8
     :padding 8
     :style-class ["panel-elevated"]
     :children [{:fx/type :label
                 :text (str "Edit: " effect-name)
                 :style-class ["label-bold"]
                 :style "-fx-font-size: 11;"}
                (cond
                   ;; Check for custom renderer in ui-hints: rgb-curves
                   (= :rgb-curves renderer-type)
                   {:fx/type custom-renderers/rgb-curves-visual-editor
                    :current-params params
                    :event-template {:event/type :chain/update-curve-point
                                     :domain :projector-effects
                                     :entity-key projector-id
                                     :effect-path effect-path}
                    :dialog-data ui-state}
                  
                  ;; Check for custom renderer in ui-hints: corner-pin-2d
                  ;; Uses standard chain events - same as all other effect param editors
                  (= :corner-pin-2d renderer-type)
                  {:fx/type custom-renderers/corner-pin-visual-editor
                   :params params
                   :event-template {:event/type :chain/update-spatial-params
                                    :domain :projector-effects
                                    :entity-key projector-id
                                    :effect-path effect-path}
                   :reset-event {:event/type :chain/reset-params
                                 :domain :projector-effects
                                 :entity-key projector-id
                                 :effect-path effect-path
                                 :defaults-map {:tl-x -1.0 :tl-y 1.0
                                                :tr-x 1.0 :tr-y 1.0
                                                :bl-x -1.0 :bl-y -1.0
                                                :br-x 1.0 :br-y -1.0}}
                   :fx-key [:projector projector-id effect-path]
                   :hint-text "Drag corners to adjust output geometry"}
                  
                  ;; Default: use generic param-controls-list based on effect registry
                  ;; This replaces individual editors like rgb-calibration-editor,
                  ;; gamma-correction-editor, axis-flip-editor, rotation-offset-editor
                  effect-def
                  {:fx/type standard-effect-params-editor
                   :projector-id projector-id
                   :effect-path effect-path
                   :effect-def effect-def
                   :params params}
                  
                  ;; No effect definition found
                  :else
                  {:fx/type :label
                   :text "Unknown effect - no parameters available"
                   :style "-fx-text-fill: #808080; -fx-font-style: italic;"})]}))

(defn- projector-effects-sidebar
  "Effect chain sidebar using list-editor with built-in keyboard handling.
   Props:
   - :fx/context - Context for subscriptions
   - :projector-id - ID of the projector
   - :selected-ids - Set of selected item IDs
   - :clipboard-items - Items available for pasting"
  [{:keys [fx/context projector-id selected-ids clipboard-items]}]
  (let [;; Read effects from chains domain, not from projector config
        effects (fx/sub-ctx context subs/projector-effect-chain projector-id)
        ;; Build label map from calibration effects
        labels (calibration-effect-labels)]
    {:fx/type list/list-editor
     :fx/context context  ;; Required for list-sidebar to subscribe to list-ui-state
     :items (or effects [])
     :component-id [:projector-effects projector-id]
     :items-path [:chains :projector-effects projector-id :items]
     ;; Custom label function that looks up effect-id in calibration-effect-labels
     :get-item-label (fn [item]
                       (get labels (:effect-id item) "Unknown Effect"))
     :on-change-event :chain/set-items
     ;; Copied and pasted as effects, so the Paste button (lit by
     ;; clipboard/get-effects-to-paste) and ⌘V paste the same thing
     :on-change-params {:domain :projector-effects :entity-key projector-id :clipboard-type :item-effects}
     :clipboard-items clipboard-items
     :header-label "EFFECTS"
     :empty-text "No calibration effects\nAdd from menu above"}))

(defn- projector-add-effect-menu
  "Menu button for adding calibration effects to a projector.
   Dynamically builds menu from registered calibration effects."
  [{:keys [projector-id]}]
  (let [calibration-effects (get-calibration-effects)
        menu-items (vec (for [effect-def calibration-effects]
                          (let [effect-id (:id effect-def)
                                effect-name (:name effect-def)
                                default-params (effects/get-default-params effect-id)]
                            {:fx/type :menu-item
                             :id (str "projector-add-effect-" (name effect-id))
                             :text effect-name
                             :on-action {:event/type :projectors/add-effect
                                         :projector-id projector-id
                                         :effect {:effect-id effect-id
                                                  :params default-params}}})))]
    {:fx/type :menu-button
     :id "projector-add-effect-menu"
     :text "+ Add Effect"
     :style "-fx-background-color: #505050; -fx-text-fill: white; -fx-font-size: 10;"
     :items menu-items}))

(defn- calibration-controls
  "The TEST PATTERN section for the selected projector: show or hide its test
   pattern (a grid through its calibration and its safety outline, zones plan
   Z1), Identify (a 3 s flash), and the pattern's brightness. A short note
   says why Identify is off, or why the pattern can't be seen (a disabled
   button shows no tooltip)."
  [{:keys [fx/context projector-id]}]
  (let [calibrating-id (fx/sub-ctx context subs/calibrating-projector-id)
        calibrating? (= calibrating-id projector-id)
        brightness (fx/sub-ctx context subs/calibration-brightness)
        projector (fx/sub-val context get-in [:projectors projector-id])
        streaming? (boolean (fx/sub-val context get-in [:backend :streaming :running?]))
        blackout? (boolean (fx/sub-val context get-in [:vibe-runtime :blackout?]))
        enabled? (:enabled? projector true)
        projector-name (or (when (and (string? (:name projector)) (not (str/blank? (:name projector))))
                             (:name projector))
                           (name projector-id))
        note (fn [id text] {:fx/type :label :id id :text text :style-class ["label-hint"] :wrap-text true})
        ;; One reason at a time: with the pattern on and streaming off, the
        ;; pattern's note says it for Identify too
        notes (cond
                (and (not streaming?) calibrating?)
                [(note "projector-pattern-note" "Not streaming: turn on Stream to see it on the laser")]

                (not streaming?)
                [(note "projector-identify-note" "Turn on Stream to see it on the laser")]

                (not enabled?)
                [(note "projector-identify-note" (str projector-name " is turned off"))]

                (and calibrating? blackout?)
                [(note "projector-pattern-note" "BLACKOUT is on (Perform), so the pattern stays dark")])]
    {:fx/type :v-box
     :spacing 6
     :children (vec
                (concat
                 [{:fx/type :label
                   :text "TEST PATTERN"
                   :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                  {:fx/type :h-box
                   :spacing 8
                   :alignment :center-left
                   :children [{:fx/type :button
                               :id "projector-calibration-button"
                               :text (if calibrating? "Hide test pattern" "Show test pattern")
                               :min-width :use-pref-size
                               :style-class [(if calibrating? "button-danger" "button-primary")]
                               :on-action {:event/type :projectors/toggle-calibration
                                           :projector-id projector-id}}
                              {:fx/type :button
                               :id "projector-identify-button"
                               :text "Identify"
                               :min-width :use-pref-size
                               :disable (or (not streaming?) (not enabled?))
                               :style-class ["button-secondary"]
                               :tooltip {:fx/type :tooltip :text "Flash this projector's test pattern for 3 seconds"}
                               :on-action {:event/type :projectors/identify
                                           :projector-id projector-id}}]}]
                 notes
                 (when calibrating?
                   [{:fx/type :h-box
                     :spacing 8
                     :alignment :center-left
                     :children [{:fx/type :label
                                 :text "Brightness:"
                                 :style "-fx-text-fill: #808080; -fx-font-size: 11;"}
                                {:fx/type :slider
                                 :id "calibration-brightness-slider"
                                 :min 0.05
                                 :max 0.5
                                 :value brightness
                                 :pref-width 150
                                 :on-value-changed {:event/type :projectors/set-calibration-brightness
                                                    :brightness :fx/event}}
                                {:fx/type :label
                                 :id "calibration-brightness-label"
                                 :text (format "%.0f%%" (* brightness 100))
                                 :pref-width 40
                                 :style "-fx-text-fill: #808080; -fx-font-size: 10;"}]}])))}))

(defn- inspector-column
  "One of the inspector's columns, in a scroll pane of its own: each one
   scrolls by itself. width fixes it (its vertical scroll bar inside), or
   it takes the rest with :h-box/hgrow on its description."
  [{:keys [id width spacing children]}]
  (cond-> {:fx/type :scroll-pane
           :id id
           :fit-to-width true
           :hbar-policy :never
           :vbar-policy :as-needed
           :style (str "-fx-background-color: " (css/bg-primary) "; -fx-background: " (css/bg-primary) ";")
           :content {:fx/type :v-box
                     :spacing spacing
                     :padding {:top 8 :right 12 :bottom 12 :left 12}
                     :style (str "-fx-background-color: " (css/bg-primary) ";")
                     :children children}}
    width (assoc :min-width width :pref-width width :max-width width)))

(defn- projector-config-panel
  "The inspector: the selected card's settings, its test pattern and
   calibration chain, and its safety zone, below the rig strip, in three
   columns that each scroll by themselves. Uses the self-contained effect
   chain sidebar with callbacks."
  [{:keys [fx/context]}]
  (let [projector (fx/sub-ctx context subs/active-projector)
        projector-id (:id projector)
        ;; Get UI state from list-ui domain (where list/list-editor stores selection)
        ;; Component ID must match what's used in projector-effects-sidebar
        list-component-id (when projector-id [:projector-effects projector-id])
        list-ui-state (when list-component-id
                        (fx/sub-ctx context subs/list-ui-state list-component-id))
        selected-ids (:selected-ids list-ui-state #{})
        ;; Also get projector-effect-ui-state for UI modes (active curve channel, etc.)
        ;; This is separate from selection state and used by effect parameter editors
        effect-ui-state (when projector-id
                          (fx/sub-ctx context subs/projector-effect-ui-state projector-id))
        ;; Read effects from chains domain
        effects (when projector-id
                  (fx/sub-ctx context subs/projector-effect-chain projector-id))
        ;; Get selected effect for parameter editor (first selected if single select)
        first-selected-id (when (= 1 (count selected-ids))
                            (first selected-ids))
        selected-effect-path (when first-selected-id
                               (chains/find-path-by-id effects first-selected-id))
        selected-effect (when selected-effect-path
                          (chains/get-item-at-path effects selected-effect-path))
        clipboard-items (clipboard/get-effects-to-paste)]
    (if projector
      {:fx/type :h-box
       :min-height 80
       :style (str "-fx-background-color: " (css/bg-primary) ";")
       :children [;; Left: the projector's own settings
                  {:fx/type inspector-column
                   :id "projector-column-left"
                   ;; FIT GUARD's rows need 314 px, beside the padding and a scroll bar
                   :width 348
                   :spacing 8
                   :children [{:fx/type :label
                               :id "projector-config-title"
                               :text (str "CONFIGURE: " (:name projector))
                               :style "-fx-text-fill: white; -fx-font-size: 14; -fx-font-weight: bold;"}
                              {:fx/type projector-basic-settings
                               :projector projector}
                              {:fx/type :separator}
                              {:fx/type projector-rig-settings
                               :projector projector}
                              {:fx/type :separator}
                              {:fx/type fit-guard-section
                               :projector projector}]}
                  ;; Middle: its test pattern, its calibration chain, and Remove...
                  {:fx/type inspector-column
                   :id "projector-column-middle"
                   :width 316
                   :spacing 8
                   :children [{:fx/type calibration-controls
                               :fx/context context
                               :projector-id projector-id}
                              {:fx/type :separator}
                              {:fx/type :h-box
                               :alignment :center-left
                               :children [{:fx/type :label
                                           :text "CALIBRATION EFFECTS"
                                           :style "-fx-text-fill: #808080; -fx-font-size: 11; -fx-font-weight: bold;"}
                                          {:fx/type :region :h-box/hgrow :always}
                                          {:fx/type projector-add-effect-menu
                                           :projector-id projector-id}]}
                              {:fx/type projector-effects-sidebar
                               :fx/key projector-id  ;; Force recreation when projector changes to avoid stale closures
                               :fx/context context
                               :projector-id projector-id
                               :selected-ids selected-ids
                               :clipboard-items clipboard-items}
                              {:fx/type :separator}
                              {:fx/type :button
                               :id "projector-remove-button"
                               :text "Remove..."
                               :style-class ["button-danger"]
                               :tooltip {:fx/type :tooltip
                                         :text "Remove this projector and its calibration"}
                               :on-action {:event/type :projectors/request-remove
                                           :projector-id projector-id}}]}
                  ;; Right: the selected effect's editor, and the safety zone (zones plan Z1)
                  {:fx/type inspector-column
                   :id "projector-column-right"
                   :h-box/hgrow :always
                   :spacing 16
                   :children (vec
                              (concat
                               (when selected-effect
                                 [{:fx/type effect-parameter-editor
                                   :projector-id projector-id
                                   :effect-path selected-effect-path
                                   :effect selected-effect
                                   :ui-state effect-ui-state}])
                               [{:fx/type safety-editor/safety-section
                                 :fx/context context
                                 :projector-id projector-id}]))}]}
      ;; No projector selected
      {:fx/type :v-box
       :min-height 80
       :padding 16
       :style (str "-fx-background-color: " (css/bg-primary) ";")
       :children [{:fx/type :label
                   :text "Click a card to set it up"
                   :style "-fx-text-fill: #606060; -fx-font-style: italic;"}]})))


;; The divider between the strip and the inspector


(def ^:private inspector-room
  "The height the inspector gets before the strip grows past its cards,
   while the divider hasn't been moved: its right column down to SAFETY
   ZONE's Copy to… row, with one blocked area: the canvas, and 170 px for
   the column's padding, the section's title, rows and buttons."
  (+ safety-editor/canvas-size 170.0))

(defn- default-divider
  "Where split's divider goes while it hasn't been moved, as a position
   (0-1): the inspector gets inspector-room and the strip the rest, no
   shorter than its cards and no taller than all its rows (the strip's min
   and max height, which the split pane keeps to). nil before split has a
   height."
  [^SplitPane split]
  (let [^Region strip (first (.getItems split))
        thick (or (some-> ^Node (.lookup split ".split-pane-divider") (.prefWidth -1)) 6.0)
        insets (.getInsets split)
        size (- (.getHeight split) (.getTop insets) (.getBottom insets))]
    (when (and strip (pos? size))
      (let [strip-height (max (.getMinHeight strip) (min (.getMaxHeight strip) (- size thick inspector-room)))]
        ;; a position is where the divider's middle goes
        (/ (+ strip-height (/ thick 2.0)) size)))))

(defn- moved?
  "Has the user moved split's divider?"
  [^SplitPane split]
  (true? (.get (.getProperties split) ::moved)))

(defn- follow-default-divider!
  "Until split's divider is moved, put it at default-divider whenever
   split's height changes: when it is first laid out, and as the window is
   resized."
  [^SplitPane split]
  (.addListener (.heightProperty split)
                (reify ChangeListener
                  (changed [_ _ _ _]
                    (when-not (moved? split)
                      (when-let [position (default-divider split)]
                        (.setDividerPositions split (double-array [position]))))))))

(defn- on-divider?
  "Is node one of split's dividers, or inside one?"
  [split node]
  (loop [n node]
    (cond
      (or (not (instance? Node n)) (identical? n split)) false
      (.contains (.getStyleClass ^Node n) "split-pane-divider") true
      :else (recur (.getParent ^Node n)))))

(defn- remember-divider
  "The split pane's event filter: the mouse letting go of the divider (it
   gets the release of the press that dragged it) is the user moving it, so
   it stays there, [:rig-ui :divider], and no longer follows the default. A
   layout pass that moves it, as a resize does, remembers nothing (as
   views/root.clj remember-split does for the preview's divider)."
  [^Event e]
  (when (= MouseEvent/MOUSE_RELEASED (.getEventType e))
    (let [^SplitPane split (.getSource e)]
      (when (on-divider? split (.getTarget e))
        (.put (.getProperties split) ::moved true)
        (events/dispatch! {:event/type :rig/set-divider
                           :position (first (.getDividerPositions split))})))))


;; Main Tab


(defn projectors-tab
  "The Projectors tab: the rig strip on top (views/components/rig_strip), the
   selected card's inspector below, and a divider between them to drag: it
   stays where it is left for the session ([:rig-ui :divider]); until then
   the inspector gets the room its safety zone needs (default-divider)."
  [{:keys [fx/context]}]
  (let [divider (fx/sub-val context get-in [:rig-ui :divider])]
    {:fx/type :v-box
     :style (str "-fx-background-color: " (css/bg-primary) ";")
     :padding 16
     :spacing 6
     :children [{:fx/type :h-box
                 :spacing 12
                 :alignment :baseline-left
                 :children [{:fx/type :label
                             :text "Projectors"
                             :style "-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"}
                            {:fx/type :label
                             :id "projectors-subtitle"
                             :text "Put your lasers in order, left to right. The groups follow the order."
                             :style "-fx-text-fill: #808080; -fx-font-size: 11;"}]}
                ;; The rig file: saved, saving, or why not (zones plan ZR)
                {:fx/type rig-file-status/rig-file-status}
                {:fx/type rig-strip/rig-header}
                {:fx/type fx/ext-on-instance-lifecycle
                 :v-box/vgrow :always
                 ;; Moved before this split pane was made: it takes the position instead
                 :on-created (fn [split] (when-not divider (follow-default-divider! split)))
                 :desc (cond-> {:fx/type :split-pane
                                :id "projectors-split"
                                :orientation :vertical
                                :style (str "-fx-background-color: " (css/bg-primary) "; -fx-padding: 0;")
                                :event-filter remember-divider
                                :items [{:fx/type rig-strip/rig-strip}
                                        {:fx/type projector-config-panel}]}
                         divider (assoc :divider-positions [divider]))}]}))
