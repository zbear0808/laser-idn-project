(ns laser-show.events.handlers.chain.core
  "Core module for hierarchical chain operations.
   
   Contains:
   - Main dispatch router (handle)
   
   Imports from:
   - helpers: State access, path manipulation, collection operations, config
   - params: Parameter operations, UI mode"
  (:require
   [clojure.tools.logging :as log]
   [laser-show.events.handlers.chain.helpers :as helpers]
   [laser-show.events.handlers.chain.params :as params]))


;; Main Dispatch Router


(defn- handle-chain-event
  "A generic chain event (handle), by its :event/type: :domain and
   :entity-key make its config, and its writer is the chain-handlers
   function for it."
  [{:keys [event/type domain entity-key state] :as event}] 
  (log/trace "chain/handle ENTER - type:" type "domain:" domain "entity-key:" entity-key)
  (let [config (helpers/chain-config domain entity-key)]
    (case type
      :chain/set-items
      (do
        (log/trace "chain/set-items - items count:" (count (:items event))
                   "items-path:" (:items-path config))
        {:state (-> state
                    (assoc-in (:items-path config) (:items event)))})
      
      ;; UI mode -> params module
      :chain/set-ui-mode
      {:state (params/handle-set-ui-mode state config (params/ui-mode-key event) (:mode event))}
      
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
      
      :chain/reset-params
      {:state (params/handle-reset-params state config event)}
      
      :chain/update-param
      {:state (params/handle-update-param state config event)}

      :chain/set-clamp-after
      {:state (params/set-clamp-after state config event)
       :history/description "Change Transform Clamping"}
      
      :chain/update-param-from-text
      {:state (params/handle-update-param-from-text state config event)}
      
      :chain/update-color-param
      {:state (params/handle-update-color-param state config event)}
      
      ;; Unknown chain event
      (do
        (log/warn "Unknown chain event type:" type)
        {}))))

(defn handle
  "Handle generic chain events that work across all chain types.
   
   These events use :domain and :entity-key to create a config and
   delegate to chain-handlers functions. One for a projector's calibration
   whose projector is gone (helpers/missing-owner?), a late one, does
   nothing ({}).
   
   This is the main entry point for :chain/* events from the dispatcher."
  [{:keys [event/type domain entity-key state] :as event}]
  (if (helpers/missing-owner? state domain entity-key)
    (do (log/debug "Ignoring" type "for a projector that is gone:" entity-key)
        {})
    (handle-chain-event event)))
