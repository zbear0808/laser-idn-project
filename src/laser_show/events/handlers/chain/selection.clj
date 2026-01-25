(ns laser-show.events.handlers.chain.selection
  "Selection-related handlers for chain operations.
   
   Contains:
   - Selection operations (select-item, select-all, clear-selection)
   - Rename operations (start-rename, cancel-rename, rename-item)
   - UI mode (set-ui-mode)
   - Enabled state (set-item-enabled)"
  (:require
   [clojure.tools.logging :as log]
   [laser-show.events.helpers :as h]
   [laser-show.events.handlers.chain.helpers :as helpers]
   [laser-show.animation.chains :as chains]))


;; ============================================================================
;; Selection Operations
;; ============================================================================


(defn handle-select-item
  "Generic item selection handler with Ctrl/Shift support.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :items-path and :ui-path
   - path: Path to select (e.g., [0] or [1 :items 0])
   - ctrl?: If true, toggle selection
   - shift?: If true, range select from anchor
   
   Returns: Updated state"
  [state config path ctrl? shift?]
  (log/debug "handle-select-item: path=" path "ctrl?=" ctrl? "shift?=" shift?)
  (let [current-paths (helpers/get-selected-paths state config)
        last-selected (helpers/get-last-selected-path state config)
        items-vec (helpers/get-items state config)
        new-paths (cond
                    ;; Ctrl+click: toggle selection
                    ctrl?
                    (if (contains? current-paths path)
                      (disj current-paths path)
                      (conj current-paths path))
                    
                    ;; Shift+click with anchor: range select
                    (and shift? last-selected)
                    (let [all-paths (vec (chains/paths-in-chain items-vec))
                          anchor-idx (.indexOf all-paths last-selected)
                          target-idx (.indexOf all-paths path)]
                      (if (and (>= anchor-idx 0) (>= target-idx 0))
                        (let [start-idx (min anchor-idx target-idx)
                              end-idx (max anchor-idx target-idx)]
                          (into #{} (subvec all-paths start-idx (inc end-idx))))
                        ;; Fallback if paths not found - just select clicked
                        #{path}))
                    
                    ;; Shift+click without anchor, or regular click: select single
                    :else
                    #{path})
        ;; Only update anchor on regular click or ctrl+click, NOT on shift+click
        update-anchor? (not shift?)]
    (-> state
        (helpers/set-selected-paths config new-paths)
        (cond-> update-anchor?
          (helpers/set-last-selected-path config path)))))

(defn handle-select-all
  "Generic select-all handler.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (let [items-vec (helpers/get-items state config)
        all-paths (into #{} (chains/paths-in-chain items-vec))]
    (helpers/set-selected-paths state config all-paths)))

(defn handle-clear-selection
  "Generic clear-selection handler.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (-> state
      (helpers/set-selected-paths config #{})
      (helpers/set-last-selected-path config nil)))


;; ============================================================================
;; Rename Operations
;; ============================================================================


(defn handle-start-rename
  "Start renaming a group (shows inline text field).
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - path: Path to the group
   
   Returns: Updated state"
  [state config path]
  (helpers/set-renaming-path state config path))

(defn handle-cancel-rename
  "Cancel renaming a group.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   
   Returns: Updated state"
  [state config]
  (helpers/set-renaming-path state config nil))

(defn handle-rename-item
  "Rename a group.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - path: Path to the group
   - new-name: New name for the group
   
   Returns: Updated state"
  [state config path new-name]
  (let [items-vec (helpers/get-items state config)
        item (get-in items-vec path)]
    (if (chains/group? item)
      (-> state
          (assoc-in (into (:items-path config) (conj path :name)) new-name)
          (helpers/set-renaming-path config nil)
          h/mark-dirty)
      state)))


;; ============================================================================
;; UI Mode Operations
;; ============================================================================


(defn handle-set-ui-mode
  "Set visual/numeric mode for effect parameter UI.
   
   Parameters:
   - state: Application state
   - config: Configuration map with :ui-path
   - effect-path: Path to the effect
   - mode: :visual or :numeric
   
   Returns: Updated state"
  [state config effect-path mode]
  (assoc-in state (conj (:ui-path config) :ui-modes effect-path) mode))


;; ============================================================================
;; Enabled State Operations
;; ============================================================================


(defn handle-set-item-enabled
  "Set the enabled state of an item at a path.
   
   Parameters:
   - state: Application state
   - config: Configuration map
   - path: Path to the item
   - enabled?: New enabled state
   
   Returns: Updated state"
  [state config path enabled?]
  (-> state
      (assoc-in (into (:items-path config) (conj (vec path) :enabled?)) enabled?)
      (h/mark-dirty)))
