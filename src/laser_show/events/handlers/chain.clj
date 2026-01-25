(ns laser-show.events.handlers.chain
  "Re-exports from chain modules for backwards compatibility.
   
   The chain handler implementation has been split into focused modules:
   - chain/helpers.clj   - Config factory, group creation
   - chain/core.clj      - Main dispatch router
   - chain/structure.clj - Group creation, item CRUD
   - chain/params.clj    - Parameter operations, UI mode
   
   NOTE: Selection-based operations (select, delete-selected, DnD, copy/paste)
   are handled by the list.clj component directly using ID-based operations."
  (:require
   [laser-show.events.handlers.chain.core :as core]
   [laser-show.events.handlers.chain.helpers :as helpers]
   [laser-show.events.handlers.chain.structure :as structure]
   [laser-show.events.handlers.chain.params :as params]))

;; Re-export main dispatch router
(def handle core/handle)

;; Re-export config factory
(def chain-config helpers/chain-config)

;; Re-export structure handlers
(def handle-create-empty-group structure/handle-create-empty-group)
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
(def handle-set-ui-mode params/handle-set-ui-mode)
