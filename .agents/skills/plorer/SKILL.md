---
name: plorer
description: Inspect and drive the running laser show JavaFX UI from its REPL with cljfx.plorer. Use it to find controls by id or class, read their props, click, type, hover, scroll, or take screenshots of the live app.
---

# Driving the running UI with plorer

plorer reads and drives the live JavaFX scene graph from the app's REPL. It is a `:dev` dependency (`io.github.cljfx/plorer` in `deps.edn`), so the REPL must start with the `:dev` alias. Use the `app-repl` skill to get a REPL running. For repeatable regression tests, use the `targeted-ui-testing` skill instead.

## Setup

Start the app in the REPL first (see the `app-repl` skill). In a fresh REPL that means `(require 'laser-show.app)` and then `(laser-show.app/start-test!)`.

From the repo root, require plorer and load the helpers once per REPL session:

```sh
clj -M:dev .agents/skills/app-repl/nrepl_eval.clj '(require (quote [cljfx.plorer :as p]))'
clj -M:dev .agents/skills/app-repl/nrepl_eval.clj @.agents/skills/plorer/helpers.clj
```

`helpers.clj` defines `outline` (a readable scene tree) and `shot!` (a screenshot saved under `debug-output/`).

Keep a window open and showing. Mouse input needs a showing window, and key input needs a focused node, so click a text field before typing.

## Find the window

```clojure
(p/all javafx.stage.Window)                    ; every open window, including menus and dialogs
(def window (p/one javafx.stage.Window))       ; only works when exactly one window is open
(def window (p/one {:fx.plorer/class javafx.stage.Stage :title "Laser Show"}))  ; the main window
```

`p/one` throws unless exactly one element matches. Opening the View menu adds a second window (a `ContextMenu`), so `(p/one javafx.stage.Window)` then fails with `Expected exactly one match, got 2`. Pass the window explicitly. Input functions without a window argument also need exactly one open window.

## Outline and find nodes

```clojure
(outline window :depth 4)                         ; indented tree with #ids and text
(p/one window "#bpm-input")                       ; by id
(p/all window ".list-view")                       ; by style class
(p/all window javafx.scene.control.Button)        ; by class
(p/all window "#main-layout" > "#status-bar")     ; > matches direct children only
(p/all window {:text "Save"})                     ; property map: every entry must match
```

Find ids by searching `src/laser_show/views` for `:id "` with the grep tool. Examples: `play-btn`, `bpm-input`, `view-menu`, `grid-tab`, `grid-cell-0-0`, `status-bar`.

`p/all` and `p/one` return live JavaFX objects, and `p/tree` returns nested maps that contain them. Print with `outline` instead of the raw result.

## Read props

```clojure
(p/props node :only [:text :id :visible :disable])
```

Keys are the kebab-case names of JavaFX properties. Leave out `:only` to list every supported key. Keys the node doesn't support are left out of the result.

## Act

```clojure
(p/mouse-click! window (p/point node 0.5 0.5) :primary)  ; click the center of a node
(p/mouse-move! window (p/point node 0.5 0.5))            ; hover
(p/scroll! window (p/point node 0.5 0.5) 0 -100)         ; negative dy scrolls down
(p/key-tap! window :a)                                   ; press and release one key
(p/key-chord! window [:meta :a])                         ; Command+A on macOS; :control on Windows and Linux
(p/key-press! window :shift)                             ; hold a key ...
(p/key-release! window :shift)                           ; ... then release it
```

- `mouse-*` takes scene coordinates. `p/point` converts a relative position inside a node into them.
- Keys use a US layout. `key-tap!` types text unless Control, Alt or Meta is held.
- A key or mouse button stays held for every later input until it is released. Pair `key-press!` with `key-release!`, and `mouse-press!` with `mouse-release!`.
- Input is delivered inside the app. Per plorer's docs, the desktop pointer does not move.
- Input functions return the target node, and the REPL prints it.

## Wait, then read

UI work runs asynchronously, so wait briefly before reading props after an action:

```clojure
(def bpm (p/one window "#bpm-input"))
(p/mouse-click! window (p/point bpm 0.5 0.5) :primary)
(p/key-chord! window [:meta :a])               ; select all
(p/key-tap! window :digit7)
(p/key-tap! window :digit5)
(Thread/sleep 300)
(p/props bpm :only [:text])                    ; => {:text "75"}
```

## Screenshots

```clojure
(shot! window "main")                          ; => "debug-output/main.png"
(shot! (p/one window "#status-bar") "status")  ; a single node
```

Open the returned PNG with the `read` tool to see it. Screenshots go to the gitignored `debug-output/` folder.

## Gotchas

- Never close the app window or call `System/exit`. The main window's close handler exits the JVM even in dev mode (`src/laser_show/views/root.clj:169`), which also ends the REPL. Use `(laser-show.app/stop!)` to close the UI.
- Clicks change the real app. Connecting hardware, changing MIDI or OSC settings, and saving or deleting project files all take effect. Ask the user before clicking those controls.
- Escape closes an open menu. Check the window count with `(count (p/all javafx.stage.Window))`.
- `p/one` throws on zero or several matches. Use `p/all` to see what matched.
- plorer relies on JavaFX internals for input and scrolling. If a JavaFX upgrade breaks it, check for a newer plorer release.
- While experimenting, start the app in test mode (see `app-repl`). It runs without hardware or network services.
