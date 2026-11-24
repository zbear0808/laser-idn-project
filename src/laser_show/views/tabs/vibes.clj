(ns laser-show.views.tabs.vibes
  "The Vibes tab: setting up vibe mode. Above, what is left to do. On the
   left the vibes, to choose the one to edit, add one, make one per grid
   row, reorder, duplicate or delete, and the Controls panel. On the right
   the vibe edited: its name, colour and energy, whether it may play cues
   that reach the audience, its slots, the options of the slot shown and,
   for a role, its tap reactions (VIBE's beside the name), and the picker
   that fills it; then what to fix before the show (the lint) and the rules
   of thumb. No preview beside it (views/root.clj): the picker needs the
   width.

   Every control dispatches an event (handlers/vibes.clj, which keeps what
   plays in step with the edits). The view keeps nothing of its own but
   what JavaFX does: the text being typed into the name, and whether the
   rules are open."
  (:require [cljfx.api :as fx]
            [clojure.string :as str]
            [laser-show.subs :as subs]
            [laser-show.vibes.library :as library]
            [laser-show.vibes.reactions :as reactions]
            [laser-show.views.components.applied-value :as applied-value]
            [laser-show.views.components.commit-field :as commit-field]
            [laser-show.views.components.vibe-controls :as vibe-controls]
            [laser-show.views.components.vibe-picker :as vibe-picker]
            [laser-show.views.components.vibe-strip :as vibe-strip]
            [laser-show.views.dialogs.common :as common]))


;; Above: what is left to do

(defn- checklist [{:keys [fx/context]}]
  {:fx/type :h-box
   :id "vibe-checklist"
   :spacing 20
   :children (mapv (fn [{:keys [step text done?]}]
                     {:fx/type :label
                      :id (str "vibe-check-" (name step))
                      :style-class (cond-> ["vibe-check"] done? (conj "vibe-check-done"))
                      :text (str (if done? "✓ " "○ ") text)})
                   (fx/sub-ctx context subs/vibe-checklist))})


;; Left: the vibes

(defn- vibe-card [{:keys [id color energy selected? current?] vibe-name :name}]
  {:fx/type :button
   :fx/key id
   :id (str "vibe-card-" (name id))
   :style-class (cond-> ["vibe-card"] selected? (conj "vibe-card-selected"))
   :focus-traversable true
   :max-width Double/MAX_VALUE
   :alignment :center-left
   :graphic {:fx/type :region
             :style-class "vibe-swatch"
             :style (when color (str "-fx-background-color: " color ";"))
             :min-width 14 :min-height 14 :max-width 14 :max-height 14}
   :text (str vibe-name "  " (vibe-strip/energy-dots energy) (when current? "  · now"))
   :on-action {:event/type :vibe/select-for-edit :vibe id}})

(defn- vibe-list
  "The vibes in their order (VIBE steps through them so), and what to do
   with the one edited."
  [{:keys [fx/context]}]
  (let [vibes (fx/sub-ctx context subs/vibe-list)
        edited (fx/sub-ctx context subs/vibe-edited-id)
        i (first (keep-indexed #(when (:selected? %2) %1) vibes))]
    {:fx/type :v-box
     :id "vibe-list"
     :spacing 6
     :children [{:fx/type :label :style-class "vibe-section-title" :text "VIBES"}
                {:fx/type :h-box
                 :spacing 6
                 :children [{:fx/type :button
                             :id "vibe-add"
                             :style-class "button-primary"
                             :text "New vibe"
                             :on-action {:event/type :vibe/add}}
                            {:fx/type :button
                             :id "vibe-make-from-rows"
                             :style-class "button-secondary"
                             :text "One per grid row"
                             :on-action {:event/type :vibe/make-from-rows}}]}
                {:fx/type :v-box
                 :spacing 4
                 :children (if (seq vibes)
                             (mapv vibe-card vibes)
                             [{:fx/type :label :style-class "label-hint" :text "No vibes yet"}])}
                {:fx/type :h-box
                 :spacing 6
                 :children [{:fx/type :button
                             :id "vibe-move-up"
                             :style-class "button-secondary"
                             :text "↑"
                             :disable (not (and i (pos? i)))
                             :on-action {:event/type :vibe/move :vibe edited :to (dec (or i 0))}}
                            {:fx/type :button
                             :id "vibe-move-down"
                             :style-class "button-secondary"
                             :text "↓"
                             :disable (not (and i (< i (dec (count vibes)))))
                             :on-action {:event/type :vibe/move :vibe edited :to (inc (or i 0))}}
                            {:fx/type :button
                             :id "vibe-duplicate"
                             :style-class "button-secondary"
                             :text "Duplicate"
                             :disable (nil? edited)
                             :on-action {:event/type :vibe/duplicate :vibe edited}}
                            {:fx/type :button
                             :id "vibe-delete"
                             :style-class "button-danger"
                             :text "Delete"
                             :disable (nil? edited)
                             :on-action {:event/type :vibe/remove :vibe edited}}]}]}))


;; Right: the vibe edited

(defn- name-field
  "The vibe's name, applied on Enter or when the field loses the focus, not
   on every key (commit-field): a blank name, or one the app refused (the
   show lock) or spells otherwise, is put back as it is stored."
  [{:keys [vibe text]}]
  {:fx/type commit-field/commit-field
   :id "vibe-name-field"
   :text (or text "")
   :pref-width 200
   :on-commit (fn [typed]
                (when-not (str/blank? typed)
                  {:event/type :vibe/rename :vibe vibe :name typed}))})

(defn- color-swatches [{:keys [vibe color]}]
  {:fx/type :h-box
   :spacing 4
   :alignment :center-left
   :children (vec (map-indexed (fn [i c]
                                 {:fx/type :button
                                  :id (str "vibe-color-" i)
                                  :style-class (cond-> ["vibe-swatch"] (= c color) (conj "vibe-swatch-selected"))
                                  :style (str "-fx-background-color: " c ";")
                                  :focus-traversable false
                                  :min-width 18 :min-height 18 :max-width 18 :max-height 18
                                  :on-action {:event/type :vibe/set-color :vibe vibe :color c}})
                               library/palette))})

(defn- energy-buttons [{:keys [vibe energy]}]
  {:fx/type :h-box
   :alignment :center-left
   :children (into [{:fx/type :label :style-class "label-secondary" :text "Energy "}]
                   (map (fn [n]
                          {:fx/type :button
                           :id (str "vibe-energy-" n)
                           :style-class "vibe-energy"
                           :focus-traversable false
                           :text (if (<= n (or energy 0)) "●" "○")
                           :on-action {:event/type :vibe/set-energy :vibe vibe :energy n}}))
                   (range 1 5))})


;; Tap reactions (plans/2026-11-18-vibe-mode/04-phase4-tap-reactions.md)

(def ^:private role-names
  {:hit "HIT" :accent "ACCENT" :flash "FLASH" :vibe "VIBE" :any "HIT, ACCENT or FLASH"})

(defn- tap-menu
  "Tap reactions ▾: the templates (vibes.reactions) that follow roles, under
   a heading per role. A choice makes one for the vibe (:vibe/add-reaction).
   A zone reaction the rig has too few places for is greyed, its hint after
   its text (hints: subs/vibe-tap-hints)."
  [vibe menu-id roles hints]
  {:fx/type :menu-button
   :id menu-id
   :style-class ["menu-button" "vibe-option"]
   :focus-traversable false
   :text "Tap reactions"
   :items (into []
                (comp (map-indexed (fn [i role]
                                     (concat (when (pos? i) [{:fx/type :separator-menu-item}])
                                             [{:fx/type :menu-item :text (role-names role) :disable true}]
                                             (for [{:keys [kind menu] :as template} reactions/templates
                                                   :when (= role (:role template))
                                                   :let [hint (get hints kind)]]
                                               {:fx/type :menu-item
                                                :id (str "vibe-tap-" (name kind))
                                                :text (if hint (str menu " — " hint) menu)
                                                :disable (some? hint)
                                                :on-action {:event/type :vibe/add-reaction :vibe vibe :kind kind}}))))
                      cat)
                roles)})

(defn- role-reactions
  "A role's tap reactions in the vibe edited: the menu that makes one (of
   the templates that follow roles), and the names of its cells that move
   on the role's press (subs/vibe-edited-reactions)."
  [{:keys [fx/context vibe role roles]}]
  (let [moves (get (fx/sub-ctx context subs/vibe-edited-reactions) role)]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [(tap-menu vibe (str "vibe-tap-menu-" (name role)) roles (fx/sub-ctx context subs/vibe-tap-hints))
                {:fx/type :label
                 :id (str "vibe-reactions-" (name role))
                 :style-class (if (seq moves) "label-secondary" "label-hint")
                 :text (if (seq moves) (str/join " · " (map :name moves)) "none yet")}]}))

(defn- editor-header [{:keys [fx/context]}]
  (let [{:keys [id color energy allow-audience? current?] vibe-name :name} (fx/sub-ctx context subs/vibe-editor)]
    {:fx/type :v-box
     :spacing 8
     :children [{:fx/type :h-box
                 :spacing 14
                 :alignment :center-left
                 :children [{:fx/type name-field :fx/key id :vibe id :text vibe-name}
                            {:fx/type color-swatches :vibe id :color color}
                            {:fx/type energy-buttons :vibe id :energy energy}
                            {:fx/type role-reactions :vibe id :role :vibe :roles [:vibe]}
                            {:fx/type :label
                             :id "vibe-editor-now"
                             :style-class "vibe-editor-now"
                             :text (if current? "plays now" "")}]}
                {:fx/type applied-value/applied-control
                 ;; Keyed by the vibe, so its listener is never a vibe late
                 :fx/key id
                 :property :selected
                 :address [id :allow-audience?]
                 :value allow-audience?
                 :on-change (fn [on?] {:event/type :vibe/set-option :vibe id :key :allow-audience? :value on?})
                 :desc {:fx/type :check-box
                        :id "vibe-allow-audience"
                        :style-class ["check-box" "vibe-check-box"]
                        :text "Allow audience cells: cues that can reach the crowd may play in this vibe"}}]}))

(def ^:private slot-names
  {:hit "HIT" :accent "ACCENT" :flash-cue "FLASH cue" :bed "BED" :fx "FX set" :flash-fx "FLASH effects"})

(defn- slot-text [vibe slot]
  (let [cells (library/slot-cells vibe slot)]
    (str (slot-names slot) "  "
         (if (#{:flash-cue :bed} slot)
           (if-let [[col row] (first cells)] (common/cell-label col row) "–")
           (count cells)))))

(defn- slot-tabs [{:keys [fx/context]}]
  (let [{:keys [slot] :as vibe} (fx/sub-ctx context subs/vibe-editor)]
    {:fx/type :h-box
     :id "vibe-slots"
     :spacing 2
     :children (mapv (fn [s]
                       {:fx/type :button
                        :id (str "vibe-slot-" (name s))
                        :style-class (cond-> ["vibe-slot"] (= s slot) (conj "vibe-slot-active"))
                        :focus-traversable false
                        :text (slot-text vibe s)
                        :on-action {:event/type :vibe/set-slot :slot s}})
                     library/slots)}))

(defn- option [id text active? event]
  {:fx/type :button
   :id id
   :style-class (cond-> ["vibe-option"] active? (conj "vibe-option-active"))
   :focus-traversable false
   :text text
   :on-action event})

(def ^:private modes
  [[:decay "Decay" "ends after its beats, or when let go of after a hold"]
   [:gate "Gate" "plays while held"]
   [:sustain "Sustain" "plays until the next press"]])

(def ^:private beat-options [[0.5 "half" "½"] [1.0 "1" "1"] [2.0 "2" "2"] [4.0 "4" "4"] [8.0 "8" "8"]])

(defn- pool-options
  "HIT's or ACCENT's: how long a cue plays, and in what order they come."
  [{:keys [id role mode beats select]}]
  (let [mode (or mode :decay)
        set-option (fn [k v] {:event/type :vibe/set-option :vibe id :role role :key k :value v})
        label (fn [text] {:fx/type :label :style-class "label-secondary" :text text})]
    {:fx/type :h-box
     :spacing 6
     :alignment :center-left
     :children (-> [(label "Plays")]
                   (into (map (fn [[m text]] (option (str "vibe-mode-" (name m)) text (= m mode) (set-option :mode m)))) modes)
                   (conj (label (some (fn [[m _ hint]] (when (= m mode) hint)) modes)))
                   (cond-> (= :decay mode)
                     (-> (conj (label "   Beats"))
                         (into (map (fn [[v id-part text]]
                                      (option (str "vibe-beats-" id-part) text (== v (or beats 1.0)) (set-option :beats v))))
                               beat-options)))
                   (conj (label "   Order"))
                   (into (map (fn [[s text]] (option (str "vibe-select-" (name s)) text (= s (or select :shuffle)) (set-option :select s))))
                         [[:shuffle "Shuffle"] [:sequence "In order"]]))}))

(defn- slot-options [{:keys [fx/context]}]
  (let [{:keys [id slot] :as vibe} (fx/sub-ctx context subs/vibe-editor)
        note (fn [text] {:fx/type :label :id "vibe-options" :style-class "label-secondary" :text text})
        with-reactions (fn [options role]
                         {:fx/type :v-box
                          :spacing 8
                          :children [options {:fx/type role-reactions :vibe id :role role :roles [role :any]}]})]
    (case slot
      (:hit :accent) (with-reactions (assoc (get vibe slot) :fx/type pool-options :id id :role slot) slot)
      (:flash-cue :flash-fx) (with-reactions
                               {:fx/type :h-box
                                ;; Keyed by the vibe, so its listener is never a vibe late
                                :children [{:fx/type applied-value/applied-control
                                            :fx/key id
                                            :property :selected
                                            :address [id :flash :solo?]
                                            :value (get-in vibe [:flash :solo?])
                                            :on-change (fn [on?] {:event/type :vibe/set-option :vibe id :role :flash
                                                                  :key :solo? :value on?})
                                            :desc {:fx/type :check-box
                                                   :id "vibe-solo"
                                                   :style-class ["check-box" "vibe-check-box"]
                                                   :text "Solo: HIT, ACCENT and the bed step aside while FLASH is held"}}]}
                               :flash)
      :bed (note "The bed plays all through the vibe, under the hits")
      :fx (note "The FX set is on while the vibe plays")
      (note ""))))

(def ^:private lint-marks {:error "✕" :warn "⚠" :info "ⓘ"})

(defn- lint [{:keys [fx/context]}]
  (let [rows (fx/sub-ctx context subs/vibe-lint)]
    {:fx/type :v-box
     :id "vibe-lint"
     :spacing 3
     :children (into [{:fx/type :label :style-class "vibe-section-title" :text "BEFORE THE SHOW"}]
                     (if (seq rows)
                       (map (fn [{:keys [level text]}]
                              {:fx/type :label
                               :style-class ["vibe-lint-row" (str "vibe-lint-" (name level))]
                               :wrap-text true
                               :graphic {:fx/type :label
                                         :style-class ["vibe-lint-mark" (str "vibe-lint-" (name level))]
                                         :text (lint-marks level)}
                               :text text})
                            rows)
                       [{:fx/type :label :style-class "label-hint" :text "Nothing to fix"}]))}))

(def ^:private rules
  "plans/2026-11-18-vibe-mode/00-overview.md §4."
  ["One feel per vibe: the same colours and energy; the shapes may differ."
   "3 to 6 cues in HIT. Two repeat too visibly, and ten hide what you could cut."
   "ACCENT: one to three bigger or rarer cues."
   "FLASH: one dramatic cue, or a set of effects, not both."
   "Keep cues that reach the audience out of the pools."
   "4 to 6 vibes in a set, from low energy to high. Name them by feel, not by shape."
   (str "One or two tap reactions per role: a Punch or a Step on HIT, a Flip or a Scatter on ACCENT, a Swell or a "
        "Strobe on FLASH, a Fade in on VIBE. More blur together. To combine two on one parameter, stack two effects "
        "(two Scales multiply).")])

(defn- rules-of-thumb [_]
  {:fx/type :titled-pane
   :id "vibe-rules"
   :style-class ["titled-pane" "vibe-rules"]
   :text "Rules of thumb"
   :expanded false
   :animated false
   :content {:fx/type :v-box
             :spacing 4
             :padding 8
             :children (mapv (fn [rule] {:fx/type :label :style-class "vibe-rule" :wrap-text true :text (str "• " rule)})
                             rules)}})

(defn- no-vibes [_]
  {:fx/type :label
   :style-class "label-hint"
   :wrap-text true
   :text "No vibes yet. Make a new vibe, or one per grid row from the cues you have."})


(defn vibes-tab
  "The Vibes tab: the checklist above, the vibes and the controls on the
   left, the vibe edited and its picker on the right."
  [{:keys [fx/context]}]
  (let [vibe? (some? (fx/sub-ctx context subs/vibe-edited-id))]
    {:fx/type :v-box
     :id "vibes-content"
     :spacing 12
     :padding 16
     :children [{:fx/type checklist}
                {:fx/type :h-box
                 :spacing 20
                 :v-box/vgrow :always
                 :children [{:fx/type :scroll-pane
                             :style-class ["scroll-pane" "scroll-pane-dark"]
                             :fit-to-width true
                             :min-width 300
                             :pref-width 310
                             :max-width 340
                             :hbar-policy :never
                             :content {:fx/type :v-box
                                       :spacing 18
                                       :padding {:right 8}
                                       :children [{:fx/type vibe-list}
                                                  {:fx/type vibe-controls/controls-panel}]}}
                            {:fx/type :scroll-pane
                             :style-class ["scroll-pane" "scroll-pane-dark"]
                             :fit-to-width true
                             :h-box/hgrow :always
                             :content {:fx/type :v-box
                                       :id "vibe-editor"
                                       :spacing 10
                                       :padding {:right 8}
                                       :children (if vibe?
                                                   [{:fx/type editor-header}
                                                    {:fx/type slot-tabs}
                                                    {:fx/type slot-options}
                                                    {:fx/type vibe-picker/picker}
                                                    {:fx/type lint}
                                                    {:fx/type rules-of-thumb}]
                                                   [{:fx/type no-vibes}
                                                    {:fx/type lint}
                                                    {:fx/type rules-of-thumb}])}}]}]}))
