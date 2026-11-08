# Keyboard Shortcut & Focus Architecture Plan

**Date:** 2026-10-07  
**Status:** Proposed. Not started. Kept after the 2026-11-08 audit; slotted into Phase 2, after 01 M5 (shared dialog scaffolding)  
**Last verified against:** master `dd95ee5` (see `plans/2026-11-07-improvement-review/07-old-plans-audit.md`)  

> **Amendments required before execution (2026-11-08 audit):**
> 1. §C.2 keeps `Space` (and implicitly `R` and `Delete`) as `MenuItem` accelerators "with a handler check". That is the bug in 01 L3: single-key accelerators fire inside text fields (`title_bar.clj:143,152,163`). Move them to the scene key filter instead.
> 2. Rename `keyboard-handler-atoms` to the current `!component-handler-props` (`list.clj:57`), and fold in 01 Q4 (the missing `:ordered-ids` defect).
> 3. Add the keyboard *trigger* input source (05 F17). It shares keys with these shortcuts.
> 4. Timeline Delete: fixed on branch `improve/p1-ui` (new `:timeline/delete-selection`; the canvas key handler is now attached). Arrow-key nudge now works too. Re-check this section against that branch.
**Target Areas:** Focus Management, Window/Stage Lifecycle, Contextual Shortcuts, Unified Clipboard

---

## 1. Executive Summary & Root Cause Analysis

An architectural review of the keyboard shortcut and focus mechanics across the application (specifically within `title_bar.clj`, `root.clj`, `list.clj`, `canvas_interaction.clj`, `timeline_editor.clj`, `grid_cell.clj`, and dialog windows) reveals two structural flaws:

1. **The JavaFX Focus Hierarchy vs. Component Scope Conflict:**
   Components like `list-editor` and `interactive-canvas` check direct self-focus (`(.isFocused node)`) on their outer container. In JavaFX, `node.isFocused()` is strictly true only if that specific node is the exact focus owner in the scene. When a user interacts with a list item, clicks a toolbar button, or scrolls, JavaFX assigns focus to the `ScrollPane`, child button, checkbox, or an ancestor. As a result, `(.isFocused node)` returns false, causing component-level event filters to discard keystrokes.

2. **Global Stage Accelerators vs. Contextual Operations Conflict:**
   The main window registers accelerators globally on `MenuItem`s in `title_bar.clj` (`Ctrl+C`, `Ctrl+V`, `Delete`, `Space`, `Escape`). When contextual components fail to consume keystrokes because their direct self-focus check fails, events bubble to the `Scene`. The scene executes the title bar accelerators, triggering grid/cell-level actions (such as clearing a grid cell or toggling playback) rather than the intended list/timeline action.

---

## 2. Why Windows & Controls Lose Focus

### A. The `ScrollPane` Focus Trap
In `list.clj`, clicking an item invokes `request-list-focus!`, which traverses parent nodes via `.getParent` looking for the first ancestor with `isFocusTraversable`.
* `javafx.scene.control.ScrollPane` has `isFocusTraversable = true` by default.
* The focus request halts at the `ScrollPane` and never reaches the outer `list-editor` container.
* In `setup-keyboard-handlers!`, the event filter checks `(when focused? ...)` using `(.isFocused node)`. Because the `ScrollPane` holds focus, this check evaluates to `false`.

### B. Child Interaction Stealing Focus
Within `list-sidebar`, clicking buttons (such as "Copy", "Paste", "Del", "Group") or item checkboxes immediately transfers JavaFX focus to the clicked button or checkbox.
* `node.isFocused()` becomes false.
* Subsequent keystrokes like `Ctrl+V` are ignored by the list filter and bubble to the Scene.

