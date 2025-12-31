(ns laser-show.state.context
  "DEPRECATED: cljfx context wrapper.
   
   This module is now deprecated. The unified state system in state.core
   provides the cljfx context directly via (state/get-context-atom).
   
   This file exists for backward compatibility. All functions delegate
   to state.core equivalents.
   
   Migration:
   - Replace [laser-show.state.context :as ctx] with [laser-show.state.core :as state]
   - Replace ctx/!context with (state/get-context-atom)
   - Remove calls to ctx/init! and ctx/shutdown! (handled by state/init-state! and state/shutdown!)"
  (:require [laser-show.state.core :as state]))

;; ============================================================================
;; DEPRECATED - Use state.core instead
;; ============================================================================

(defn init!
  "DEPRECATED: No-op. State initialization is handled by state/init-state!
   Keep for backward compatibility."
  []
  (println "DEPRECATED: laser-show.state.context/init! - use state/init-state! instead")
  nil)

(defn shutdown!
  "DEPRECATED: No-op. Shutdown is handled by state/shutdown!
   Keep for backward compatibility."
  []
  (println "DEPRECATED: laser-show.state.context/shutdown! - use state/shutdown! instead")
  nil)

(defn sync-context!
  "DEPRECATED: No-op. With unified state, no sync is needed.
   State mutations automatically update the cljfx context."
  []
  nil)

;; For code that still references !context directly
(def ^{:doc "DEPRECATED: Use (state/get-context-atom) instead.
             This is now an alias to the unified state context atom."}
  !context
  (state/get-context-atom))

;; ============================================================================
;; Debug / REPL
;; ============================================================================

(comment
  ;; Migration examples:
  
  ;; Old: @ctx/!context
  ;; New: @(state/get-context-atom) or just (state/get-state)
  
  ;; Old: (ctx/init!)
  ;; New: (state/init-state! initial-state)
  
  ;; Old: (ctx/shutdown!)
  ;; New: (state/shutdown!)
  
  ;; Old: (fx/sub-val @ctx/!context :grid)
  ;; New: (fx/sub-val (state/get-context) :grid) or (state/get-in-state [:grid])
  )
