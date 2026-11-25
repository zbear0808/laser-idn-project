(ns laser-show.events.handlers.cue-chain
  "Event handlers for cue chain operations (presets and groups).
   
   The cue chain editor allows building complex cues by combining multiple
   presets with per-preset effect chains.
   
   This file handles cue-chain-specific operations:
   - Editor lifecycle (open)
   - Preset management (add)
   - A preset's own parameters and colour, by the item's :id and the cue's
     cell, found where the item is when the edit is handled (follow-up N10)
   - An item's effects: its effects list's changes and the effect bank's
     adds, by the item's :id the same way (follow-up N14)
   - Tab switching (preset banks, effect banks)
   - Targets: the cue's and each item's (zones plan Z4)
   
   Effect-level operations have moved to chain.clj:
   - Effect CRUD (add, remove, set-enabled)
   - Parameter updates (update-param, update-param-from-text)
   - Spatial and curve editing
   - UI mode switching
   
   UI components should use :chain/* events with {:domain :cue-chains}
   for cue-chain item operations and effect parameter editing."
  (:require [clojure.tools.logging :as log]
            [laser-show.events.helpers :as h]
            [laser-show.animation.chains :as chains]
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.animation.effects :as effects]
            [laser-show.events.handlers.chain.helpers :as chain-helpers]
            [laser-show.events.handlers.chain.params :as params]
            [laser-show.routing.targets :as targets]))


;; Path Helpers


(defn- cue-chain-path
  "Get the path to a grid cell's cue chain in state.
   Uses new unified :chains domain structure."
  [col row]
  [:chains :cue-chains [col row] :items])

(defn- cue-item-path
  "Where the item or group item-id of the cue at [col row] is in app state
   now (chains/find-path-by-id, groups searched), or nil once it is gone:
   an edit made before a reorder finds its own item (follow-up N14)."
  [state col row item-id]
  (let [items-path (cue-chain-path col row)
        items (get-in state items-path)]
    (when (and (some? item-id) (sequential? items))
      (some->> (chains/find-path-by-id (vec items) item-id) (into items-path)))))


;; Editor Lifecycle


(defn- handle-cue-chain-open-editor
  "Open the cue chain editor for a specific cell with rename mode off.
   FLATTENED: No :data nesting - fields directly in dialog map.

   Selection state is now stored in list-ui domain at [:list-ui [:cue-chain col row]].
   Uses :list/auto-select-single-item to auto-select if only 1 item exists."
  [{:keys [col row state initial-tab]}]
  (log/debug "Opening cue chain editor" {:col col :row row})
  {:state (-> state
              (update-in [:ui :dialogs :cue-chain-editor] merge
                         {:open? true
                          :col col
                          :row row
                          :active-preset-tab (or initial-tab :geometric)
                          :selected-effect-id nil
                          :item-effects-ui {}
                          :editing-name? false}))
   :dispatch {:event/type :list/auto-select-single-item
              :component-id [:cue-chain col row]
              :items-path [:chains :cue-chains [col row] :items]}})



;; Preset Management


(defn- handle-cue-chain-add-preset
  "Add a preset to the cue chain.
   
   Expects :item-id with the preset ID (e.g., :circle, :wave): the bank's
   event carries the id alone (reactivity plan 17).
   
   Auto-selects the newly added preset for better UX.
   
   Requires cell to exist - errors if [:chains :cue-chains [col row]] is nil."
  [{:keys [col row item-id state]}]
  (let [new-preset (h/ensure-item-fields (cue-chains/create-preset-instance item-id {}))
        new-preset-id (:id new-preset)]
    {:state (-> state
                (update-in (cue-chain-path col row) conj new-preset))
     :dispatch {:event/type :list/select-item
                :component-id [:cue-chain col row]
                :item-id new-preset-id
                :mode :single}}))

(defn- handle-cue-chain-add-effect-from-bank
  "Add the effect :item-id from the effect bank to the effects of the cue
   item or group :owner-id of the cue at [col row], found where it is when
   the click is handled (follow-up N14: a click queued behind a reorder adds
   to its own item, and one for an item that is gone adds nothing, {}),
   with its definition's defaults as they are then
   (effects/get-default-params: the bank's event carries the id alone,
   reactivity plan 17); an id no effect has adds nothing.
   
   Auto-selects the newly added effect for better UX."
  [{:keys [col row owner-id item-id state]}]
  (if-let [owner-path (cue-item-path state col row owner-id)]
    (if-let [params-map (effects/get-default-params item-id)]
      (let [new-effect (h/ensure-item-fields {:effect-id item-id
                                              :params params-map})
            new-effect-id (:id new-effect)
            target-path (conj owner-path :effects)
            current-effects (get-in state target-path [])]
        {:state (-> state
                    (assoc-in target-path (conj current-effects new-effect)))
         :dispatch {:event/type :list/select-item
                    :component-id [:item-effects col row]
                    :item-id new-effect-id
                    :mode :single}})
      {:state state})
    {}))

;; A preset's parameters (follow-up N10): the item by its id, as its target
;; is (R2). The preset editor sends the cue's cell and the item's :id, never
;; its place, so an edit made before a reorder edits its own item.


(defn- preset-edit
  "The effects of an edit of the preset :item-id of the cue at [col row]:
   (f state config path), path where the item is when the event is handled
   (chains/find-path-by-id), as {:state}. {} once the item is gone (or is
   no preset), or when the edit changes nothing."
  [{:keys [col row item-id state]} f]
  (let [config (chain-helpers/chain-config :cue-chains [col row])
        items (get-in state (:items-path config))
        path (when (and (some? item-id) (sequential? items))
               (chains/find-path-by-id (vec items) item-id))
        item-path (when path (into (:items-path config) path))]
    (if (and path (some? (:preset-id (get-in state item-path))))
      (let [new-state (f state config path)]
        (if (= (get-in state item-path) (get-in new-state item-path))
          {}
          {:state new-state}))
      {})))

(defn- handle-update-preset-param
  "Set a parameter (:param-key) of the preset :item-id of the cue at [col
   row] to :fx/event or :value, a slider's, a choice's or a check box's (a
   whole number for an :int parameter: params/handle-update-param)."
  [event]
  (preset-edit event #(params/handle-update-param %1 %2 (assoc (select-keys event [:param-key :fx/event :value])
                                                               :effect-path %3))))

(defn- handle-update-preset-param-from-text
  "Set a parameter of the preset :item-id from its text field's typed :text
   (params/handle-update-param-from-text, with :min, :max and :is-int?)."
  [event]
  (preset-edit event #(params/handle-update-param-from-text %1 %2 (assoc (select-keys event [:param-key :min :max :is-int? :text])
                                                                         :effect-path %3))))

(defn- handle-update-preset-color
  "Set the colour (:red :green :blue) of the preset :item-id to the
   picked :color [red green blue] (params/color-value, params/write-color)."
  [event]
  (preset-edit event #(params/write-color %1 %2 %3 (params/color-value event))))


;; Tab Switching


(defn- handle-cue-chain-set-preset-tab
  "Set the active preset bank tab.
   FLATTENED: No :data nesting."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :active-preset-tab] tab-id)})

(defn- handle-cue-chain-set-effect-tab
  "Set the active effect bank tab.
   FLATTENED: No :data nesting."
  [{:keys [tab-id state]}]
  {:state (assoc-in state [:ui :dialogs :cue-chain-editor :active-effect-tab] (or tab-id :shape))})


;; Hierarchical List Integration (Item Effects)


(defn- handle-cue-chain-set-item-effects
  "Set the effects of the cue item or group :item-id of the cue at [col
   row] to :items: what its item effects list sends for each change (a
   drop, a delete, a paste...). The item is found where it is when the
   change is handled (follow-up N14: one made before a reorder lands on its
   own item); one that is gone, or another cue's, changes nothing ({})."
  [{:keys [col row item-id items state]}]
  (if-let [item-path (cue-item-path state col row item-id)]
    {:state (assoc-in state (conj item-path :effects) items)}
    {}))


;; Targets (zones plan Z4): where the cue's content goes


(defn- chosen-target
  "The target an event sets: its :target, else a picker's choice (the :id
   of its :fx/event), :inherit meaning none; in normal form."
  [{:keys [fx/event] :as e}]
  (let [t (if (contains? e :target) (:target e) (:id event))]
    (when-not (= :inherit t)
      (targets/normalize-target t))))

(defn- with-target
  "m with target t, or without :target for none (it inherits)."
  [m t]
  (if (some? t) (assoc m :target t) (dissoc m :target)))

(defn- handle-cue-chain-set-target
  "Set the cue's own target (:target, or a picker's :fx/event), in normal
   form; none removes it, so the cue follows its column, the show, then
   All. A cell with no cue changes nothing."
  [{:keys [col row state] :as event}]
  (if-let [cue (get-in state [:chains :cue-chains [col row]])]
    {:state (assoc-in state [:chains :cue-chains [col row]] (with-target cue (chosen-target event)))}
    {:state state}))

(defn- handle-cue-chain-set-item-target
  "Set or clear the target of the preset or group :item-id of the cue at
   [col row], from :target or a picker's :fx/event, in normal form; none
   (nil, [], :inherit) removes it, so the item inherits. The item is found
   where it is when the event is handled (follow-up R2: a choice made
   before a reorder sets its own item). One that isn't there changes
   nothing ({})."
  [{:keys [col row item-id state] :as event}]
  (let [items-path (cue-chain-path col row)
        item-path (when (some? item-id)
                    (chains/find-path-by-id (get-in state items-path) item-id))]
    (if item-path
      {:state (update-in state (into items-path item-path) with-target (chosen-target event))}
      {})))


;; Name Operations


(defn- handle-cue-chain-set-name
  "Set the name for a cue chain.
   Also clears the editing state of :dialog-id (default :cue-chain-editor)."
  [{:keys [col row name dialog-id state]}]
  (let [name-path [:chains :cue-chains [col row] :name]]
    {:state (-> state
                (assoc-in name-path (when (seq name) name))
                (assoc-in [:ui :dialogs (or dialog-id :cue-chain-editor) :editing-name?] false))}))


;; Trigger Mode Operations


(defn- handle-cue-chain-set-trigger-mode
  "Set the trigger mode for a cue chain.
   
   Trigger modes:
   - :toggle (default) - Click to turn ON, click again to turn OFF
   - :retrigger - Always start/restart the cue on click"
  [{:keys [col row fx/event state]}]
  (let [;; Extract :id from fx/event (combo-box selected value)
        mode (or (:mode event) (:id event) :toggle)
        mode-path [:chains :cue-chains [col row] :trigger-mode]]
    (log/debug "Setting per-cue trigger mode:" {:col col :row row :mode mode :fx-event event})
    {:state (-> state
                (assoc-in mode-path mode))}))


;; Public API


(defn handle
  "Dispatch cue chain events to their handlers.
   
   Accepts events with :event/type in the :cue-chain/* namespace.
   
   Note: Effect CRUD, parameter, curve, and UI mode operations should use :chain/* events:
   - :chain/add-item, :chain/set-items
   - :chain/update-param, :chain/update-param-from-text
   - :chain/add-curve-point, :chain/update-curve-point, :chain/remove-curve-point
   - :chain/set-active-curve-channel
   - :chain/update-spatial-params
   - :chain/set-ui-mode
   
   Pass {:domain :cue-chains :entity-key [col row]} to these events."
  [{:keys [event/type] :as event}]
  (case type
    ;; Editor lifecycle
    :cue-chain/open-editor (handle-cue-chain-open-editor event)
    
    ;; Preset management
    :cue-chain/add-preset (handle-cue-chain-add-preset event)
    
    ;; Effect bank (data-driven) - add effect to cue chain item
    :cue-chain/add-effect-from-bank (handle-cue-chain-add-effect-from-bank event)
    
    ;; Tab switching
    :cue-chain/set-preset-tab (handle-cue-chain-set-preset-tab event)
    :cue-chain/set-effect-tab (handle-cue-chain-set-effect-tab event)
    
    ;; Hierarchical list integration
    :cue-chain/set-item-effects (handle-cue-chain-set-item-effects event)
    
    ;; Targets
    :cue-chain/set-target (handle-cue-chain-set-target event)
    :cue-chain/set-item-target (handle-cue-chain-set-item-target event)
    
    ;; A preset's parameters, by the item's id
    :cue-chain/update-preset-param (handle-update-preset-param event)
    :cue-chain/update-preset-param-from-text (handle-update-preset-param-from-text event)
    :cue-chain/update-preset-color (handle-update-preset-color event)
    
    ;; Name
    :cue-chain/set-name (handle-cue-chain-set-name event)
    
    ;; Trigger mode
    :cue-chain/set-trigger-mode (handle-cue-chain-set-trigger-mode event)
    
    ;; Unknown event in this domain
    {}))
