# Stale Closures in cljfx Applications

## Overview

Stale closures are a common source of bugs in cljfx applications. This document explains what they are, how they manifest, and patterns to avoid them.

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
- **Buttons**: Handlers are recreated on each render (no stale closure)
- **Keyboard**: Handler was set up once in `on-created` (stale closure)

```clojure
;; Button - WORKS (new handler on each render)
{:fx/type :button
 :on-action (fn [_] (do-something items))}  ; Fresh items each render

;; Keyboard - STALE (set up once)
:on-created (fn [node]
              (.setOnKeyPressed node
                (fn [_] (do-something items))))  ; Old items forever
```

## Solutions

### Solution 1: Atom-Based State for Imperative Handlers

Store current values in atoms and update them on each render:

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

Instead of capturing data, dispatch events that read current state:

```clojure
;; BAD - Captures items at setup time
:on-created (fn [node]
              (.setOnKeyPressed node
                (fn [e]
                  (when (ctrl+g? e)
                    (group-items! items)))))  ; Stale!

;; GOOD - Dispatch event that reads current state
:on-created (fn [node]
              (.setOnKeyPressed node
                (fn [e]
                  (when (ctrl+g? e)
                    (events/dispatch! {:event/type :ui/group-selected
                                       :component-id component-id})))))
                    ;; Handler reads current state from global store
```

### Solution 3: Use cljfx Event Maps (When Possible)

cljfx event maps don't have stale closure issues because they're created fresh each render:

```clojure
;; This is safe - event map is recreated each render
{:fx/type :button
 :on-action {:event/type :do-something
             :items items}}  ; Fresh items value each render
```

### Solution 4: Avoid Imperative Setup When Possible

Sometimes you can avoid `on-created` entirely:

```clojure
;; Instead of imperatively setting up key handlers,
;; use cljfx's :on-key-pressed prop (if available)
{:fx/type :text-field
 :on-key-pressed {:event/type :handle-key
                  :items items}}  ; Safe - recreated each render
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

### The Fix

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

| Scenario | Risk Level | Solution |
|----------|------------|----------|
| `on-created` with closures | HIGH | Use atoms or event dispatch |
| Imperative `.setOnX` handlers | HIGH | Use atoms or event dispatch |
| cljfx event maps `{:event/type ...}` | SAFE | N/A - recreated each render |
| Function props on cljfx components | SAFE | N/A - recreated each render |
| Timers/intervals | HIGH | Store current state in atoms |

Remember: If a closure is set up once but needs to access data that changes, you need a level of indirection (atoms, event dispatch, or global state queries) to avoid staleness.
