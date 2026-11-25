# cljfx State Management: Contexts vs Atoms

## Reactive state in this app

The app owns **one atom containing a cljfx context**. [`fx/create-app` in `app.clj`](../src/laser_show/app.clj) mounts it; [the event pipeline](../src/laser_show/events/core.clj) commits the next state while preserving the context cache. The rules below apply to project code and agent workflows. The later comparisons with standalone cljfx apps illustrate other designs.

- **Render reads:** subscribe through the component's supplied `:fx/context`. Use `fx/sub-val` for a cheap, narrow lookup and `fx/sub-ctx` for a derived selector with stable arguments. Finish all subscription reads before returning; a lazy sequence or callback that reads the bound context later is too late. `state/get-raw-state`, the deprecated `get-state`/`get-in-state` snapshot wrappers and ordinary atom derefs do not register dependencies on the rendering component. [`state/root-context->snapshot`](../src/laser_show/state/core.clj) is the only reader of cljfx's internal context map; never call it from a view.
- **Child components:** return `{:fx/type child :id id}` and let cljfx supply the child's context. Don't forward the parent's context, even inside a props map or a captured callback: the incoming description is part of the cache key, and a forwarded context keeps old generations alive. A helper called synchronously during render may use the parent's context. Pass label and menu builders as top-level functions or `[f & args]` data, so equal inputs give equal descriptions. Keep render functions pure; a cache hit can skip their bodies completely.
- **Cache size:** the app's LRU keeps 4096 entries ([`state/core.clj` `cache-factory`](../src/laser_show/state/core.clj)), chosen from measured working sets (the busiest measured screen used 1036). A view must subscribe to everything it shows; it can't rely on eviction to refresh it. Re-measure view calls on a representative tree before changing the size.
- **Renderer options:** [`app/create-app`](../src/laser_show/app.clj) passes `:renderer-error-handler` in every mode and, in dev mode only, `:renderer-middleware` (`laser-show.dev-renderer/middleware`: cljfx.dev validation, component stacks and F12). Its `:event-handler` is one app instance's handler (`events/instance-handler`); middleware must not wrap or replace that single dispatcher. In [cljfx 1.10.6 `create-app`](https://github.com/cljfx/cljfx/blob/1.10.6/src/cljfx/api.clj#L584-L613), `:opts` and `:error-handler` are ignored `create-renderer` options.
- **Events:** send a stable entity ID and what the user changed. A handler resolves the entity from its current `:state` co-effect, validates the value and returns `{:state new-state}` and/or named effects. For text fields, Enter supplies a JavaFX `ActionEvent`, not the text or a number. Extract the input on the FX thread; only plain data belongs in the document or undo history. An edit to an effect or cue item names it by id (`:effect-instance-id`, `:owner-id`, `:item-id`), not by its place in a chain: the pipeline's `wrap-effect-instance-target` resolves the current path before the Show Lock judges the edit, and a target that's gone gets `{}`. Install an event map directly on a control, or have its function listener call `events/dispatch!`; merely returning a map from a native listener does not dispatch it.
- **Workers:** a service can read one immutable snapshot via `state/get-raw-state` and pure extractors. Have the effect hand blocking I/O to a worker that dispatches the result back. When work can outlive a request, editor or document, include its identity and ignore superseded completions. Neither a worker nor an FX callback writes the app atom directly.
- **Identities for late results:** a result is checked against the owner it was started for, and a mismatch returns `{}` (no commit, no notice). The owners in use:
  - **App instance:** `events/instance-handler` drops a stopped app's events and effects, and `init-state!` starts a new context epoch.
  - **Open show:** `[:project :document-id]` changes only when New or Open goes ahead. Stamp the event with `h/for-document` and check it with `h/same-document?`. Anything that would discard the show re-checks its authorization (`may-discard?` in `handlers/menu.clj`).
  - **Learn attempt:** `[:ui :input-learn :active]` and its `:learn-id`.
  - **Device request:** request ids per kind (`input/devices.clj`).
  - **Scan:** `[:projector-ui :scan-id]`.
  - **Editor and gesture:** an editor's `:editor-id`, and a gesture's press id (`:history/gesture-id`).
  - **Timecode chase:** a chase episode's `:chase-id`.