### C. Canvas Mouse-Focus Blind Spot
In `canvas_interaction.clj`, `interactive-canvas` enables focus traversal when keyboard handling is present, but `:on-mouse-pressed` never invokes `.requestFocus` on the canvas.
* In JavaFX, clicking a `Canvas` does not automatically transfer keyboard focus to it.
* When selecting items in the timeline editor, focus remains on whichever control had focus previously (e.g., a toolbar button, an input, or the main window).
* Pressing `Delete`, `Left`, or `Right` fails to reach `timeline-editor`'s `on-key` handler.

### D. Multi-Stage Focus & Modality `:none`
Dialog windows (`cue-chain-editor-dialog`, `effect-chain-editor-dialog`, `timeline-cue-editor-dialog`, `zone-group-editor-dialog`) are non-modal (`:modality :none`).
* If a user clicks back to the main window or an unfocused area, the OS focus leaves the dialog.
* Because dialogs do not have menu bars, they have zero scene-level accelerators. When shortcuts fail to be consumed at the node level in a dialog, they do nothing.
* Conversely, if the user interacts with the main window while a dialog is visible, pressing `Delete` or `Ctrl+V` executes against the main grid instead of the dialog.

### E. Multi-List Context Ambiguity in Dialogs
In `cue-chain-editor-dialog`, there are two sibling lists in the same window:
1. Cue Chain Items (`[:cue-chain col row]`)
2. Item Effects (`[:item-effects col row]`)
When neither list is strictly focused, or if focus shifted to the right-hand parameter editor, there is no shared record in state indicating which list was the last active target for `Ctrl+C`, `Ctrl+V`, or `Delete`.

### F. Out-of-Band State in `list.clj`
`list.clj` maintains a private global atom `keyboard-handler-atoms` mapping `component-id` to mutable atoms holding items and props. This bypasses Clojure's unidirectional data flow, creating potential stale state or orphaned listeners during dialog open/close cycles.

---

## 3. Gaps in the Current System

1. **Absence of an Explicit Focus Context in State:** Focus is treated as an ephemeral JavaFX UI detail rather than first-class application state (`[:ui :focus-context]`).
2. **Strict Node-Focus Testing Instead of Hierarchical/Branch Testing:** Code checks `(.isFocused node)` rather than whether the focus owner is a descendant of the component branch.
3. **Canvas Focus Omission:** Canvas controls require explicit `.requestFocus` on mouse down to receive key events.
4. **Aggressive Main-Window Accelerators:** `MenuItem` accelerators catch all bubbled events and route them strictly to the active tab's grid cell.
5. **No Scene-Level Key Interception in Secondary Stages:** Dialog stages do not install a unified key handler on their scenes.
6. **Fragmented Clipboard Types:** Separate types (`:cue-chain-items`, `:item-effects`, `:effect-chain`, `:cue`, `:cell-assignment`) without compatibility conversion or fallback handling.
7. **Non-Atomic Cut Operations:** `Ctrl+X` dispatches a copy event followed by a separate delete event, risking data loss if copy encounters issues.

---

## 4. Proposed Consolidated Architecture

The target architecture replaces fragmented, node-level key listeners and rigid menu accelerators with a **Context-Driven Command System**.

```
┌─────────────────────────────────────────────────────────────┐
│                      UI / View Layer                        │
│  Stage Scene Event Filters  ───►  Report Focus on Click     │
│  (Main Window & Dialogs)          (Canvas, List, Grid, Tab) │
└──────────────────────────────┬──────────────────────────────┘
                               │ Dispatches :shortcuts/key-pressed
                               │ or :ui/set-focus-context
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                    State Management Layer                   │
│  [:ui :focus-context] ───► Target, Component ID, Domain     │
│  [:ui :clipboard]     ───► Standardized Typed Clipboard     │
└──────────────────────────────┬──────────────────────────────┘
                               │ Routes to contextual handler
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                   Event Handling Layer                      │
│  Central Command Handlers:                                  │
│  - :command/copy-selection                                  │
│  - :command/paste-clipboard                                 │
│  - :command/delete-selection                                │
│  - :command/select-all                                      │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. Component & Structural Plan

### A. New State Management Structures

#### 1. Focus Context Schema (`[:ui :focus-context]`)
Represents what area or component currently owns user intention:
* **`:target-type`**: Keyword indicating category (`:list`, `:canvas-timeline`, `:grid-cell`, `:effects-cell`, `:text-input`, or `:general`).
* **`:component-id`**: Identifier for specific instance (e.g., `[:cue-chain 0 1]`, `[:item-effects 0 1]`, `:cue-grid`, `:timeline`).
* **`:window-id`**: Keyword identifying the owning stage (`:main-window`, `:cue-chain-editor`, `:effect-chain-editor`, `:timeline-cue-editor`, etc.).
* **`:capabilities`**: Set of supported shortcut commands for this target (e.g., `#{:copy :paste :delete :select-all :nudge :group}`).
* **`:context-data`**: Supplementary parameters map (such as coordinates, selected IDs, paths).

