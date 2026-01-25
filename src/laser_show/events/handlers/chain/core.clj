(ns laser-show.events.handlers.chain.core
  "Core module for hierarchical chain operations.
   
   Contains:
   - Main dispatch router (handle)
   
   Imports from:
   - helpers: State access, path manipulation, collection operations, config
   - structure: Groups, DnD, item CRUD
   - params: Parameter operations, UI mode"
  (:require
   [clojure.tools.logging :as log]
   [laser-show.events.helpers :as h]
   [laser-show.events.handlers.chain.helpers :as helpers]
   [laser-show.events.handlers.chain.structure :as structure]
   [laser-show.events.handlers.chain.params :as params]))


;; Main Dispatch Router


(defn handle
 "Handle generic chain events that work across all chain types.
  
  These events use :domain and :entity-key to create a config and
  delegate to chain-handlers functions.
  
  This is the main entry point for :chain/* events from the dispatcher."
 [{:keys [event/type domain entity-key state] :as event}]
 (log/debug "chain/handle ENTER - type:" type "domain:" domain "entity-key:" entity-key)
 (let [config (helpers/chain-config domain entity-key)]
   (case type
     :chain/set-items
     (do
       (log/debug "chain/set-items - items count:" (count (:items event))
                  "items-path:" (:items-path config))
       {:state (-> state
                   (assoc-in (:items-path config) (:items event))
                   (h/mark-dirty))})
     
     ;; UI mode -> params module
     :chain/set-ui-mode
     {:state (params/handle-set-ui-mode state config (:effect-path event) (:mode event))}
     
     ;; Structure events -> structure module
     :chain/create-empty-group
     {:state (structure/handle-create-empty-group state config (:name event))}
     
     :chain/add-item
     {:state (structure/handle-add-item state config event)}
     
     :chain/remove-item-at-path
     {:state (structure/handle-remove-item-at-path state config (:path event))}
     
     :chain/reorder-items
     {:state (structure/handle-reorder-items state config (:from-idx event) (:to-idx event))}
     
     ;; Param events -> params module
     :chain/add-curve-point
     {:state (params/handle-add-curve-point state config event)}
     
     :chain/update-curve-point
     {:state (params/handle-update-curve-point state config event)}
     
     :chain/remove-curve-point
     {:state (params/handle-remove-curve-point state config event)}
     
     :chain/set-active-curve-channel
     {:state (params/handle-set-active-curve-channel state config event)}
     
     :chain/update-spatial-params
     {:state (params/handle-update-spatial-params state config event)}
     
     :chain/update-scale-params
     {:state (params/handle-update-scale-params state config event)}
     
     :chain/update-rotation-param
     {:state (params/handle-update-rotation-param state config event)}
     
     :chain/reset-params
     {:state (params/handle-reset-params state config event)}
     
     :chain/toggle-zone-group
     {:state (params/handle-toggle-zone-group state config event)}
     
     :chain/update-param
     {:state (params/handle-update-param state config event)}
     
     :chain/update-param-from-text
     {:state (params/handle-update-param-from-text state config event)}
     
     :chain/update-color-param
     {:state (params/handle-update-color-param state config event)}
     
     ;; Unknown chain event
     (do
       (log/warn "Unknown chain event type:" type)
       {}))))