- **Lifetimes:** shared UI state lives in the database; node dimensions and gesture state can be local; device handles and frame-rate data belong to their runtime owner. For example, `input/devices.clj` owns MIDI ports and the OSC server, `input/link.clj` owns the Carabiner connection, `input/learn.clj` owns the learn listener, and a drag's fallback payload lives on its Scene. Publish immutable status for declarative views, or sample the runtime snapshot in a lifecycle-managed painter. An `:on-created` listener runs for that instance: read the current target from declarative `:user-data` when the input arrives and clean up with the node. `:on-advanced` on `ext-on-instance-lifecycle` means the instance was replaced, not that its props changed.
- **Refused native edits:** JavaFX changes a checkbox, combo, slider or spinner before the handler decides. Wrap such a control in `applied-value/applied-control` ([`views/components/applied_value.clj`](../src/laser_show/views/components/applied_value.clj)), which puts the applied value back after a refusal. Text fields commit through `commit-field`, which restores the applied text.
- **Verification:** `events/flush!` from a test or REPL waits for events queued ahead of its marker. It does not wait for a future completion or a JavaFX render. Wait for the expected state **and** re-query the visible control when the check is about UI behavior.

The [BPM field](../src/laser_show/views/toolbar.clj#L116-L135) shows the loop: `subs/bpm` reads through the supplied context; committing text produces `{:event/type :timing/set-bpm :bpm new-bpm}`; [the handler](../src/laser_show/events/handlers/timing.clj#L40-L43) updates the current state. See the pinned [cljfx 1.10.6 subscription docs](https://github.com/cljfx/cljfx/blob/1.10.6/README.md#subscriptions-and-contexts) and [event-handling example](https://github.com/cljfx/cljfx/blob/1.10.6/examples/e18_pure_event_handling.clj).

## Table of Contents

- [Reactive state in this app](#reactive-state-in-this-app)
- [Overview: Atoms vs Contexts](#overview-atoms-vs-contexts)
- [Regular Atoms](#regular-atoms)
- [Contexts](#contexts)
- [When to Use Each](#when-to-use-each)
- [Thread Safety](#thread-safety)
- [Async Patterns from UI Handlers](#async-patterns-from-ui-handlers)
- [Best Practices](#best-practices)

---

## Overview: Atoms vs Contexts

cljfx offers two primary approaches to state management:

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Regular Atoms                                 │
├─────────────────────────────────────────────────────────────────────┤
│  Atom with state map → Watch triggers render → Entire tree          │
│                        re-evaluated → Props flow down                │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                        Contexts                                      │
├─────────────────────────────────────────────────────────────────────┤
│  Context wrapping state → Subscription functions → Memoization      │
│                           cache → Only changed subscriptions        │
│                           trigger re-render                          │
└─────────────────────────────────────────────────────────────────────┘
```

| Aspect | Regular Atoms | Contexts |
|--------|---------------|----------|
| **Setup complexity** | ✅ Simple, minimal boilerplate | ❌ Requires core.cache, middleware |
| **Mental model** | ✅ Straightforward prop passing | ❌ Subscription-based |
| **Performance (small apps)** | ✅ Negligible difference | ➖ Slight overhead |
| **Performance (large apps)** | ❌ Re-evaluates entire tree | ✅ Selective re-rendering |
| **Prop drilling** | ❌ Must pass through layers | ✅ Direct subscription |
| **Computed values** | ❌ Recomputed every render | ✅ Memoized |

---

## Regular Atoms

The atoms approach is straightforward—you pass state through props:

```clojure
(ns myapp.core
  (:require [cljfx.api :as fx]))

(def *state
  (atom {:title "App title"
         :items []}))

(defn title-input [{:keys [title]}]
  {:fx/type :text-field
   :on-text-changed #(swap! *state assoc :title %)
   :text title})

(defn root [{:keys [title items]}]
  {:fx/type :stage
   :showing true
   :title title
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :children [{:fx/type title-input
                              :title title}
                             {:fx/type :label
                              :text (str "Items: " (count items))}]}}})

(def renderer
  (fx/create-renderer
    :middleware (fx/wrap-map-desc assoc :fx/type root)))

(fx/mount-renderer *state renderer)
```

**How it works:** Every time the atom changes, the entire description tree gets re-evaluated from the root. Props must flow down through the component hierarchy.

---

## Contexts

Contexts wrap your state and provide memoized subscription functions:

```clojure
(ns myapp.core
  (:require [cljfx.api :as fx]
            [clojure.core.cache :as cache]))

(def *state
  (atom (fx/create-context {:title "Hello"
                            :items []
                            :users []}
                           cache/lru-cache-factory)))

;; Subscription functions
(defn sorted-items [context]
  (sort-by :name (fx/sub-val context :items)))

(defn item-count [context]
  (count (fx/sub-val context :items)))

;; View components receive context
(defn root [{:keys [fx/context]}]
  {:fx/type :stage
   :showing true
   :scene {:fx/type :scene
           :root {:fx/type :v-box
                  :children [{:fx/type :label
                              ;; Subscribe directly to what you need
                              :text (fx/sub-val context :title)}
                             {:fx/type :label
                              ;; Use computed subscriptions
                              :text (str "Items: " (fx/sub-ctx context item-count))}]}}})

(def renderer
  (fx/create-renderer
    :middleware (comp
                  fx/wrap-context-desc
                  (fx/wrap-map-desc (fn [_] {:fx/type root})))
    :opts {:fx.opt/type->lifecycle 
           #(or (fx/keyword->lifecycle %)
                (fx/fn->lifecycle-with-context %))}))

(fx/mount-renderer *state renderer)
```

### Key Context Functions

| Function | Purpose | Example |
|----------|---------|---------|
| `fx/create-context` | Create initial context | `(fx/create-context {:data []} cache/lru-cache-factory)` |
| `fx/sub-val` | Subscribe to raw value (keyword or fn) | `(fx/sub-val context :title)` or `(fx/sub-val context get-in [:users i])` |
| `fx/sub-ctx` | Subscribe via function | `(fx/sub-ctx context sorted-items)` |
| `fx/swap-context` | Update context (preserves cache) | `(swap! *state fx/swap-context assoc :x 1)` |
| `fx/reset-context` | Replace context state | `(swap! *state fx/reset-context new-state)` |

### The Memoization Advantage

```clojure
(defn task-summary [context]
  (format "Tasks: %d/%d"
          (fx/sub-ctx context remaining-task-count)
          (fx/sub-ctx context task-count)))

;; Create derived context
(def context-2 
  (fx/swap-context context-1 assoc-in [:tasks 0 :text] "Buy bread"))

;; Even though :tasks changed, task-summary is NOT recalculated if
;; remaining-task-count and task-count return the same values!
(fx/sub-ctx context-2 task-summary)  ;; Cache hit - no recalculation
```

---

## When to Use Each

### Use Regular Atoms When:

- **Small/simple applications** — Todo apps, prototypes, simple tools
- **Shallow component trees** — Not much prop drilling needed
- **Minimal computed state** — Mostly displaying raw data
- **Learning cljfx** — Start simple, add complexity when needed

### Use Contexts When:

- **Large applications** with deep component hierarchies
- **Expensive computed values** — Sorting, filtering, aggregations
- **Many components need same derived data** — Memoization prevents duplicate work
- **Avoiding prop drilling** — Components deep in tree need global data
- **Performance matters** — Re-rendering large trees is expensive

---

## Thread Safety

### Reading State from Other Threads

**Reading the atom is always safe:**

```clojure
;; From any thread - safe
(let [ctx @*state]
  ...)
```

**However, subscription functions mutate the cache:**

```clojure
;; From worker thread - CAUTION
(let [ctx @*state]
  (fx/sub-val ctx :data))  ;; Mutates internal cache!
```

The restriction on a **bound render context** is its lifetime: do not call `sub-*` on it after the component or subscription returns. A worker in this app takes plain data from its effect payload or reads one service snapshot; it need not ask the FX thread for a fresh context:

```clojure
;; A service that needs configuration at the start of an operation
(let [snapshot (state/get-raw-state)]
  (get-in snapshot [:timing :bpm]))
```

> An out-of-band snapshot is appropriate for a worker or a REPL inspection. Inside a render it would leave the view unaware of subsequent changes.

### Modifying State from Other Threads

**With regular atoms:**

```clojure
;; From ANY thread - safe
(swap! *state update :counter inc)
```

**With contexts — use `fx/swap-context`:**

```clojure
;; Standalone atom design; the laser show app dispatches an event instead.
(swap! *state fx/swap-context update :counter inc)
```

> ⚠️ **Important:** Never use plain `swap!` with `assoc` on contexts—it corrupts the context structure:
> ```clojure
> ;; WRONG - corrupts context
> (swap! *state assoc :foo :bar)
> 
> ;; CORRECT
> (swap! *state fx/swap-context assoc :foo :bar)
> ```

> 🔒 **In this app:** only the event pipeline writes app state. A handler returns `:state`, and the pipeline writes it on the one event thread (`laser-show-events`).
> - **From other threads** (futures, the show clock, device callbacks), dispatch an event with the result, as in [Pattern 6](#pattern-6-using-effects-pure-event-handling).
> - **A `swap!` from another thread is atomic, but not safe here:** an event in flight writes back the state it read before your `swap!`, and your change is gone.
> - **While an app runs**, a direct write (`state/swap-state!`, `assoc-in-state!`, `reset-state!`, …) from any thread other than the event thread throws in dev mode and in tests (`app/start-test!`), and is logged at ERROR in production. Kondo flags direct writers in `src/` (`.clj-kondo/config.edn`).
> - **Tests and the REPL** write with `(events/write-state! f & args)`, and wait for a dispatched event with `(events/dispatch! e)` then `(events/flush!)`.

### How the Renderer Handles Cross-Thread Updates

```
Worker Thread          Atom              Renderer           JavaFX Thread
     │                  │                   │                    │
     │──swap! context──▶│                   │                    │
     │                  │──watch triggered─▶│                    │
     │                  │                   │──batch updates────▶│
     │                  │                   │                    │──re-render
```

The renderer:
1. Watches your atom
2. **Coalesces queued updates** — a render can use the latest of several pending descriptions
3. **Always renders on FX thread** — safe for JavaFX

---

## Async Patterns from UI Handlers

> **In this app:** Patterns 1–5 `swap!` the state from a future or an executor. This app doesn't use them for app state: the worker dispatches an event with its result instead (Pattern 6, and the "In this app" note under [Modifying State from Other Threads](#modifying-state-from-other-threads)).

### Pattern 1: Simple Future from Handler

Spawn async work directly from an event handler:

```clojure
(defn handle-search-click [_event]
  ;; Mark loading immediately (on FX thread)
  (swap! *state fx/swap-context assoc :loading true :error nil)
  
  ;; Spawn async work
  (future
    (try
      (let [results (expensive-search-operation)]
        ;; Update from worker thread - safe!
        (swap! *state fx/swap-context assoc
               :results results
               :loading false))
      (catch Exception e
        (swap! *state fx/swap-context assoc
               :error (.getMessage e)
               :loading false)))))

;; In view:
{:fx/type :button
 :text "Search"
 :disable (fx/sub-val context :loading)
 :on-action handle-search-click}
```

### Pattern 2: File Operations

```clojure
;; Extract data before spawning thread
(defn handle-save-file [_event]
  (let [ctx @*state
        data (fx/sub-val ctx :document)
        path (fx/sub-val ctx :file-path)]
    
    (swap! *state fx/swap-context assoc :saving true)
    
    (future
      (try
        (spit path (pr-str data))
        (swap! *state fx/swap-context assoc
               :saving false
               :last-saved (java.time.Instant/now))
        (catch Exception e
          (swap! *state fx/swap-context assoc
                 :saving false
                 :save-error (.getMessage e)))))))
```

### Pattern 3: Network Requests

```clojure
(require '[clj-http.client :as http])

(defn fetch-users! []
  (swap! *state fx/swap-context assoc :users-loading true)
  
  (future
    (try
      (let [response (http/get "https://api.example.com/users"
                               {:as :json})
            users (:body response)]
        (swap! *state fx/swap-context assoc
               :users users
               :users-loading false
               :users-error nil))
      (catch Exception e
        (swap! *state fx/swap-context assoc
               :users-loading false
               :users-error (.getMessage e))))))

;; View with loading state
(defn users-panel [{:keys [fx/context]}]
  (let [loading (fx/sub-val context :users-loading)
        error (fx/sub-val context :users-error)
        users (fx/sub-val context :users)]
    {:fx/type :v-box
     :children [(cond
                  loading 
                  {:fx/type :progress-indicator}
                  
                  error
                  {:fx/type :label
                   :style {:-fx-text-fill :red}
                   :text error}
                  
                  :else
                  {:fx/type :list-view
                   :items users})
                {:fx/type :button
                 :text "Refresh"
                 :disable loading
                 :on-action (fn [_] (fetch-users!))}]}))
```

### Pattern 4: Debounced Text Input

For expensive operations triggered by typing:

```clojure
(def search-executor (java.util.concurrent.Executors/newSingleThreadExecutor))
(def pending-search (atom nil))

(defn debounced-search [query]
  ;; Cancel any pending search
  (when-let [fut @pending-search]
    (future-cancel fut))
  
  ;; Schedule new search with delay
  (reset! pending-search
    (.submit search-executor
      (fn []
        (Thread/sleep 300)  ;; Debounce delay
        (when (= query (fx/sub-val @*state :search-query))
          (let [results (perform-search query)]
            (swap! *state fx/swap-context assoc
                   :search-results results
                   :searching false)))))))

(defn handle-search-input [new-text]
  ;; Update query immediately (responsive UI)
  (swap! *state fx/swap-context assoc
         :search-query new-text
         :searching true)
  ;; Debounced actual search
  (debounced-search new-text))

;; View
{:fx/type :text-field
 :text (fx/sub-val context :search-query)
 :on-text-changed handle-search-input}
```

### Pattern 5: Long-Running Background Task with Progress

```clojure
(defn process-files! [file-paths]
  (let [total (count file-paths)]
    (swap! *state fx/swap-context assoc
           :processing true
           :progress 0
           :progress-total total)
    
    (future
      (try
        (doseq [[idx path] (map-indexed vector file-paths)]
          ;; Update progress
          (swap! *state fx/swap-context assoc
                 :progress (inc idx)
                 :current-file (.getName (io/file path)))
          ;; Do work
          (process-single-file path))
        
        (swap! *state fx/swap-context assoc
               :processing false
               :progress-message "Complete!")
        
        (catch Exception e
          (swap! *state fx/swap-context assoc
                 :processing false
                 :progress-error (.getMessage e)))))))

;; Progress view
(defn progress-view [{:keys [fx/context]}]
  (let [processing (fx/sub-val context :processing)
        progress (fx/sub-val context :progress)
        total (fx/sub-val context :progress-total)
        current (fx/sub-val context :current-file)]
    (when processing
      {:fx/type :v-box
       :children [{:fx/type :progress-bar
                   :progress (/ progress total)}
                  {:fx/type :label
                   :text (format "Processing %d/%d: %s" progress total current)}]})))
```

### Pattern 6: Using Effects (Pure Event Handling)

For maximum testability, use the effects system. cljfx provides helper functions to simplify setup:

| Helper Function | Purpose |
|-----------------|---------|
| `fx/make-deref-co-effect` | Creates a co-effect that derefs an atom |
| `fx/make-reset-effect` | Creates an effect that resets an atom |
| `fx/dispatch-effect` | Built-in effect for dispatching new events |

**Example using contexts (from e18_pure_event_handling):**

```clojure
(ns myapp.events
  (:require [cljfx.api :as fx]))

;; Pure event handler - returns effects as data
(defmulti event-handler :event/type)

(defmethod event-handler ::type-url [{:keys [fx/context fx/event]}]
  {:context (fx/swap-context context assoc :typed-url event)})

(defmethod event-handler ::open-url [{:keys [fx/context url]}]
  {:context (fx/swap-context context assoc :typed-url url :loading true)
   :http {:method :get
          :url url
          :on-response {:event/type ::on-response :result :success}
          :on-exception {:event/type ::on-response :result :failure}}})

(defmethod event-handler ::on-response [{:keys [fx/context result response exception]}]
  {:context (fx/swap-context context assoc
                             :loading false
                             :result result
                             :response response
                             :error exception)})
```

```clojure
(ns myapp.core
  (:require [cljfx.api :as fx]
            [clojure.core.cache :as cache]
            [myapp.events :as events]))

(def *state
  (atom (fx/create-context {:typed-url "" :loading false}
                           cache/lru-cache-factory)))

;; Custom effect for HTTP requests
(defn http-effect [v dispatch!]
  (future
    (try
      (let [response (http/request (dissoc v :on-response :on-exception))]
        (dispatch! (assoc (:on-response v) :response response)))
      (catch Exception e
        (dispatch! (assoc (:on-exception v) :exception e))))))

;; Wire up with helper functions
(def event-handler
  (-> events/event-handler
      (fx/wrap-co-effects
        {:fx/context (fx/make-deref-co-effect *state)})  ;; Helper!
      (fx/wrap-effects
        {:context (fx/make-reset-effect *state)          ;; Helper!
         :dispatch fx/dispatch-effect                     ;; Built-in!
         :http http-effect})))

(def renderer
  (fx/create-renderer
    :middleware (comp
                  fx/wrap-context-desc
                  (fx/wrap-map-desc (fn [_] {:fx/type root})))
    :opts {:fx.opt/map-event-handler event-handler
           :fx.opt/type->lifecycle #(or (fx/keyword->lifecycle %)
                                        (fx/fn->lifecycle-with-context %))}))

(fx/mount-renderer *state renderer)
```

**Simpler example without contexts:**

```clojure
(defn handle-event [event]
  (case (:event/type event)
    ::search-clicked
    {:state (assoc (:state event) :loading true)
     :async {:action :search
             :query (get-in event [:state :query])
             :on-success ::search-complete
             :on-error ::search-failed}}
    
    ::search-complete
    {:state (assoc (:state event)
                   :loading false
                   :results (:fx/event event))}
    
    ::search-failed
    {:state (assoc (:state event)
                   :loading false
                   :error (:fx/event event))}))

(def actual-handler
  (-> handle-event
      (fx/wrap-co-effects {:state #(deref *state)})
      (fx/wrap-effects
        {:state (fn [state _] (reset! *state state))
         :dispatch fx/dispatch-effect
         :async (fn [{:keys [action on-success on-error] :as params} dispatch!]
                  (future
                    (try
                      (let [result (case action
                                     :search (perform-search (:query params)))]
                        (dispatch! {:event/type on-success
                                    :fx/event result}))
                      (catch Exception e
                        (dispatch! {:event/type on-error
                                    :fx/event (.getMessage e)})))))})))

;; View uses map events
{:fx/type :button
 :text "Search"
 :on-action {:event/type ::search-clicked}}
```

**Advantage:** Event handlers are pure functions—easy to test:

```clojure
(handle-event {:event/type ::search-clicked
               :state {:query "test" :loading false}})
;; => {:state {:query "test" :loading true}
;;     :async {:action :search :query "test" ...}}
```

---

## Best Practices

The direct-atom examples below concern standalone apps. For the laser show app, use [Reactive state in this app](#reactive-state-in-this-app), including for errors, cancellation and progress.

### 1. Extract Data Before Spawning Threads

```clojure
;; ✅ Good - extract data first
(defn handle-export [_]
  (let [ctx @*state
        data (fx/sub-val ctx :export-data)
        format (fx/sub-val ctx :export-format)]
    (future
      (export-to-file data format))))

;; Avoid: rereading the global state in the worker can export a different version
(defn handle-export [_]
  (future
    (let [ctx @*state]  ;; State might have changed!
      (export-to-file 
        (fx/sub-val ctx :export-data)
        (fx/sub-val ctx :export-format)))))
```

### 2. Use Atomic Updates

```clojure
;; ❌ Race condition
(future
  (let [current (fx/sub-val @*state :counter)]
    (Thread/sleep 100)
    (swap! *state fx/swap-context assoc :counter (inc current))))

;; ✅ Atomic
(future
  (swap! *state fx/swap-context update :counter inc))
```

In this app an atomic update is necessary but not enough: an event in flight can still undo it, so a worker dispatches an event instead.

### 3. Handle Errors Gracefully

```clojure
(defn async-operation! []
  (swap! *state fx/swap-context assoc :loading true :error nil)
  (future
    (try
      (let [result (risky-operation)]
        (swap! *state fx/swap-context assoc
               :result result
               :loading false))
      (catch Exception e
        (swap! *state fx/swap-context assoc
               :loading false
               :error {:message (.getMessage e)
                       :type (type e)})))))
```

### 4. Consider Cancellation for Long Operations

```clojure
(def current-operation (atom nil))

(defn start-operation! []
  ;; Cancel previous
  (when-let [old @current-operation]
    (future-cancel old))
  
  (reset! current-operation
    (future
      (when-not (Thread/interrupted)
        ;; ... do work, checking interrupted periodically
        ))))

(defn cancel-operation! []
  (when-let [op @current-operation]
    (future-cancel op)
    (swap! *state fx/swap-context assoc :cancelled true)))
```

### 5. Keep High-Frequency Updates Separate

For data that updates very frequently (60+ fps), consider keeping it outside the main state:

```clojure
;; Separate atom for high-frequency data
(def *animation-state (atom {:frame 0 :time 0}))

;; Main state for UI
(def *state (atom (fx/create-context {...} cache/lru-cache-factory)))

;; Animation loop updates *animation-state frequently
;; UI occasionally reads snapshots for display
```

---

## Summary

For this application:

| Task | Approach |
|------|----------|
| Display shared state | Read through the supplied `:fx/context`; use `sub-val` for cheap access and `sub-ctx` for derived values |
| Change app state | Dispatch an intent; the handler returns `:state` computed from its co-effect |
| Async work, progress or debounce | Use an effect and dispatch results; match them to the current request |
| Read state in a service | Use the effect's data or a single `state/get-raw-state` snapshot and pure extractors |
| Draw a frame or meter | Sample the runtime owner's snapshot in the painter; keep per-frame data out of the global database |
| Inspect a UI change | Wait for the model and the displayed value; an event-queue flush alone doesn't establish both |