#### 2. Clipboard Unification Schema (`[:ui :clipboard]`)
Enhances `laser-show.state.clipboard`:
* **`:type`**: Source type (`:cue-chain-items`, `:item-effects`, `:effect-chain`, `:grid-cell`).
* **`:data`**: The deep-copied data vector or payload.
* **`:compatible-targets`**: Vector of targets that can accept this clipboard data with or without conversion.

---

### B. Event Handling Layer

#### New Namespaces:
* `laser-show.events.handlers.shortcuts`: Central dispatcher and command router.
* `laser-show.shortcuts.registry`: Declarative mappings between key combinations, targets, and command events.

#### New / Updated Functions & Handlers:

1. **`handle-set-focus-context`** in `laser-show.events.handlers.ui`
   * **Parameters**: Parameter map containing `state`, `target-type`, `component-id`, `window-id`, `capabilities`, `context-data`.
   * **Behavior**: Updates `[:ui :focus-context]` in state.

2. **`handle-key-pressed`** in `laser-show.events.handlers.shortcuts`
   * **Parameters**: Parameter map containing `state`, `window-id`, `key-code`, `shortcut?`, `shift?`, `alt?`, `focus-owner-class`.
   * **Behavior**:
     * Verifies whether the focused element is a text input control (`TextField`, `TextArea`). If so, leaves default text typing/shortcuts untouched.
     * Looks up matching action from `laser-show.shortcuts.registry` given key combination and `[:ui :focus-context]`.
     * If matched, returns an effects map with `:dispatch` for the appropriate domain command (e.g., `:command/copy-selection`).

3. **Contextual Command Handlers**:
   * **`handle-command-copy`**:
     * Inspects `focus-context`.
     * Routes to list copy, timeline copy, or grid cell copy.
   * **`handle-command-paste`**:
     * Inspects `focus-context` and clipboard compatibility.
     * Pastes items at selection or appends.
   * **`handle-command-delete`**:
     * Inspects `focus-context`.
     * Deletes selected list items, timeline blocks, or clears grid cell.
   * **`handle-command-select-all`**:
     * Selects all items in the active list or timeline.
   * **`handle-command-cut`**:
     * Atomically executes copy and delete within a single transaction function to prevent data loss.

---

### C. UI Component Layer Changes

#### 1. Scene Key Filtering across All Stages
* **`root.clj`** (`root-view`):
  * Add a scene-level event filter on the main window scene for `KeyEvent/KEY_PRESSED` that dispatches `:shortcuts/key-pressed` with `:window-id :main-window`.
* **Dialog Windows** (`cue_chain_editor.clj`, `effect_chain_editor.clj`, `timeline_cue_editor.clj`):
  * Add a matching scene-level event filter on each dialog scene with its respective `:window-id`.

#### 2. Menu Item Accelerators in `title_bar.clj`
* Remove local duplicate accelerators from `MenuItem`s for contextual operations (`Ctrl+C`, `Ctrl+V`, `Delete`), or convert them to fire the new `:command/*` events instead of raw cell-only events.
* Global application shortcuts (`Space` for play/pause, `Escape` for stop, `Ctrl+S` for save, `F11` for fullscreen) remain registered, but their event handler checks whether an active text input or modal dialog has priority.

