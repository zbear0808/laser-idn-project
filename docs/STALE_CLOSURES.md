# Stale Closures in cljfx Applications

## Overview

For project code, use [Reactive state in this app](CLJFX_STATE_MANAGEMENT.md#reactive-state-in-this-app). There are two different opportunities for staleness: a JavaFX node can outlive the render that installed its callback, and a queued event can outlive the data used to build it. Recreating a closure or replacing it with a map only addresses the first.

## What is a Stale Closure?

In Clojure, a closure is a function that "captures" values from its surrounding lexical scope. When the captured values become outdated (stale) while the closure continues to exist and be called, we have a **stale closure**.

```clojure
;; Example of closure capturing a value
(let [items [{:id 1 :name "Item 1"}]]
  (fn []
    ;; This function "closes over" items
    ;; It will always see the original value, even if items
    ;; changes elsewhere in the application
    (count items)))  ; => Always returns 1
```

## Why Stale Closures Happen in cljfx

cljfx components are **re-rendered** when their props or subscribed context values change. However, certain callbacks are only set up **once** during a component's lifecycle:

### The Problem: `on-created` Callbacks

```clojure
{:fx/type fx/ext-on-instance-lifecycle
 :on-created (fn [node]
               ;; This closure captures `items` at creation time
               ;; It will NOT update when the component re-renders!
               (setup-handler! node items))
 :desc {:fx/type :v-box ...}}
```

The `on-created` callback runs **once** when the JavaFX node is first created. Even though the cljfx component re-renders with new `items`, the JavaFX node persists and the original `on-created` closure is never called again.

### Common Scenarios Where Stale Closures Occur

1. **Keyboard event handlers** set up in `on-created`
2. **Mouse event handlers** (`.setOnMouseClicked`, etc.) set up imperatively
3. **Drag-and-drop handlers** that capture props
4. **Timer/interval callbacks** created at component mount
5. **Native JavaFX listeners** added imperatively

## Symptoms of Stale Closures

### 1. Operations Work the First Time, Then Stop Working

- Keyboard shortcut works when dialog first opens
- After adding/removing items, the same shortcut does nothing or operates on old data
- Clicking a button works, but keyboard equivalent doesn't

### 2. Debug Logs Show Old Data

```
;; User added 3 items, then pressed Ctrl+G
[DEBUG] group-selected! items count: 1   ;; <-- Should be 3!
```

### 3. Intermittent Success/Failure

- Works if you close and reopen the dialog (recreates the closure with fresh data)
- Fails if you perform operations without closing the dialog

### 4. UI Buttons Work but Keyboard Shortcuts Don't

This is a strong indicator because:
- **Buttons**: Declarative handler props advance with the description. A queued action can still contain data from an older render.
- **Keyboard**: Handler was set up once in `on-created` (stale closure)

```clojure
;; A declarative handler sees the items from its latest description
{:fx/type :button
 :on-action (fn [_] (do-something items))}  ; Fresh items each render

;; Keyboard - STALE (set up once)
:on-created (fn [node]
              (.setOnKeyPressed node
                (fn [_] (do-something items))))  ; Old items forever
```

## Solutions

### Historical workaround: atom-based state for imperative handlers

The example below illustrates the indirection used by older list code. Do not use a render-time registry write as a component lifecycle hook: a cache hit may skip it, a reopened node can miss its entry, and a retargeted node can still look up the previous ID. Prefer declarative event props. If a native listener must be installed once, bind its current command or ID via declarative `:user-data` and read that from the node at event time; see [the commit-field focus listener](../src/laser_show/views/components/commit_field.clj#L76-L95).

```clojure
;; Registry of atoms per component
(defonce handler-atoms (atom {}))

(defn get-or-create-atoms! [component-id]
  (or (get @handler-atoms component-id)
      (let [atoms {:items-atom (atom [])
                   :props-atom (atom {})}]
        (swap! handler-atoms assoc component-id atoms)
        atoms)))

(defn update-atoms! [component-id items props]
  (let [{:keys [items-atom props-atom]} (get-or-create-atoms! component-id)]
    (reset! items-atom items)
    (reset! props-atom props)))

;; In component render function
(defn my-component [{:keys [items props component-id]}]
  (let [{:keys [items-atom props-atom]} (get-or-create-atoms! component-id)
        _ (update-atoms! component-id items props)]  ; Update on EVERY render
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   (setup-handler! node 
                     ;; Dereference atoms at event time, not setup time
                     (fn [] @items-atom)
                     (fn [] @props-atom)))
     :desc ...}))

;; In the handler
(defn setup-handler! [node get-items get-props]
  (.setOnKeyPressed node
    (fn [event]
      ;; Deref NOW to get current values
      (let [items (get-items)
            props (get-props)]
        (handle-key items props event)))))
```

### Solution 2: Event Dispatch Instead of Direct Mutation

Instead of capturing selected items, put the current target on the node and dispatch its ID. The handler resolves the selection from its `:state` co-effect. This works even when the node is reused for a different list:

```clojure
{:fx/type fx/ext-on-instance-lifecycle
 :desc {:fx/type :v-box :user-data {:component-id component-id}}
 :on-created
 (fn [node]
   (.setOnKeyPressed node
     (reify javafx.event.EventHandler
       (handle [_ e]
         (when (= javafx.scene.input.KeyCode/ESCAPE (.getCode e))
           (events/dispatch! {:event/type :list/clear-selection
                              :component-id (:component-id (.getUserData node))}))))))}
```

### Solution 3: Use cljfx Event Maps (When Possible)

A map installed as the handler is dispatched with `:fx/event`. Send the action the user chose; derive mutable facts in the handler when the event is processed:

```clojure
{:fx/type :button
 :on-action {:event/type :list/clear-selection
             :component-id component-id}}
```

### Solution 4: Avoid Imperative Setup When Possible

Sometimes you can avoid `on-created` entirely:

```clojure
;; Instead of imperatively setting up key handlers,
;; use cljfx's :on-key-pressed prop (if available)
{:fx/type :text-field
 :on-key-pressed {:event/type :handle-key
                  :component-id component-id}}
```

## Debugging Checklist

When you suspect stale closures:

1. **Add debug logging** at the point where data is used:
   ```clojure
   (log/debug "Handler called with items count:" (count items))
   ```

2. **Compare button vs keyboard behavior** - if buttons work but keyboard doesn't, likely stale closure

3. **Check `on-created` callbacks** - any closures here are prime suspects

4. **Look for imperative handler setup** - `.setOnX`, `.addEventHandler`, etc.

5. **Test close/reopen** - if closing and reopening fixes it, confirms stale closure

## Real Example from This Project

### The Bug

In `list.clj`, keyboard shortcuts (Ctrl+G for grouping) stopped working after adding items to the list.

### The Cause

```clojure
;; list-editor component
{:fx/type fx/ext-on-instance-lifecycle
 :on-created (fn [node]
               ;; items captured here at creation time!
               (setup-keyboard-handlers! node component-id items props))
 ...}
```

### The historical fix

```clojure
;; Store atoms per component
(defonce keyboard-handler-atoms (atom {}))

;; Update atoms on every render
(defn list-editor [{:keys [items ...]}]
  (let [{:keys [items-atom props-atom]} (get-or-create-handler-atoms! component-id)
        _ (update-handler-atoms! component-id items handler-props)]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created (fn [node]
                   ;; Pass atoms, not values
                   (setup-keyboard-handlers! node component-id items-atom props-atom))
     ...}))

;; Handler derefs atoms at event time
(defn setup-keyboard-handlers! [node component-id items-atom props-atom]
  (.addEventFilter node KeyEvent/KEY_PRESSED
    (fn [event]
      (let [items @items-atom   ; Current value!
            props @props-atom]  ; Current value!
        (handle-key-event items props event)))))
```

## Summary

| Scenario | What can go wrong | Solution |
|----------|------------|----------|
| `on-created` or imperative `.setOnX` | A captured target can outlive the edit | Read current node data at input time; detach on disposal |
| cljfx map handler | Queued payload can contain an old model | Send a stable ID and the user's input; resolve against current `:state` |
| Native function listener | Returning an event map has no effect | Call `events/dispatch!` after extracting plain input on the FX thread |
| Timer or delayed completion | Its owner may be gone or replaced | Stop timers with the node; match completion events to the active request |

When checking the fix, keep the editor mounted, switch from A to B, then verify that the real callback changes B and leaves A alone. Also exercise close/reopen; cached descriptions need to work on a newly created node.
