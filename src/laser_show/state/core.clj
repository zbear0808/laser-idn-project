(ns laser-show.state.core
  "Unified state management with macro-based domain definitions.
   
   This module provides:
   - A single cljfx context atom for all application state
   - The `defstate` macro for declarative state domain definitions
   - Context-aware state updates that preserve memoization cache
   - Low-level write primitives, which only the event pipeline calls while
     an app runs (guard-writes!)
   
   For READ operations:
   - Backend/services: Use extractors directly with get-raw-state (thread-safe, no memoization)
   - UI components: Use laser-show.subs (memoized, context-based subscriptions)
   
   Usage:
   1. Initialize with (init-state!), once at start-up
   2. Write via event handlers: they return {:state new-state}, and the event
      pipeline (laser-show.events.core) writes it on the event thread. While
      an app runs, a write fn called off that thread is refused (see
      guard-writes!). Tests and the REPL use events/write-state!
   3. Read in backend: (ex/bpm (state/get-raw-state))
   4. Read in UI: (fx/sub-ctx context subs/bpm)
   
   Debug:
   - (save-state-debug!) - Save full state to timestamped EDN file"
  (:require [cljfx.api :as fx]
            [clojure.core.cache :as cache]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.tools.logging :as log])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))


;; Section 1: Core State Atom & Cache


(defonce ^{:private true
           :doc "The single cljfx context atom. Initialized by init-state!"}
  *context
  (atom nil))

(defonce ^{:private true
           :doc "Registry of all defined state domains."}
  *domain-registry
  (atom {}))