#### 3. Refactoring `list.clj`
* Remove private mutable atom `keyboard-handler-atoms`.
* Remove `.addEventFilter` and `setup-keyboard-handlers!` from `list-editor`.
* In `setup-click-handler!` and list container interaction:
  * On mouse press or click anywhere inside the list container or items, dispatch `:ui/set-focus-context` with `:target-type :list` and the list's `component-id`.
* The list no longer needs fragile `.isFocused` assertions.

#### 4. Refactoring `interactive-canvas` in `canvas_interaction.clj`
* In `:on-mouse-pressed`, call `(.requestFocus canvas)` so the JavaFX canvas gains node focus.
* Dispatch `:ui/set-focus-context` with `:target-type :canvas-timeline` (or specific canvas type).

#### 5. Refactoring `generic-grid-cell` in `grid_cell.clj`
* On cell selection or click, dispatch `:ui/set-focus-context` with `:target-type :grid-cell` and coordinates `[col row]`.

---

## 6. Functional Alternatives Evaluated

### Alternative 1: Context-Driven Centralized Dispatch (Recommended)
* **Mechanism**: Every user interaction (click, focus change) registers the active context in state (`[:ui :focus-context]`). Keystrokes are captured at the `Scene` level of whatever window is active, converted into abstract commands (`:command/copy`, `:command/paste`), and handled by a pure routing function.
* **Pros**:
  * Adheres strictly to unidirectional data flow.
  * Solves focus loss caused by internal widgets (ScrollPanes, Buttons).
  * Works consistently across main window and all secondary dialog stages.
  * Eliminates mutable atoms and lifecycle listeners in view components.
* **Cons**:
  * Additional events dispatched to state on user click interactions.

### Alternative 2: JavaFX Scene Focus Owner Inspection (`isFocusWithin`)
* **Mechanism**: Retain local event handling on nodes, but replace `node.isFocused()` with a tree inspection helper that checks whether `scene.getFocusOwner()` is a child or descendant of `node` (using JavaFX's `focusWithinProperty` or traversing parents).
* **Pros**:
  * Retains local component handling without adding context tracking to state.
* **Cons**:
  * Does not solve the issue where clicking in a parameter editor leaves the user wanting to paste into the list they just worked with.
  * Does not resolve accelerator conflicts on the main window.
  * Still leaves out-of-band mutable atoms in `list.clj`.

### Alternative 3: Command Action Pattern via Stage Key Maps
* **Mechanism**: Each stage builds an immutable map of `KeyCombination -> action-event` passed directly into the stage/scene definition.
* **Pros**:
  * Declarative stage setup.
* **Cons**:
  * Difficult to dynamically adjust shortcuts based on internal sub-component selection state without frequent full scene rebuilds.

---

## 7. Implementation Phasing

1. **State & Registry Setup**:
   * Define focus context paths in `laser-show.state.domains`.
   * Create `laser-show.shortcuts.registry` with declarative key mappings.
2. **Event Dispatch Consolidation**:
   * Implement `laser-show.events.handlers.shortcuts` for unified command routing.
   * Update clipboard handlers to support atomic cut and cross-target paste.
3. **UI Interaction Updates**:
   * Wire `:on-mouse-pressed` / click handlers in `list.clj`, `canvas_interaction.clj`, and `grid_cell.clj` to update focus context.
   * Add scene-level event filters to `root.clj` and dialog scenes.
   * Clean up deprecated out-of-band atoms in `list.clj`.
4. **Testing & Verification**:
   * Verify list copy/paste with ScrollPane present.
   * Verify timeline nudging and deletion with canvas clicked.
   * Verify that dialog shortcuts operate independently without affecting main grid cells.
