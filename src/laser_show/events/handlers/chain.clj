(ns laser-show.events.handlers.chain
  "Re-exports from chain modules for backwards compatibility.
   
   The chain handler implementation has been split into focused modules:
   - chain/helpers.clj   - State access, path manipulation, config (~200 lines)
   - chain/core.clj      - Main dispatch router (~130 lines)
   - chain/selection.clj - Selection, rename, UI mode (~150 lines)
   - chain/structure.clj - Groups, items, copy/paste, DnD (~350 lines)
   - chain/params.clj    - Parameter operations (~150 lines)
   
   This namespace re-exports the public API so existing code continues to work."
  (:require
   [laser-show.events.handlers.chain.core :as core]
   [laser-show.events.handlers.chain.helpers :as helpers]
   [laser-show.events.handlers.chain.selection :as selection]
   [laser-show.events.handlers.chain.structure :as structure]
   [laser-show.events.handlers.chain.params :as params]))

;; Re-export main dispatch router
(def handle core/handle)

;; Re-export config factory
(def chain-config helpers/chain-config)

;; Re-export selection handlers
(def handle-select-item selection/handle-select-item)
(def handle-select-all selection/handle-select-all)
(def handle-clear-selection selection/handle-clear-selection)
(def handle-start-rename selection/handle-start-rename)
(def handle-cancel-rename selection/handle-cancel-rename)
(def handle-rename-item selection/handle-rename-item)
(def handle-set-ui-mode selection/handle-set-ui-mode)
(def handle-set-item-enabled selection/handle-set-item-enabled)

;; Re-export structure handlers
(def handle-copy-selected structure/handle-copy-selected)
(def handle-paste-items structure/handle-paste-items)
(def handle-delete-selected structure/handle-delete-selected)
(def handle-create-empty-group structure/handle-create-empty-group)
(def handle-group-selected structure/handle-group-selected)
(def handle-ungroup structure/handle-ungroup)
(def handle-toggle-collapse structure/handle-toggle-collapse)
(def handle-start-drag structure/handle-start-drag)
(def handle-move-items structure/handle-move-items)
(def handle-clear-drag-state structure/handle-clear-drag-state)
(def handle-add-item structure/handle-add-item)
(def handle-remove-item-at-path structure/handle-remove-item-at-path)
(def handle-reorder-items structure/handle-reorder-items)

;; Re-export params handlers
(def handle-add-curve-point params/handle-add-curve-point)
(def handle-update-curve-point params/handle-update-curve-point)
(def handle-remove-curve-point params/handle-remove-curve-point)
(def handle-set-active-curve-channel params/handle-set-active-curve-channel)
(def handle-update-spatial-params params/handle-update-spatial-params)
(def handle-update-scale-params params/handle-update-scale-params)
(def handle-update-rotation-param params/handle-update-rotation-param)
(def handle-reset-params params/handle-reset-params)
(def handle-toggle-zone-group params/handle-toggle-zone-group)
(def handle-update-param params/handle-update-param)
(def handle-update-param-from-text params/handle-update-param-from-text)
(def handle-update-color-param params/handle-update-color-param)
