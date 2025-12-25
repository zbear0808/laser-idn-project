(ns laser-show.state.context
  "cljfx context wrapper for UI state.
   
   This module provides a memoized context that wraps the combined state
   from multiple atoms, enabling efficient re-renders in cljfx components.
   
   Components subscribe to specific parts of state using fx/sub-val and fx/sub-ctx.
   cljfx tracks dependencies and only re-renders affected components.
   
   Usage:
   - The !context atom is the single source of truth for UI state
   - Atom watchers automatically sync changes from individual atoms
   - Use fx/sub-val for simple value lookups (fast, always invalidated)
   - Use fx/sub-ctx for computed subscriptions (memoized, dependency-tracked)"
  (:require [cljfx.api :as fx]
            [clojure.core.cache :as cache]
            [laser-show.state.atoms :as atoms]))

;; ============================================================================
;; Context Configuration
;; ============================================================================

(def cache-factory
  "Cache factory for the context. Uses LRU cache to prevent unbounded growth.
   Adjust :threshold based on app complexity and memory constraints."
  #(cache/lru-cache-factory % :threshold 512))

;; ============================================================================
;; State Aggregation
;; ============================================================================

(defn- get-combined-state
  "Aggregate state from all atoms into a single map for the context.
   
   This is called when syncing atoms to context. Only includes state
   needed for UI rendering - backend-only state is excluded.
   
   Returns: Map with keys :grid, :playback, :timing, :connection, :effects, :ui, :project"
  []
  {:grid @atoms/!grid
   :playback @atoms/!playback
   :timing @atoms/!timing
   :connection {:connected? (:connected? @atoms/!idn)
                :target (:target @atoms/!idn)}
   :effects @atoms/!effects
   :ui (select-keys @atoms/!ui [:selected-preset :clipboard :drag])
   :project (select-keys @atoms/!project [:current-folder :dirty?])})

;; ============================================================================
;; Context Atom
;; ============================================================================

(defonce ^{:doc "The cljfx context atom. Components subscribe to this via fx/sub-val and fx/sub-ctx."}
  !context
  (atom (fx/create-context (get-combined-state) cache-factory)))

;; ============================================================================
;; Sync Functions
;; ============================================================================

(defn sync-context!
  "Sync the combined state from all atoms to the context.
   Called by atom watchers whenever any watched atom changes.
   
   Uses fx/swap-context to preserve memoization cache where possible."
  []
  (let [new-state (get-combined-state)
        old-ctx @!context
        new-ctx (fx/swap-context old-ctx (constantly new-state))]
    (reset! !context new-ctx)))

(defn- make-sync-watcher
  "Create a watcher function that triggers context sync.
   The watcher key is included for debugging."
  [watcher-key]
  (fn [_key _ref _old-val _new-val]
    (sync-context!)))

;; ============================================================================
;; Watcher Management
;; ============================================================================

(def ^:private watcher-key :cljfx-context-sync)

(defn setup-watchers!
  "Set up watchers on all atoms to sync to the context.
   Call this once during app initialization."
  []
  (add-watch atoms/!grid watcher-key (make-sync-watcher :grid))
  (add-watch atoms/!playback watcher-key (make-sync-watcher :playback))
  (add-watch atoms/!timing watcher-key (make-sync-watcher :timing))
  (add-watch atoms/!idn watcher-key (make-sync-watcher :idn))
  (add-watch atoms/!effects watcher-key (make-sync-watcher :effects))
  (add-watch atoms/!ui watcher-key (make-sync-watcher :ui))
  (add-watch atoms/!project watcher-key (make-sync-watcher :project)))

(defn remove-watchers!
  "Remove the context sync watchers from all atoms.
   Call this during app shutdown."
  []
  (remove-watch atoms/!grid watcher-key)
  (remove-watch atoms/!playback watcher-key)
  (remove-watch atoms/!timing watcher-key)
  (remove-watch atoms/!idn watcher-key)
  (remove-watch atoms/!effects watcher-key)
  (remove-watch atoms/!ui watcher-key)
  (remove-watch atoms/!project watcher-key))

;; ============================================================================
;; Initialization
;; ============================================================================

(defonce ^:private initialized? (atom false))

(defn init!
  "Initialize the context system. Safe to call multiple times.
   Sets up atom watchers and performs initial sync."
  []
  ;; Always set up watchers - they are idempotent (same key replaces)
  (setup-watchers!)
  (sync-context!)
  (when-not @initialized?
    (reset! initialized? true)))

(defn shutdown!
  "Shutdown the context system. Removes watchers."
  []
  (when @initialized?
    (remove-watchers!)
    (reset! initialized? false)
    (println "cljfx context shutdown")))

;; ============================================================================
;; Debug / REPL
;; ============================================================================

(comment
  ;; Initialize the context system
  (init!)
  
  ;; Check current context state
  @!context
  
  ;; Test sync
  (sync-context!)
  
  ;; Shutdown
  (shutdown!)
  
  ;; Test subscription (must be in cljfx context)
  ;; (fx/sub-val @!context :grid)
  )
