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
   [laser-show.events.handlers.chain.helpers :as helpers]))

;; Re-export main dispatch router
(def handle core/handle)

;; Re-export config factory
(def chain-config helpers/chain-config)
