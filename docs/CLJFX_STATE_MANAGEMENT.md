# cljfx State Management: Contexts vs Atoms

This guide covers state management approaches in cljfx, including thread safety considerations and patterns for async operations.

## Table of Contents

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

The cache is designed for the render cycle, not concurrent access. For worker threads, extract the specific data you need on the main thread first:

```clojure
;; SAFE: Extract specific data before spawning worker
(let [ctx @*state
      data (fx/sub-val ctx :document)
      settings (fx/sub-val ctx :settings)]
  ;; Pass extracted data to worker - it's just immutable data
  (future (process-data data settings)))
```

> **Note:** Avoid calling `fx/sub-val` or `fx/sub-ctx` from within worker threads. Extract all needed data on the FX thread before spawning the worker.

### Modifying State from Other Threads

**With regular atoms:**

```clojure
;; From ANY thread - safe
(swap! *state update :counter inc)
```

**With contexts — use `fx/swap-context`:**

```clojure
;; From ANY thread - safe
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
2. **Batches rapid updates** — 10 quick updates = 1 render with final state
3. **Always renders on FX thread** — safe for JavaFX

---

## Async Patterns from UI Handlers

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

### 1. Extract Data Before Spawning Threads

```clojure
;; ✅ Good - extract data first
(defn handle-export [_]
  (let [ctx @*state
        data (fx/sub-val ctx :export-data)
        format (fx/sub-val ctx :export-format)]
    (future
      (export-to-file data format))))

;; ❌ Avoid - reading context in worker
(defn handle-export [_]
  (future
    (let [ctx @*state]  ;; State might have changed!
      (export-to-file 
        (fx/sub-val ctx :export-data)   ;; Cache mutation on wrong thread
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

| Task | Approach |
|------|----------|
| Simple state update from UI | Direct `swap!` (atoms) or `swap! fx/swap-context` (contexts) |
| Async operation from handler | Spawn `future`, update state when complete |
| Read state in worker thread | Extract data on FX thread first, pass to worker |
| Expensive computation | Use `fx/sub-ctx` subscriptions for memoization |
| Testable async handlers | Use effects system with `wrap-effects` |
| Progress updates | Update state periodically from worker thread |
| Debounced input | Executor + cancellable futures |

The key insight: **Clojure atoms handle thread safety. The renderer handles getting updates onto the FX thread. You just need to use `fx/swap-context` for contexts.**
