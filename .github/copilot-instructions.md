# Laser Show Project - AI Coding Instructions

## Project Overview
Clojure desktop application for controlling laser shows via ILDA Digital Network (IDN) protocol. Uses **cljfx** (functional JavaFX wrapper) for UI with a re-frame-inspired architecture.

## Architecture

### Core Data Flow
```
UI Events → Event Handlers (pure) → Effects → State Update → Subscriptions → UI Re-render
```

### Key Directories
| Path | Purpose |
|------|---------|
| `src/laser_show/state/` | State management - `core.clj` (context atom), `extractors.clj` (pure state queries) |
| `src/laser_show/events/` | Event system - `core.clj` (effects/co-effects), `handlers/` (domain handlers) |
| `src/laser_show/subs.clj` | Memoized subscriptions for UI (use `fx/sub-val` and `fx/sub-ctx`) |
| `src/laser_show/views/` | cljfx UI components |
| `src/laser_show/css/` | CSS-in-Clojure via cljfx/css |
| `src/laser_show/animation/` | Frame generation, effects, modulators |
| `src/laser_show/backend/` | IDN streaming to laser hardware |

### State Management Pattern
- **UI reads**: Use subscriptions in `subs.clj` with `fx/sub-ctx` (memoized)
- **Backend reads**: Use `state/get-raw-state` with extractors (thread-safe, no memoization)
- **Writes**: Always through events → `state/reset-state!` or `state/assoc-in-state!`

```clojure
;; UI component pattern
(defn my-component [{:keys [fx/context]}]
  (let [bpm (fx/sub-ctx context subs/bpm)]  ; Memoized subscription
    {:fx/type :label :text (str bpm " BPM")}))

;; Event handler pattern (pure function)
(defn handle [{:keys [state] :as event}]
  {:state (assoc-in state [:timing :bpm] 120.0)})  ; Return effects map
```

### Event Routing
Events use namespaced keywords routed by domain in `events/handlers.clj`:
```clojure
{:event/type :grid/trigger-cell :col 0 :row 0}  ; → grid handler
{:event/type :timing/set-bpm :bpm 140.0}        ; → timing handler
```

## Critical Conventions

### Avoid Stale Closures (see `docs/STALE_CLOSURES.md`)
Never capture state in `on-created` callbacks. Use event dispatch instead:
```clojure
;; ❌ BAD - stale closure
:on-created (fn [node] (.setOnKeyPressed node (fn [_] (do-something items))))

;; ✅ GOOD - dispatch event
:on-key-pressed {:event/type :my/action}
```

### CSS Colors
Use accessor functions from `laser-show.css.core`, never hardcode hex values:
```clojure
(require '[laser-show.css.core :as css])
(css/bg-primary)     ; => "#1E1E1E"
(css/text-primary)   ; => "#E0E0E0"
```

### Animation Types
LaserPoint is `[x y r g b]` vector with **normalized values** (-1.0 to 1.0 for coords, 0.0 to 1.0 for colors). Conversion to hardware format happens at IDN output stage.

## Development Commands

### Running the App
```bash
# Windows (add :mac or :linux for other platforms)
clj -M:win:dev:laser-show

# VS Code + Calva: Jack-in auto-selects correct aliases and runs (start)
```

### Testing
```bash
clj -M:win:test  # unit tests; use :mac/:mac-arm/:linux on other platforms. Coverage is incomplete
clj -M:win:test -n laser-show.events.grid-handlers-test  # one unit test namespace

clj -M:win:test history  # unit tests whose ns/name contains "history"

# TestFX UI tests. Headless by default: no windows, no mouse movement, no
# display needed (any OS, incl. CI). Run only what you need:
clj -M:test-ui:win:test-ui-headless --list                              # list UI tests
clj -M:test-ui:win:test-ui-headless tap-tempo                           # tests matching a name
clj -M:test-ui:win:test-ui-headless -n laser-show.ui-test.toolbar-test  # one feature namespace
clj -M:test-ui:win:test-ui-headless                                     # whole UI suite

# Visible mode (drop :test-ui-headless) shows real windows and moves the mouse.
# On macOS it needs Accessibility permission for the terminal/IDE.
clj -M:test-ui:win tap-tempo
```

### UI Regression Requirement

Any change to `src/laser_show/views/`, UI event handlers, subscriptions, state
used by the UI, CSS, or application lifecycle must preserve the headless
TestFX suite. It uses JavaFX's built-in headless platform
(`-Dglass.platform=headless`), so it behaves the same locally and in GitHub
Actions (Linux, macOS, Windows jobs). During development, first run the smallest
relevant UI test namespace or test var, along with related unit tests. Do not
rerun the complete TestFX suite after every edit when a focused test can cover
the change. Before considering UI work complete, after focused checks pass:

1. Run the relevant unit tests.
2. Run `clj -M:test-ui:win:test-ui-headless` (or your platform alias).
3. Run `clj -M:win:test` (or your platform alias) for the complete unit suite.
4. Ensure the GitHub Actions headless UI test jobs pass.

Use `KeyCode/SHORTCUT` (not `CONTROL`) for shortcut modifier clicks in UI
tests, and wrap every state assertion after an interaction in `ui/wait-for!`.
On failure, a screenshot is saved to `test-results/<ns>/<test>.png`.

New interactive controls should have stable JavaFX `:id` values so they can be
queried by CSS selectors. Add or update a TestFX regression test for every
new user workflow, dialog, navigation path, or interaction whose behavior
could regress. Keep hardware, network discovery, MIDI, OSC, Link, and gamepad
services disabled or mocked in UI tests.

### REPL Utilities (in `user` namespace)
```clojure
(start)           ; Start app
(stop)            ; Stop app
(watch-styles!)   ; CSS hot-reload
(help :label)     ; cljfx component docs
```

## File Naming
- Clojure: `kebab-case` namespaces → `snake_case` filenames
- Example: `laser-show.css.grid-cells` → `css/grid_cells.clj`

## Key Documentation
- `docs/CLJFX_STATE_MANAGEMENT.md` - Context vs atoms, subscription patterns
- `docs/STALE_CLOSURES.md` - Common cljfx pitfall and solutions
- `docs/CSS_COLOR_SYSTEM.md` - Theme architecture
- `docs/PROFILING.md` - clj-async-profiler and JFR usage
