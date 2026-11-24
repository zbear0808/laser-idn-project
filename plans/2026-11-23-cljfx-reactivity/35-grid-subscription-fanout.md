# 35 · Only rebuild the grid cells whose indicators change

**Status:** Proposed · **Last verified against:** `7b320b3` (headless counts below)

**Supersedes / Superseded by:** None. **Related findings:** [reactivity audit, “A per-cell lookup…”](../2026-11-23-cljfx-reactivity-audit.md#subscription-hot-spots-that-were-measured); [November 7 F16](../2026-11-07-improvement-review/01-views-ui.md#L102-L103) and its [M4 binding-index proposal](../2026-11-07-improvement-review/01-views-ui.md#L281-L284). The earlier [F11](../2026-11-07-improvement-review/01-views-ui.md#L67-L71) grid fix (`03314bf`, [review status](../2026-11-07-improvement-review/00-overview.md#L33)) is a regression guard.

## 1. Current behaviour and measured baseline

Moving the selection from A1 to A2 rebuilds **32 display maps on the selected grid for two changed values**. Adding a Vibe binding to a previously unbound key runs **32 cue and 32 effect cell wrappers for zero changed descriptions**. These are two different costs.

Source paths are relative to `src/laser_show/`; `deps.edn`, `test/`, `docs/` and `plans/` are at the repository root. The defaults are 8 columns × 4 rows (`state/domains.clj:27–28,139–148`). The shell shows one tab at a time (`views/root.clj:66–75`); the cue and effects tabs supply their respective cell components (`views/tabs/grid.clj:19–34`, `views/tabs/effects.clj:16–27`). Each row already supplies stable `:fx/key [col row]`, coordinates and size (`views/components/grid_tab.clj:84–102`).

**Measured at `7b320b3`:** fresh headless JVM, real cljfx 1.10.6 (`deps.edn:12–16`), both sets of 32 addresses in one isolated context. Empty chains and bindings, both selections initially `[0 0]`, fixed size `[80 60]` and font size `12`. Warm all display selectors and wrapper descriptions. From that same baseline, apply each single edit with `fx/swap-context` and request all 64 again through `fx/sub-ctx` (context operations: `docs/CLJFX_STATE_MANAGEMENT.md:148–156`). Count actual selector/wrapper bodies with stable counting functions installed before warm-up; compare returned values with `=`. A 4,096-entry comparison LRU isolates dependencies from eviction.

| Single edit | Display-body calls, cue / effect | Wrapper-body calls, cue / effect | Unequal display maps / wrapper descriptions (total) |
|---|---:|---:|---:|
| Cue selection `[0 0]` → `[1 0]` | 32 / 0 | 2 / 0 | 2 / 2 |
| Effects selection `[0 0]` → `[1 0]` | 0 / 32 | 0 / 2 | 2 / 2 |
| Bind keyboard `:h` to `{:type :vibe :role :hit}` | 0 / 0 | 32 / 32 | 0 / 0 |
| Begin learning that Vibe role | 0 / 0 | 32 / 32 | 0 / 0 |
| Begin learning cue `[1 0]` | 0 / 0 | 32 / 32 | 0 / 1 |

This counted descriptions, without mounting nodes or starting services. In the app those views render on the JavaFX thread (`events/core.clj:16–27`); the separate `show-clock` targets 60 Hz and projector engines send on their own threads (`services/show_clock.clj:1–5,23–33,68–72`). No UI latency, node replacement, FPS or output throughput was measured.

## 2. Root cause

- `cell-display-data` subscribes to the entire selected cue coordinate before walking the preset tree (`subs.clj:250–291`); `effect-cell-display-data` does the same for the effects selection and flattens its chain (`subs.clj:337–373`). Comparing the coordinate **inside** the map builder is too late: all 32 builders depend on it. Equal results already protect 30 wrappers, but don't recover the work of building 30 maps.
- `grid-cell` and `effects-cell` each subscribe to the complete `input-trigger-map` and `input-learning-target`, scan all bindings until a match, and compare the whole learning target locally (`views/components/grid_cell.clj:280–306,460–494`; sources `subs.clj:1574–1584`). A change to either input reruns the wrapper even when both booleans stay false. `generic-grid-cell` itself takes display data as props; it renders the CSS classes, LEARN badge and ⚡ indicator (`views/components/grid_cell.clj:38–52,103–110,133–151,200–232`). The dependency belongs **before** this description is built.

## 3. Reuse / extend

| Existing thing | How it is reused |
|---|---|
| `selected-grid-cell`, `selected-effects-cell` (`subs.clj:80–88`) | These supply the two independent selection coordinates. Put a boolean between each coordinate and its display-map consumer. |
| `cue-cell-chain`, `cue-cell-active?`, `effect-cell-chain` (`subs.clj:197–201,240–248,331–335`) | Preserve F11: read the chain at this coordinate and cue membership as a boolean. Editing another chain, changing a still-active run's timing, or publishing frame stats must not invalidate this cell's display map. |
| `input-trigger-map`, `input-learning-target` (`subs.clj:1574–1584`); `vibe-cell-index` → `indexed-cell-vibes` (`subs.clj:1898–1905`) | Reuse the first two as sources. Reuse the **pattern** of a shared index followed by a cell lookup; Vibe pool membership in the existing index is a different fact. |

## 4. Changes per namespace

**`laser-show.subs` (`subs.clj:1–26`).** Add four named `fx/sub-ctx` selectors. Only stable values follow the supplied context in their argument lists; a fresh predicate, partial or a parent's context would make a poor cache key. Each caller subscribes through its own context (`docs/CLJFX_STATE_MANAGEMENT.md:5–8`).

| Selector | Input → output and consumer |
|---|---|
| **NEW** `cell-selected? [context action-type col row]` | For `:trigger-cue` read only `selected-grid-cell`; for `:trigger-effect` read only `selected-effects-cell`. Compare to `[col row]` here; return literal true/false, including false for nil. Replace only the selection dependency in the two display selectors. |
| **NEW** `cell-trigger-targets [context]` | One shared subscription, **no cell or grid argument**. On a changed `input-trigger-map`, traverse its values once and produce an eager set of `[type target]` for the two cell action types. Example: `#{[:trigger-cue [1 0]] [:trigger-effect [1 0]]}`. A cell has ⚡ when **any** entry matches, independent of physical key, behavior, playback and whether the cell is populated. |
| **NEW** `cell-has-trigger? [context action-type col row]` | Membership of `[action-type [col row]]` in `cell-trigger-targets`, as a boolean. Both wrappers subscribe to **this boolean**. They must not subscribe to the set and perform membership themselves; then any change to another cell's membership would rerun them all. |
| **NEW** `cell-learning? [context action-type col row]` | Compare the active `input-learning-target` with `{:type action-type :target [col row]}` inside this selector. Today the writer strips `:behavior` from target identity but retains it on the learned action (`events/handlers/input.clj:239–247,291–316,380–386`). Only this boolean controls the style and LEARN badge. |

**`laser-show.views.components.grid-cell` (`views/components/grid_cell.clj:1–7`).** Replace the two wrappers' binding/learning reads. Preserve the cue wrapper's conditional held state, warning, target badge, pattern, tooltip and paste availability (`views/components/grid_cell.clj:305–328`), and the effect wrapper's chain-derived label, tooltip and menu eligibility (`views/components/grid_cell.clj:444–458,484–509`). Supply the existing `:selected?`, `:has-trigger?` and `:learning?` to the generic cell; use the last two for the menus (`views/components/grid_cell.clj:354–391,510–548`). Keep the IDs: `:cell-type :grid` → `#grid-cell-1-0`, `:cell-type :effects` → `#effects-cell-1-0` (`views/components/grid_cell.clj:144–151,377–384,535–542`). The dialog modes `:cue` / `:effects` (`views/components/grid_cell.clj:369–376,524–530`) are not trigger action types.

**Integration with [24 · Input learning, §5–6](24-input-learning.md#5-state-placement-and-readers):** keep its projection of the active target. The Cancel menu also needs that attempt's identity: read 24's **NEW** `subs/learn-for-target [context target]` only for the cell showing Cancel. A restart for the *same* target leaves the badge on but must refresh that cell's Cancel payload; the old menu must not cancel the replacement. Keep 20/24's document identity on document-bound intents. A new document is a real change to those payloads. Do not pass the global request to every cell or implement the attempt lifecycle here.

## 5. State placement

| Key / value | Lives in; written by | Undo / dirty? |
|---|---|---|
| `[:grid :selected-cell]`, `[:effects :selected-cell]` (`subs.clj:80–88`) | Existing session UI in the app context. Grid select/click and effects toggle return these in their next state (`events/handlers/grid.clj:95–111,129–131`, `events/handlers/effects.clj:29–50`). Read via `cell-selected?`. | Neither; the saved grid fields are size and column targets (`state/persistent.clj:68–73`, `state/history.clj:62–79`). |
| `[:config :input :trigger-map]` (`state/domains.clj:286–287`) | Existing show document. Capture/remove/clear/behavior handlers return changes (`events/handlers/input.clj:380–389,427–465`); cell move/clear also retarget/remove bindings (`events/handlers/grid.clj:133–145,178–187`, `events/handlers/effects.clj:52–58,79–91`). | Saved, undoable, marks dirty (`state/persistent.clj:68–71`, `state/history.clj:14–19`). |
| `[:backend :input :learning-target]` (`subs.clj:1580–1584`) | Existing transient status in app state, set at start and cleared on capture/cancel (`events/handlers/input.clj:246–247,308–316,380–386,417–425`). Read via `cell-learning?`. | Neither (`state/history.clj:62–79`). |
| **NEW derived values only:** target set and three cell booleans | Evaluated on demand in the existing cljfx context cache (`state/core.clj:38–51`). No writer, persisted key, runtime atom, or invalidation event. | Neither. |

## 6. Events and effects

No events or effects are introduced or hooked. The existing routes explain which changes the selectors must observe:

| Existing route | Dispatcher → handler/effect |
|---|---|
| `:grid/cell-clicked`, `:effects/toggle-cell` | Native click and wrapper event maps (`views/components/grid_cell.clj:174–178,377–386,535–545`) → pure state transitions (`events/handlers/grid.clj:100–131`, `events/handlers/effects.clj:29–50`). [10 · Grid click intent](10-grid-click-intent.md) owns deciding occupancy at event time. |
| `:input/start-learn`, `:input/cancel-learn`, `:input/clear-cell-triggers` | Cell menus (`views/components/grid_cell.clj:354–368,510–523`) → input dispatch (`events/handlers/input.clj:509–516`). Start/cancel own the current listener/promise; clear filters by type **and** target (`events/handlers/input.clj:285–316,417–442`). The new selectors perform no I/O. |
| `:input/await-learn` → `:input/learn-captured` | Start supplies the completion event (`events/handlers/input.clj:308–316`); the registered effect dispatches the captured input (`events/core.clj:313–322,719–724`). The handler matches the current target, writes the full binding and clears learning (`events/handlers/input.clj:351–389`). The existing `:state` effect commits through the ordered pipeline (`events/core.clj:655–672,719`). |

## 7. Inputs → expected outputs

`C` = `{:type :trigger-cue :target [1 0]}`, `E` = `{:type :trigger-effect :target [1 0]}`, `V` = `{:type :vibe :role :hit}`. `K` = `{:source :keyboard :id :h}`, `M` = `{:source :midi :channel 0 :note 60}` are **stored lookup keys**, not device events (`events/handlers/input.clj:318–326`; MIDI fixture `test/laser_show/events/handlers/grid_trigger_test.clj:44–55`). Counts below are acceptance targets with a warm, non-evicting working set.

| Input change | Expected observable result |
|---|---|
| 1. Selection `[0 0]` → `[1 0]`, independently on each grid | Only old/new `:selected?` change: `true → false`, `false → true`. **2** full display builds on that grid, **0** on the other. Reselecting the same coordinate: 0; nil → cell: 1. |
| 2. Bindings `{}` → `{K V}` | One index build; index still `#{}`. **0 cue / 0 effect** wrapper calls. No cell gains ⚡ or “Clear Triggers”. |
| 3. Bindings `{K C, M C}` → `{M C}` → `{M E}` → `{M V}` | The pair of badges `[cue effect]` goes `[true false] → [true false] → [false true] → [false false]`. Removing one of two inputs leaves the indicator on; retargeting the last input changes both affected cells, even at the same coordinates. Replacing `:follow` with `:momentary` alone changes neither badge; the editor must still show the new behavior. |
| 4. Active learn target `nil → V → C → E → nil` | The set of cells showing LEARN is `#{} → #{} → #{C} → #{E} → #{}`. Respectively 0, 1, 2, 1 wrapper calls across the two grids; zero binding-index builds. Starting a non-cell Learn while a cell is learning also clears that cell's LEARN. |
| 5. Capture at E: `[learning-target, trigger-map]` changes from `[E {}]` to `[nil {K {:type :trigger-effect :target [1 0] :behavior :momentary}}]` | Only E changes its `[:learning? :has-trigger?]` flags: `[true false] → [false true]`. LEARN/Cancel disappear and ⚡/Clear appear in the same committed state. The action still carries `:momentary`. |

**Invariant:** one binding-map traversal per changed map when the index is demanded and resident. Cheap boolean lookups may be rechecked across a grid; costly map/description bodies depend on the resulting values. Old/new-cell bounds apply to an isolated edit, not to a resize, a paste-availability change or Stop holding several active cues: everything that actually changes its display must still update.

## 8. Acceptance tests

| Test var | Fixture and assertion |
|---|---|
| **NEW** `laser-show.subs-test/grid-selection-rebuilds-only-old-and-new-cells-test` | Extend the real-context fixture with nested cues and populated effects (`test/laser_show/subs_test.clj:1–44`). Warm both grids at comparison capacity; apply input 1 and count the actual display bodies by coordinate. Assert the two changed values **and** `identical?` maps for the other 30 and the untouched grid. Include nil and reselect. |
| **NEW** `laser-show.subs-test/cell-trigger-index-is-shared-by-both-grids-test` | Same context across all 64 typed addresses. Count the index body once on first demand, once per changed map for inputs 2/3, zero for selection/learn-only updates. Assert exact membership, including two keys for an empty cell, both action types at one coordinate, last removal and a behavior-only edit. |
| **NEW** `laser-show.views.components.grid-tab-test/cell-wrappers-rerun-only-for-changed-indicators-test` | Extend the existing grid component tests (`test/laser_show/views/components/grid_tab_test.clj:1–8`). Use the §1 fixture: stable counted adapters call the **real** two wrappers with their bound context and fixed props. For inputs 2–5 assert the *set of addresses invoked* as well as flags/menu descriptions. Merely comparing equal descriptions would let today's 64 needless calls pass. Renaming a populated cell must still update its label data. With 24 integrated, restart Learn on the same cell: only its Cancel menu gets the new attempt ID. |
| **NEW** `laser-show.ui-test.grid-test/cell-learn-and-trigger-badges-follow-the-target-test` | Headless mounted starter, `with-toolkit` / `with-app` (`test/laser_show/ui_test/fixtures.clj:18–25,48–59`). On each tab, use the cell menu and a synthetic keyboard press in the router's real shape (`test/laser_show/ui_test/trigger_editor_test.clj:43–48,130–156`). Re-query the model and visible cell: `.grid-cell-learning-badge` and “Cancel Learning Trigger” while waiting, then `.grid-cell-trigger-indicator` and “Clear Triggers” after capture, neither after clear; the opposite kind at `[1 0]` stays unmarked. Use `#grid-cell-1-0` / `#effects-cell-1-0` and the existing menu-text lookup (`test/laser_show/ui_test/grid_test.clj:196–217`), not new node IDs. |

Retain `laser-show.subs-test/cell-display-data-ignores-unrelated-changes-test` and `laser-show.subs-test/effect-cell-display-data-test` (nested chains, own/other cell and clock status, `test/laser_show/subs_test.clj:18–44,70–106`), including [11's disabled-group/occupancy fixture](11-stored-effect-occupancy.md#8-acceptance-tests-and-fixtures). Keep `laser-show.ui-test.grid-test/held-cell-has-an-outline-and-no-fill-test` (Stop, select an empty neighbour, `test/laser_show/ui_test/grid_test.clj:96–117`) and `laser-show.ui-test.trigger-editor-test/trigger-editor-learns-input-and-configures-behavior` (capture, change behavior, remove a key, `test/laser_show/ui_test/trigger_editor_test.clj:130–170`).

## 9. Order and scope

- **Land [11 · Stored effect occupancy](11-stored-effect-occupancy.md) first:** both changes touch `effect-cell-display-data` (`subs.clj:353–370`). Carry its stored/enabled/creation distinctions through unchanged; do not reintroduce enabled-only flattening as an occupancy test (`animation/chains.clj:68–89`). Preserve 10's click fix when integrating the cue wrapper. This page changes the dependency for selection, not what a cell holds or plays.
- Keep the index for the grid indicator only. The trigger editor lists individual keys and behavior (`views/dialogs/cell_trigger_editor.clj:14–22,44–54`, `views/components/trigger_bank.clj:96–118,133–163`). The input resolver has its own definition precedence and press/release behavior (`events/handlers/triggers.clj:30–53,65–79,128–141`). Neither should consume a presence boolean in place of the action data. Menu materialization, drag handlers, and Learn attempt ownership belong outside this change.
- **[36 · Cache working set](36-cache-working-set.md) measures the final tree.** It can record the before baseline now; rerun its mounted Grid, Vibes and open-editor scenarios after 35 and the other subscription/retention repairs. Production currently uses 512 entries (`state/core.clj:48–51`); the real-root call-count probe is `plans/probes/cljfx_reactivity_2026_11_23.clj:73–93`. The local counts above isolate a dependency error; they neither choose a production capacity nor establish an FPS gain.
