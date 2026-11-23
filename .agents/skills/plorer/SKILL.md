---
name: plorer
description: Inspect and drive the running laser show UI with cljfx.plorer from the app's REPL. Find windows and controls, click, right-click, type, pick menu and list items, wait for results, read app state and take screenshots. Use it when the app's REPL is running and you need to see or operate the live app.
---

# Driving the running UI with plorer

[cljfx.plorer](https://github.com/cljfx/plorer) inspects and drives the live JavaFX scene graph from the app's REPL. It is a `:dev` dependency in `deps.edn`.

This skill assumes the app's REPL is already running: started with the `:dev` alias, with its port in `.nrepl-port`. If it isn't, use the `app-repl` skill to start one. For repeatable regression tests, use the `targeted-ui-testing` skill instead.

## Evaluating the forms

Everything below is a Clojure form to evaluate in that REPL. From the repo root:

```sh
clj -M:dev .agents/skills/app-repl/nrepl_eval.clj '(windows)'
clj -M:dev .agents/skills/app-repl/nrepl_eval.clj @debug-output/step.clj
```

- Each call takes about two seconds, so put several forms in one call. The call prints what the forms print, then each form's value.
- Inside the shell's single quotes, write `(quote x)` for `'x`. Put multi-line forms, and forms with single quotes, in a file under `debug-output/` (gitignored) and pass `@path`.
- A form that throws doesn't stop the later forms in the same call, but the call exits with status 1. Wrap steps that depend on each other in `(do ...)`.
- `def`s and the app persist between calls: `(def dlg ...)` in one call, use `dlg` in the next.

If you reach the REPL another way, such as Calva or an MCP eval tool, evaluate the same forms in the `user` namespace.

## Setup, once per REPL session

```sh
clj -M:dev .agents/skills/app-repl/nrepl_eval.clj @.agents/skills/plorer/helpers.clj
clj -M:dev .agents/skills/app-repl/nrepl_eval.clj '(windows)'
```

The helpers file also requires plorer as `p`.

`(windows)` should list a `"Laser Show"` stage. If no window is open, start the app in test mode, which runs no hardware or network services, and wait for its window:

```clojure
(require 'laser-show.app)
(laser-show.app/start-test!)
(wait-for main-window 10000)
```

Loading the helpers or the app prints a `Reflection warning` from `cljfx/coerce.clj`. It is harmless.

Ask the user before running `(start)`, which starts the real app: it scans the network for projectors and starts MIDI, OSC and Link.

## The loop: look, act, wait, check

1. Look: `(windows)`, `(ids)`, `(outline (main-window) :depth 4)`, `(shot!)`.
2. Act: `click!`, `right-click!`, `double-click!`, `choose!`, `type!`, `fill!`, or plorer's own input functions.
3. Wait: events and rendering run asynchronously. `(wait-for #(...))` until the result shows up, rather than sleeping.
4. Check: `text-of`, `p/props`, `(state [...])` or another screenshot.

For example, set the tempo and confirm it:

```clojure
(fill! "#bpm-input" "128")                    ; click, select all, type, Enter
(wait-for #(= 128.0 (state [:timing :bpm])))  ; => true
(text-of "#bpm-input")                        ; => "128.0"
(shot! (main-window) "bpm")                   ; => "debug-output/bpm.png"
```

Open a screenshot with the `read` tool to see it.

## Helpers

`helpers.clj` defines these in the `user` namespace. A target is a node, or a plorer selector that matches exactly one node in the open windows, such as `"#play-btn"`. When a selector matches no node or several, the helper throws and lists any matches.

| Helper | What it does |
|---|---|
| `(windows)` | Lists the open windows with their class and title. |
| `(main-window)` | Returns the main window, the one that holds `#main-layout`. |
| `(window-titled "Cue Chain")` | Returns the one window whose title contains the text. |
| `(popup)` | Returns the open menu, right-click menu or combo box list. |
| `(ids)`, `(ids root)` | Lists every node that has an `#id`, in scene-graph order, which is roughly top to bottom. |
| `(outline root :depth 4)` | Prints the tree: class, `#id`, text, and `(hidden)` or `(disabled)`. |
| `(describe node)` | Returns the same one-line description for a single node. |
| `(by-text "Close")`, `(by-text root "Close")` | Returns the one button, label or menu item with exactly this text. |
| `(text-of target)` | Returns the node's text. |
| `(click! target)` | Clicks the node's centre, in the node's own window. `(click! target :secondary)` sends a secondary click but opens no menu. |
| `(right-click! target)` | Right-clicks the node and opens its context menu. |
| `(double-click! target)` | Double-clicks the node. |
| `(choose! "Edit Cue...")` | Clicks the item with exactly this text in the open popup. |
| `(type! window "text")` | Types into the focused node of that window. |
| `(fill! target "text")` | Clicks a field, selects all, types, and presses Enter. |
| `(wait-for f)`, `(wait-for f ms)` | Calls `f` every 50 ms until it returns a truthy value, and returns that value. Errors count as not yet. Gives up after 3 s by default. |
| `(state)`, `(state [:timing :bpm])` | Returns the app state, or the value at a path in it. |
| `(shot!)`, `(shot! target "name")` | Saves a PNG to `debug-output/` and returns its path. |
| `shortcut` | `:meta` on macOS, `:control` elsewhere, for key chords. |

## Finding things

- `(ids)` lists the ids in the main window. To search the source, grep `src/laser_show/views` for `:id "`. Examples: `#effects-tab`, `#bpm-input`, `#trigger-mode-selector`, `#file-menu`, `#view-menu`, `#stream-btn`.
- Grid cells are `#grid-cell-<col>-<row>`, counted from 0. The UI labels rows with letters and columns with numbers, so A1 is `#grid-cell-0-0` and A2 is `#grid-cell-1-0`.
- Dialogs are separate windows: `(def dlg (wait-for #(window-titled "Cue Chain Editor")))`, then search inside `dlg`.
- `p/all` and `p/one` take selectors: a class (`javafx.scene.control.Button`), `"#id"`, `".style-class"`, a property map (`{:text "Save"}`) or a predicate. Each selector searches inside the matches of the one before it. Put `>` before a selector to match direct children only: `(p/all (main-window) "#main-layout" > "#status-bar")`.
- `p/all` and `p/one` return live JavaFX objects. Print them with `describe` or `outline`.
- `p/one` throws unless exactly one element matches, and its error doesn't say which ones did. Use `p/all` to see them; the helpers' errors list them.

## App state

Confirm what an action did by reading the state with `(state path)`:

| What | Path |
|---|---|
| Tempo | `[:timing :bpm]` |
| Trigger mode | `[:config :cue :trigger-mode]`, such as `:retrigger` |
| A grid cell's cue | `(get (state [:chains :cue-chains]) [col row])`, which is `nil` for an empty cell |
| Playback | `[:playback :playing?]`, `[:playback :active-cell]` |
| Active tab | `[:ui :active-tab]`, such as `:grid` |
| Dialogs | `[:ui :dialogs]`, such as `[:ui :dialogs :cue-chain-editor :open?]` |
| Unsaved changes | `[:project :dirty?]` |

`(keys (state))` lists the top-level keys.

## Recipes

Each of these was run against the app in test mode.

```clojure
;; Switch tabs.
(click! "#effects-tab")
(click! "#grid-tab")

;; Use the menu bar; on Grid, the preview's split pane disappears or returns.
(click! "#grid-tab")
(wait-for #(= :grid (state [:ui :active-tab])))
(let [shown? #(boolean (seq (p/all (main-window) "#main-split")))
      before (shown?)]
  (click! "#view-menu")
  (choose! "Show Preview")
  (wait-for #(not= before (shown?))))

;; Pick from a combo box.
(click! "#trigger-mode-selector")
(choose! "Toggle")

;; Close an open menu or list without choosing.
(p/key-tap! (main-window) :escape)

;; Send shortcut keys to a window.
(p/key-chord! (main-window) [shortcut :z])         ; undo
(p/key-chord! (main-window) [shortcut :shift :z])  ; redo

;; Create a cue in an empty cell (B3) from its right-click menu. The menu's
;; items depend on whether the cell holds a cue; on a miss, choose! lists them.
;; "Create Cue" opens the Cue Chain Editor on an empty chain, and the cue
;; exists once a preset is added to it.
(right-click! "#grid-cell-2-1")
(choose! "Create Cue")
(def dlg (wait-for #(window-titled "Cue Chain Editor")))
(click! (by-text dlg "Circle"))
(wait-for #(get (state [:chains :cue-chains]) [2 1]))

;; Read the items of the editor's cue chain.
(mapv #(text-of (p/one % javafx.scene.control.Label))
      (p/all dlg "#list-editor-cue-chain-2-1" ".chain-item"))  ; => ["Circle [All]"]

;; Drag a slider thumb, here the editor's first slider. Mouse drags work;
;; drag-and-drop doesn't, see Gotchas.
(let [slider (first (p/all dlg javafx.scene.control.Slider))
      [x y] (p/point (p/one slider ".thumb") 0.5 0.5)]
  (p/mouse-press! dlg [x y] :primary)
  (doseq [dx (range 10 61 10)] (p/mouse-move! dlg [(+ x dx) y]))
  (p/mouse-release! dlg [(+ x 60) y] :primary)
  (:value (p/props slider :only [:value])))
(click! (by-text dlg "Close"))

;; Double-click a cell to open its editor. The first click also triggers the
;; cell's cue.
(double-click! "#grid-cell-0-0")
(def dlg (wait-for #(window-titled "Cue Chain Editor")))
(click! (by-text dlg "Close"))

;; Create a timeline cue and click inside the editor's canvas. Canvases have
;; no child nodes, so aim at an offset read off a screenshot. At the default
;; zoom this clicks the ruler at bar 3, which moves the playhead there.
(right-click! "#grid-cell-0-3")
(choose! "Create Timeline Cue")
(def tl (wait-for #(window-titled "Timeline Editor")))
(let [[x0 y0] (p/point (p/one tl "#timeline-canvas") 0 0)]
  (p/mouse-click! tl [(+ x0 401) (+ y0 10)] :primary))
(wait-for #(= "3.1.1" (text-of (p/one tl "#timeline-readout"))))
(click! (by-text tl "Close"))
```

## plorer functions

Use these for anything the helpers don't cover. `(clojure.repl/doc p/all)` and the other docstrings have the details.

```clojure
(p/props node)                                ; every property and its value
(p/props node :only [:text :value :selected])
(p/point node 0.5 0.5)                        ; scene coordinates of a point inside a node
(p/mouse-press! window [x y] :primary)        ; also mouse-move!, mouse-release! and mouse-click!
(p/scroll! window [x y] 0 -100)               ; negative dy scrolls down
(p/key-tap! window :enter)                    ; also :tab, :escape, :back-space, :digit1, :a
(p/key-chord! window [shortcut :a])           ; modifiers first
(p/key-press! window :shift)                  ; held until (p/key-release! window :shift)
(p/screenshot! node)                          ; temporary PNG; shot! copies it to debug-output/
```

Input functions take a window or scene and a position in that scene's coordinates. `p/point` converts a position inside a node. The helpers pick the node's own window for you.

## Gotchas

- Find the main window with `(main-window)`, not by its title. The title becomes `"Laser Show *"` when there are unsaved changes, and it includes the project file once one is open.
- `(p/one javafx.stage.Window)` fails as soon as a dialog, menu or combo list is open, because each of them is a window.
- `(click! target :secondary)` opens no context menu. A real right-click also sends a context-menu request, which `right-click!` adds.
- A double-click is two clicks at one point in quick succession, which is what `double-click!` sends. Clicks in separate calls stay single clicks.
- Drag-and-drop can't be driven: moving cells, reordering lists, dragging from the timeline browser. In headless mode, starting a drag throws `UnsupportedOperationException`. In a visible window, the OS drag doesn't follow plorer's synthetic mouse. Dispatch the event the drop would send instead, or cover the drop in a UI test with `fire-drop!` from `test/laser_show/ui_test/testfx.clj`.
- The app's text fields apply their value on Enter. `fill!` presses it. After `type!`, press it yourself with `(p/key-tap! window :enter)`.
- Keys go to the focused node of the window you pass, so click the field first. To see what has focus: `(describe (:focus-owner (p/props (.getScene (main-window)) :only [:focus-owner])))`.
- Escape sent to a popup's own window doesn't close it. Send it to the main window, or click somewhere else in the main window.
- A window's screenshot leaves out its popups. Take one of the popup itself: `(shot! (popup) "menu")`.
- Text cut off with "..." in a screenshot is still whole in the node. Read it with `text-of`.
- Clicking a grid cell triggers its cue. In a `(start)` session with projectors connected and streaming, that sends laser output.
- Clicks change the real app. Connecting projectors, changing MIDI or OSC settings, and saving or deleting files all take effect, so ask the user first.
- Never call `System/exit`. Closing the main window asks about unsaved changes and then stops the app in a `(start)` session. In a `start-test!` session it only logs a warning and the window stays open. Use `(laser-show.app/stop!)` to close the UI.
- plorer relies on JavaFX internals for input and scrolling, and `right-click!` uses one more. If a JavaFX upgrade breaks them, look for a newer plorer release.
