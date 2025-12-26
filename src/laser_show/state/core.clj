(ns laser-show.state.core
  "Unified state management with macro-based domain definitions.
   
   This module provides:
   - A single cljfx context atom for all application state
   - The `defstate` macro for declarative state domain definitions
   - Generated accessor functions for each field
   - Context-aware state updates that preserve memoization cache
   
   Usage:
   1. Define state domains in laser-show.state.domains
   2. Initialize with (init-state!)
   3. Access via generated functions: (get-timing-bpm), (set-timing-bpm! 140)
   4. Subscribe in UI via (fx/sub-val context :timing)"
  (:require [cljfx.api :as fx]
            [clojure.core.cache :as cache]
            [clojure.pprint :as pprint]))

;; ============================================================================
;; Core State Atom
;; ============================================================================

(defonce ^{:private true
           :doc "The single cljfx context atom. Initialized by init-state!"}
  *context
  (atom nil))

(defonce ^{:private true
           :doc "Registry of all defined state domains."}
  *domain-registry
  (atom {}))

;; ============================================================================
;; Cache Configuration
;; ============================================================================

(def cache-factory
  "LRU cache factory for context memoization.
   Adjust :threshold based on app complexity."
  #(cache/lru-cache-factory % :threshold 512))

;; ============================================================================
;; Context Access Functions
;; ============================================================================

(defn get-context-atom
  "Get the raw context atom (for fx/create-app and fx/mount-renderer)."
  []
  *context)

(defn get-context
  "Get the current cljfx context value.
   Use this when you need to pass context to subscriptions outside of components."
  []
  @*context)

(defn get-state
  "Get the raw state map from the context.
   
   Returns the unwrapped state map, useful for:
   - Event handler co-effects
   - Testing
   - Debugging
   
   NOTE: This uses fx/sub-val which should only be called from the UI thread
   or within cljfx component render functions. For background thread access,
   use get-raw-state instead.
   
   Returns nil if context not initialized."
  []
  (when-let [ctx @*context]
    (fx/sub-val ctx identity)))

(defn get-raw-state
  "Get the raw state map from the context WITHOUT subscription tracking.
   
   Safe to use from background threads (timers, async tasks, etc.)
   where fx/sub-val would cause assertion errors.
   
   Does not participate in cljfx's memoization/caching system.
   Use get-state for UI thread access when possible.
   
   Returns nil if context not initialized."
  []
  (when-let [ctx @*context]
    (:cljfx.context/m ctx)))

;; ============================================================================
;; State Mutation Functions
;; ============================================================================

(defn swap-state!
  "Update state using a function, preserving context memoization.
   
   Parameters:
   - f: Function that takes current state and returns new state
   - args: Additional arguments passed to f
   
   Example:
   (swap-state! assoc-in [:timing :bpm] 140.0)
   (swap-state! update-in [:timing :tap-times] conj timestamp)"
  [f & args]
  (swap! *context
         (fn [ctx]
           (if ctx
             (fx/swap-context ctx #(apply f % args))
             (throw (ex-info "State not initialized. Call init-state! first." {}))))))

(defn reset-state!
  "Reset state to a completely new value.
   Preserves the context wrapper for memoization.
   
   Parameters:
   - new-state: The new state map"
  [new-state]
  (swap! *context
         (fn [ctx]
           (if ctx
             (fx/swap-context ctx (constantly new-state))
             (throw (ex-info "State not initialized. Call init-state! first." {}))))))

(defn get-in-state
  "Get a value at a path in state.
   
   Parameters:
   - path: Vector path into state
   
   Example:
   (get-in-state [:timing :bpm]) => 120.0"
  [path]
  (get-in (get-state) path))

(defn assoc-in-state!
  "Set a value at a path in state.
   
   Parameters:
   - path: Vector path into state
   - value: New value
   
   Example:
   (assoc-in-state! [:timing :bpm] 140.0)"
  [path value]
  (swap-state! assoc-in path value))

(defn update-in-state!
  "Update a value at a path using a function.
   
   Parameters:
   - path: Vector path into state
   - f: Update function
   - args: Additional args to f
   
   Example:
   (update-in-state! [:timing :tap-times] conj timestamp)"
  [path f & args]
  (apply swap-state! update-in path f args))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn init-state!
  "Initialize the state atom with the given initial state.
   
   Parameters:
   - initial-state: Map containing all application state
   
   This creates a cljfx context wrapping the state with an LRU cache.
   Call this once at application startup before mounting the renderer."
  [initial-state]
  (reset! *context (fx/create-context initial-state cache-factory))
  (println "State initialized with" (count initial-state) "top-level keys"))

(defn initialized?
  "Check if state has been initialized."
  []
  (some? @*context))

(defn shutdown!
  "Shutdown the state system. Clears the context."
  []
  (reset! *context nil)
  (println "State shutdown"))

;; ============================================================================
;; Domain Registration (used by defstate macro)
;; ============================================================================

(defn register-domain!
  "Register a state domain. Called by defstate macro.
   
   Parameters:
   - domain-key: Keyword identifying the domain (e.g., :timing)
   - initial-value: Initial state map for this domain
   - field-specs: Map of field-name -> {:default :doc}"
  [domain-key initial-value field-specs]
  (swap! *domain-registry assoc domain-key
         {:initial initial-value
          :fields field-specs}))

(defn get-registered-domains
  "Get all registered domains. Useful for building initial state."
  []
  @*domain-registry)

(defn build-initial-state-from-domains
  "Build the initial state map from all registered domains."
  []
  (into {}
        (map (fn [[k v]] [k (:initial v)]))
        @*domain-registry))

;; ============================================================================
;; defstate Macro
;; ============================================================================

(defmacro defstate
  "Define a state domain with automatic accessor generation.
   
   This macro:
   1. Registers the domain in the domain registry
   2. Defines a var with the initial state for this domain
   3. Generates getter/setter/updater functions for each field
   4. Generates a full-domain getter function
   
   Parameters:
   - domain-name: Symbol naming the domain (e.g., timing)
   - docstring: Documentation for this domain
   - field-specs: Map of field-name -> {:default value :doc \"description\"}
   
   Example:
   (defstate timing
     \"Timing and BPM management.\"
     {:bpm {:default 120.0 :doc \"Beats per minute\"}
      :tap-times {:default [] :doc \"Tap tempo timestamps\"}})
   
   Generates:
   - timing-initial (var with initial state map)
   - get-timing (returns full domain map)
   - get-timing-bpm, set-timing-bpm!, update-timing-bpm!
   - get-timing-tap-times, set-timing-tap-times!, update-timing-tap-times!"
  [domain-name docstring field-specs]
  (let [domain-kw (keyword domain-name)
        initial-var-name (symbol (str domain-name "-initial"))
        domain-getter-name (symbol (str "get-" domain-name))
        
        ;; Build initial value map from field specs
        initial-value (into {}
                            (map (fn [[k v]] [k (:default v)]))
                            field-specs)
        
        ;; Generate accessor functions for each field
        field-accessors
        (mapcat
          (fn [[field-name {:keys [doc]}]]
            (let [field-kw field-name
                  getter-name (symbol (str "get-" domain-name "-" (name field-name)))
                  setter-name (symbol (str "set-" domain-name "-" (name field-name) "!"))
                  updater-name (symbol (str "update-" domain-name "-" (name field-name) "!"))]
              [`(defn ~getter-name
                  ~(str "Get " (or doc (name field-name)) " from " domain-name " domain.")
                  []
                  (get-in-state [~domain-kw ~field-kw]))
               
               `(defn ~setter-name
                  ~(str "Set " (or doc (name field-name)) " in " domain-name " domain.")
                  [~'value]
                  (assoc-in-state! [~domain-kw ~field-kw] ~'value))
               
               `(defn ~updater-name
                  ~(str "Update " (or doc (name field-name)) " using function f.")
                  [~'f & ~'args]
                  (apply update-in-state! [~domain-kw ~field-kw] ~'f ~'args))]))
          field-specs)]
    
    `(do
       ;; Define the initial value var
       (def ~initial-var-name
         ~(str "Initial state for " domain-name " domain. " docstring)
         ~initial-value)
       
       ;; Register the domain
       (register-domain! ~domain-kw ~initial-value ~field-specs)
       
       ;; Domain-level getter
       (defn ~domain-getter-name
         ~(str "Get the full " domain-name " state map. " docstring)
         []
         (get-in-state [~domain-kw]))
       
       ;; Field-level accessors
       ~@field-accessors
       
       ;; Return the domain keyword for chaining
       ~domain-kw)))

;; ============================================================================
;; REPL / Debug Helpers
;; ============================================================================

(defn debug-state
  "Print current state for debugging."
  []
  (pprint/pprint (get-state)))

(defn debug-domains
  "Print registered domains."
  []
  (pprint/pprint (get-registered-domains)))

#_(comment
  ;; Example usage:
  
  ;; Define a domain
  (defstate timing
    "Timing and BPM management."
    {:bpm {:default 120.0 :doc "Beats per minute"}
     :tap-times {:default [] :doc "Tap tempo timestamps"}})
  
  ;; Initialize state
  (init-state! (build-initial-state-from-domains))
  
  ;; Use generated accessors
  (get-timing-bpm)          ;; => 120.0
  (set-timing-bpm! 140.0)   
  (get-timing-bpm)          ;; => 140.0
  (update-timing-bpm! + 10) 
  (get-timing-bpm)          ;; => 150.0
  
  ;; Get full domain
  (get-timing)              ;; => {:bpm 150.0 :tap-times []}
  
  ;; Direct path access
  (get-in-state [:timing :bpm])
  (assoc-in-state! [:timing :bpm] 120.0)
  
  ;; Debug
  (debug-state)
  (debug-domains)
  
  ;; Shutdown
  (shutdown!)
  )