(def cache-factory
  "LRU cache factory for context memoization.
   Adjust :threshold based on app complexity."
  #(cache/lru-cache-factory % :threshold 512))


;; Section 2: Context Access Functions


(defn get-context-atom
  "Get the raw context atom (for fx/create-app and fx/mount-renderer)."
  []
  *context)


(defn get-state
  "Get the raw state map from the context with subscription tracking.
   
   Returns the unwrapped state map, useful for:
   - Event handler co-effects (safe - not lazy)
   - UI component rendering (memoized)
   - Testing
   - Debugging
   
   IMPORTANT: This participates in cljfx's subscription tracking system.
   Do NOT use in subscription functions that return lazy sequences, as
   subscription tracking must complete before the function returns.
   
   For background thread access without subscription tracking (e.g., timers,
   async tasks), use get-raw-state instead.
   
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


;; Section 3: State Mutation Primitives


(defonce ^{:private true
           :doc "nil, or {:allowed? (fn []) :strict? bool}: set by
                 guard-writes! while an app runs."}
  !write-guard
  (atom nil))

(defn guard-writes!
  "Installs the write guard, or removes it with nil. While a guard is set,
   every write fn below first calls its (allowed?). When that is false the
   write is refused: with :strict? it throws ex-info (with :thread), otherwise
   it is logged at ERROR with a stack trace and done anyway.

   laser-show.app installs {:allowed? events/on-event-thread?} while an app
   runs, so only the event pipeline writes app state then."
  [guard]
  (reset! !write-guard guard))

(def ^:private refused-message
  "App state written outside the event pipeline: dispatch an event (tests and the REPL: events/write-state!)")

(defn- check-write! []
  (when-let [{:keys [allowed? strict?]} @!write-guard]
    (when-not (allowed?)
      (let [e (ex-info refused-message {:thread (.getName (Thread/currentThread))})]
        (if strict?
          (throw e)
          (log/error e refused-message))))))

(defn swap-state!
  "Update state using a function, preserving context memoization.
   
   Parameters:
   - f: Function that takes current state and returns new state
   - args: Additional arguments passed to f
   
   Example:
   (swap-state! assoc-in [:timing :bpm] 140.0)
   (swap-state! update-in [:playback :active-cues] dissoc [0 0])"
  [f & args]
  (check-write!)
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
  (check-write!)
  (swap! *context
         (fn [ctx]
           (if ctx
             (fx/swap-context ctx (constantly new-state))
             (throw (ex-info "State not initialized. Call init-state! first." {}))))))

(defn reset-state-vals!
  "Like reset-state!, but returns [old-state new-state], with old-state the
   value it replaced."
  [new-state]
  (check-write!)
  (let [[old-ctx _] (swap-vals! *context
                                (fn [ctx]
                                  (if ctx
                                    (fx/swap-context ctx (constantly new-state))
                                    (throw (ex-info "State not initialized. Call init-state! first." {})))))]
    [(:cljfx.context/m old-ctx) new-state]))

(defn get-in-state
  "Get a value at a path in state.
   
   This is a low-level primitive used internally.
   
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


;; Section 4: Initialization & Lifecycle


(defn init-state!
  "Initialize the state atom with the given initial state.
   
   Parameters:
   - initial-state: Map containing all application state
   
   This creates a cljfx context wrapping the state with an LRU cache.
   Call this once at application startup before mounting the renderer."
  [initial-state]
  (check-write!)
  (reset! *context (fx/create-context initial-state cache-factory))
  (log/info "State initialized with" (count initial-state) "top-level keys"))


;; Section 5: Domain Registration (used by defstate macro)


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
  (update-vals @*domain-registry :initial))


;; Section 6: defstate Macro


(defmacro defstate
  "Define a state domain declaratively.
   
   This macro:
   1. Registers the domain in the domain registry
   2. Defines a var with the initial state for this domain
   
   State writes come from event handlers, which return {:state new-state}
   for the event pipeline to write; tests and the REPL use
   laser-show.events.core/write-state!. The write fns here (swap-state!,
   assoc-in-state!, ...) are the pipeline's primitives.
   
   State reads should use:
   - extractors directly with get-raw-state for backend/services (thread-safe, no memoization)
   - subs namespace for UI components (memoized, context-based)
   
   Parameters:
   - domain-name: Symbol naming the domain (e.g., timing)
   - docstring: Documentation for this domain
   - field-specs: Map of field-name -> {:default value :doc \"description\"}
   
   Example:
   (defstate timing
     \"Timing and BPM management.\"
     {:bpm {:default 120.0 :doc \"Beats per minute\"}
      :beat-index {:default 0 :doc \"The beat in the bar\"}})
   
   Generates:
   - timing-initial (var with initial state map)
   - Domain registration for build-initial-state-from-domains
   
   Usage, in a handler:
   {:state (assoc-in state [:timing :bpm] 140.0)}"
  [domain-name docstring field-specs]
  (let [domain-kw (keyword domain-name)
        initial-var-name (symbol (str domain-name "-initial"))
        
        initial-value (update-vals field-specs :default)]
    
    `(do
       (def ~initial-var-name
         ~(str "Initial state for " domain-name " domain. " docstring)
         ~initial-value)
       
       (register-domain! ~domain-kw ~initial-value ~field-specs)
       
       ;; Return the domain keyword for chaining
       ~domain-kw)))


;; Section 7: Debug Utilities


(def ^:private debug-output-dir
  "Directory for debug output files."
  "debug-output")

(def ^:private timestamp-formatter
  "Formatter for timestamp in filenames: 2026-02-01T041817"
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HHmmss"))

(defn save-state-debug!
  "Save current application state to an EDN file for debugging.
   
   Creates a timestamped file in the debug-output/ directory.
   The state is pretty-printed for readability.
   
   Returns the absolute path of the saved file.
   
   Example:
     (save-state-debug!)
     ;; => \"c:/path/to/project/debug-output/state-debug-2026-02-01T041817.edn\""
  []
  (let [state (get-raw-state)
        timestamp (.format (LocalDateTime/now) timestamp-formatter)
        filename (str "state-debug-" timestamp ".edn")
        dir (io/file debug-output-dir)
        file (io/file dir filename)]
    ;; Ensure directory exists
    (.mkdirs dir)
    ;; Write state as pretty-printed EDN
    (with-open [w (io/writer file)]
      (pprint/pprint state w))
    (let [path (.getAbsolutePath file)]
      (log/info "State debug saved to:" path)
      path)))
