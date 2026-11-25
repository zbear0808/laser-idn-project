(ns laser-show.events.handlers.cue-chain
  "Event handlers for cue chain operations (presets and groups).
   
   The cue chain editor allows building complex cues by combining multiple
   presets with per-preset effect chains.
   
   This file handles cue-chain-specific operations:
   - Editor lifecycle (open)
   - Preset management (add)
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
            [laser-show.animation.cue-chains :as cue-chains]
            [laser-show.animation.effects :as effects]
            [laser-show.routing.targets :as targets]))


;; Path Helpers


(defn- cue-chain-path
  "Get the path to a grid cell's cue chain in state.
   Uses new unified :chains domain structure."
  [col row]
  [:chains :cue-chains [col row] :items])


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
  "Add the effect :item-id to a cue chain item's effects (:parent-path, e.g.
   [0 :effects]) from the effect bank, with its definition's defaults as
   they are when the click is handled (effects/get-default-params: the
   bank's event carries the id alone, reactivity plan 17); an id no effect
   has adds nothing.
   
   Auto-selects the newly added effect for better UX.
   
   Requires cell to exist - errors if [:chains :cue-chains [col row]] is nil."
  [{:keys [col row parent-path item-id state]}]
  (if-let [params-map (effects/get-default-params item-id)]
    (let [new-effect (h/ensure-item-fields {:effect-id item-id
                                            :params params-map})
          new-effect-id (:id new-effect)
          ;; parent-path should be something like [0 :effects]
          target-path (vec (concat [:chains :cue-chains [col row] :items] parent-path))
          current-effects (get-in state target-path [])]
      {:state (-> state
                  (assoc-in target-path (conj current-effects new-effect)))
       :dispatch {:event/type :list/select-item
                  :component-id [:item-effects col row]
                  :item-id new-effect-id
                  :mode :single}})
    {:state state}))

;; NOTE: Preset parameter handlers have been removed - now using generic :chain/* events
;; from chain.clj with {:domain :cue-chains :entity-key [col row] :effect-path preset-path}
;; This includes :chain/update-param, :chain/update-param-from-text, :chain/update-color-param


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
  "Set the entire effects array for a cue chain item (simple persistence callback).
   
   Requires cell to exist - errors if [:chains :cue-chains [col row]] is nil."
  [{:keys [col row item-path items state]}]
  (let [effects-path (vec (concat (cue-chain-path col row) item-path [:effects]))]
    {:state (-> state
                (assoc-in effects-path items))}))


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
  "Set or clear the target of the preset or group at :item-path (a path
   into :items), from :target or a picker's :fx/event, in normal form;
   none (nil, [], :inherit) removes it, so the item inherits."
  [{:keys [col row item-path state] :as event}]
  (let [path (into [:chains :cue-chains [col row] :items] item-path)]
    (if (and (some? col) (seq item-path) (map? (get-in state path)))
      {:state (update-in state path with-target (chosen-target event))}
      {:state state})))


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
    
    ;; Name
    :cue-chain/set-name (handle-cue-chain-set-name event)
    
    ;; Trigger mode
    :cue-chain/set-trigger-mode (handle-cue-chain-set-trigger-mode event)
    
    ;; Unknown event in this domain
    {}))
