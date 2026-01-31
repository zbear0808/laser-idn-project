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
clj -M:test  # Note: test coverage is incomplete
```

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
