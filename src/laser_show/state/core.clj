(ns laser-show.state.core
  "Unified state management with macro-based domain definitions.
   
   This module provides:
   - A single cljfx context atom for all application state
   - The `defstate` macro for declarative state domain definitions
   - Context-aware state updates that preserve memoization cache
   - Low-level write primitives, which only the event pipeline calls while
     an app runs (guard-writes!)
   - Epochs: each init-state! makes a context of a new epoch, which every
     write after it keeps; swap-state-in-epoch! writes only into one, so an
     app's event can't write into the context of the app started after it
   
   For READ operations:
   - Backend/services: Use extractors directly with get-raw-state (thread-safe, no memoization)
   - Callbacks (a key, a click, a drop): get-raw-state when the input comes
   - UI components: Use laser-show.subs on the component's own :fx/context
     (memoized, context-based subscriptions); a snapshot is no render
     dependency
   - A root context from the atom (a swap's or a watch's old and new):
     root-context->snapshot
   
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
           :doc "How many contexts init-state! made: the newest one's ::epoch."}
  !epochs
  (atom 0))

(defonce ^{:private true
           :doc "Registry of all defined state domains."}
  *domain-registry
  (atom {}))

(def cache-factory
  "LRU cache factory for context memoization, 4096 entries (reactivity
   plan 36). A render looks up every subscription and function component
   on screen (its working set); an LRU smaller than that evicts it all each
   render, so any change runs every view body (at 512: 226 on the starter
   Grid, 370 with both chain editors open). Working sets of the starter
   show, in entries: Grid 817, Vibes 602, Perform 296, Grid and both
   editors 977; a cue in every cell and longer chains in both editors,
   1036. At 4096, the first power of two at least twice that, a change no
   view reads runs no view body, and a cell's rename only that cell's two.
   The rest holds descriptions no view uses any more (a grid cell's: about
   16 KB), never an earlier context's cache. ui-test cache-working-set-test
   keeps the Grid and both editors under half of it."
  #(cache/lru-cache-factory % :threshold 4096))


;; Section 2: Context Access Functions


(defn get-context-atom
  "Get the raw context atom (for fx/create-app and fx/mount-renderer)."
  []
  *context)


(defn root-context->snapshot
  "The app map root-context holds: root-context being a root context from
   the context atom (its value, an atomic swap's old or new value, a
   watch's old or new), not a component's :fx/context. It only converts:
   it doesn't read the atom, subscribe or touch the subscription cache, and
   it returns the map itself, not a copy. nil for nil.

   The one place that knows how cljfx 1.10.6 (the deps.edn pin) keeps the
   map, which cljfx calls private. Any other non-nil value (an app map given
   by mistake, a context of a cljfx that keeps it differently) throws
   instead of reading as nil or {}; state.core-test checks the adapter
   against cljfx's own fx/sub-val."
  [root-context]
  (when (some? root-context)
    (let [m (when (map? root-context) (:cljfx.context/m root-context))]
      (if (map? m)
        m
        (throw (ex-info (str "laser-show.state.core/root-context->snapshot: not a root context holding"
                             " an app map, as cljfx 1.10.6 (the deps.edn pin) keeps it. A cljfx"
                             " upgrade that keeps it otherwise needs this adapter changed.")
                        {:given (type root-context)}))))))


(defn get-raw-state
  "One current app map: the context atom read once, its map
   (root-context->snapshot). No subscription and no cache access, so it is
   for code that decides when to sample: an event's :state co-effect, the
   show clock's tick, a callback when its input comes, the REPL. A view
   subscribes through the :fx/context it is given instead (laser-show.subs):
   this is no render dependency.
   
   Returns nil if context not initialized."
  []
  (root-context->snapshot @*context))

(defn get-state
  "Deprecated: get-raw-state's snapshot, the app map as it is now, nil if
   context not initialized. A compatibility snapshot: no subscription, no
   cache access, no render dependency. For display, subscribe through the
   component's supplied :fx/context before returning (laser-show.subs)."
  {:deprecated "1.0.0"}
  []
  (get-raw-state))


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

(defn- successor
  "The context after ctx holding (f state), of the same epoch (init-state!).
   Throws when there is no context yet."
  [ctx f]
  (if ctx
    (assoc (fx/swap-context ctx f) ::epoch (::epoch ctx))
    (throw (ex-info "State not initialized. Call init-state! first." {}))))

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
  (swap! *context successor #(apply f % args)))

(defn reset-state!
  "Reset state to a completely new value.
   Preserves the context wrapper for memoization.
   
   Parameters:
   - new-state: The new state map"
  [new-state]
  (check-write!)
  (swap! *context successor (constantly new-state)))

(defn reset-state-vals!
  "Like reset-state!, but returns [old-state new-state], with old-state the
   map it replaced: taken from the old root the atomic swap returned, never
   from reading the atom again, which can already hold a later write. The
   dev lost-update check compares it to the event's snapshot by identity."
  [new-state]
  (check-write!)
  (let [[old-ctx _] (swap-vals! *context successor (constantly new-state))]
    [(root-context->snapshot old-ctx) new-state]))

(defn epoch
  "Which init-state! made the context: a number each one increases, kept by
   every write after it. nil before the first."
  []
  (::epoch @*context))

(defn swap-state-in-epoch!
  "Like swap-state!, but only while the context is still the one init-state!
   made at epoch (see epoch): [old-state new-state], or nil, writing
   nothing, once another init-state! replaced it. The check and the write
   are one atomic step, so a re-initialization can't come between them (the
   app's events write so: events.core effect-state)."
  [epoch f & args]
  (check-write!)
  (loop []
    (let [ctx @*context]
      (when (= epoch (::epoch ctx))
        (let [next-ctx (successor ctx #(apply f % args))]
          (if (compare-and-set! *context ctx next-ctx)
            [(root-context->snapshot ctx) (root-context->snapshot next-ctx)]
            (recur)))))))

(defn get-in-state
  "Deprecated: (get-in (get-raw-state) path), the value at path in one
   snapshot; nil if context not initialized or nothing is there. A
   compatibility snapshot: no subscription, no cache access, no render
   dependency. For display, subscribe through the component's supplied
   :fx/context before returning (laser-show.subs).
   
   Example:
   (get-in-state [:timing :bpm]) => 120.0"
  {:deprecated "1.0.0"}
  [path]
  (get-in (get-raw-state) path))

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
   Call this once at application startup before mounting the renderer.
   Each call starts a new epoch (see epoch): a write computed for the
   context before it can be told apart (swap-state-in-epoch!)."
  [initial-state]
  (check-write!)
  (reset! *context (assoc (fx/create-context initial-state cache-factory) ::epoch (swap! !epochs inc)))
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
