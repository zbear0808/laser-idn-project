# 29 · Tell the map being edited apart from the map chosen on a tick

**Status:** Implemented in `153b648` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24). Planned as: Proposed · **Last verified against:** `7b320b3801b8`

**Supersedes / Superseded by:** none · **Related findings:** [reactivity audit, map editor](../2026-11-23-cljfx-reactivity-audit.md#L74); [Z4, UI map vs tick map](../2026-11-19-zones/06-phase-z4-zones-maps-targets.md#L498); [Z5, pressing a map during a cycle](../2026-11-19-zones/10-questions.md#L319-L321).

## 1. Current behaviour

**The command works; the editor disables it.** In a headless, in-memory check of the real view descriptions, subscriptions and handler, the editor was inspecting Unison, the stored manual map. With an enabled default Map cycle in cell `[1 1]`, the pure tick rule at beat `4.0` chose Odd / Even. The editor returned `"in use"`, a `●` on Unison and `:disable true`; Unison's pad returned `:active? false`. Passing the button's own event to the handler left the live map slot at `{:active :map/unison :pending nil :cycle-paused? true}`. This checked a routing decision, not a projector or a sent frame.

Source citations are relative to `src/laser_show/`; `T/` means `test/laser_show/`.

| Question | Source of truth today |
|---|---|
| Which map am I editing? | `subs/selected-zone-map-id` resolves `[:ui :zone-maps :selected]` if the map exists, else uses the resolved manual map. `subs/active-zone-map` reads `[:zone-map :active]`, falling back to Left / Right for a missing ID (`subs.clj:1021–1031,1136–1143`). Clicking a row only selects it (`views/components/zone_maps_panel.clj:62–76`; `events/handlers/zone_maps.clj:65–66,210–213`). |
| What does the editor claim? | `map-list` and `map-editor` independently compare the row/edited ID with the resolved manual ID. That drives the dot, “in use” and **Use now**'s disable flag (`views/components/zone_maps_panel.clj:73–82,97–99,208–234`). The editor's Delete confirmation makes the same “in use” claim from the stored ID (`events/handlers/zone_maps.clj:163–177`). |
| What do the pads show? | `subs/map-cycle-running?` means an on cell contains an enabled cycle and the cycle isn't paused; `subs/map-pad` suppresses the manual active mark in that mode and tracks a queued switch separately (`subs.clj:1091–1112`; `routing/zone_maps.clj:78–88`; enabled leaves/groups: `animation/chains.clj:68–89`). The CYCLE pad gets its lit/paused/off state from app data (`subs.clj:1255–1271`; `views/components/zone_map_pads.clj:184–191,227–249`). The separate `↻` on a map pad is painted from a **fresh, unpaused cycle pick** at `[:routing :cycle :map]`, with a 40 ms sample interval (`views/components/zone_map_pads.clj:50–68,129–151`). It is **not** a read of the tick's final `[:routing :map]`. |
| Which map did zone routing use? | The `show-clock` daemon targets 60 ticks/s (`services/show_clock.clj:68,316–331,342–351`). It takes one state snapshot; the last enabled cycle in reading order supplies resolved parameters, and beat/press data selects among known maps (possibly none): `routing/zone_patterns.clj:236–271`; `services/show_clock.clj:239–256,267–281`. `effective-map-id` takes a **due pending** map first (due `:cycle` means pick or manual, even if paused), else an unpaused pick, else manual; an unknown result falls back to Left / Right (`routing/zone_maps.clj:90–104`). The published **routing** carries `:map`; on resolver failure it can retain the previous routing or have no map (`routing/rig.clj:429–438`; `services/show_clock.clj:208–225,290–304`). |

## 2. Root cause

One equality was used to answer two different questions: “is this my manual map?” and “would taking manual control here do anything?” Even when the cycle happens to pick the manual map, **Use now** can pause it there. Even with cycling paused or off, the same button can clear a waiting switch. Conversely, an enabled cycle with no valid picks can fall back to the manual map. Neither “this map is in use” nor “this map is *not* in use” follows from cycle mode alone. A due switch can also affect a tick before its commit reaches the UI (`routing/zone_maps.clj:90–104,149–163`; `events/handlers/timing.clj:185–192`).

## 3. Reuse / extend

| Existing thing | Use in this repair |
|---|---|
| `subs/zone-map`, `active-zone-map`, `selected-zone-map-id`, `map-cycle-running?` (`subs.clj:1021–1031,1091–1096,1136–1143`) | Keep entity lookup, fallback, editor navigation and the pads' test for cycle mode. Clarify the docstrings: “manual” and “enabled, unpaused” are state facts. |
| `zone-maps/commit` and `handle-use-now` (`routing/zone_maps.clj:149–163`; `events/handlers/zone_maps.clj:254–258`) | Already implement immediate takeover: validate the event's ID in **current** state, set the manual ID, clear pending, pause any on cycle. If the ID no longer exists, leave state alone. |
| `zone-maps/schedule` (`routing/zone_maps.clj:214–236`) | The pad precedent: while cycling its selected control is `:cycle`, so even pressing the stored manual map can schedule a switch. Keep beat/bar scheduling on the pad/learned-key path (`events/handlers/triggers.clj:114–116`). |

## 4. Changes per namespace

1. **`laser-show.subs`** (`subs.clj:1–16,1091–1112`): add **NEW** `manual-map-marker-id [context]` to share the manual-mark rule across the list and pads. Return `nil` when `map-cycle-running?` is true; otherwise return the resolved manual ID. Make `map-pad` mark `:active?` only for a matching, non-nil marker. Its bindings, pending decoration and clock painter remain independent. `nil` means **no manual marker**, not “no map routed.”
2. In the same namespace add **NEW** `editor-map-switch [context id]`, a pure `fx/sub-ctx` projection for the editor. Example **NEW derived result**: `{:mode :cycle :manual-name "Unison" :queued? false :can-use-now? true}`. Use the shared marker for mode (`:manual` when non-nil), the resolved manual map for its name, `subs/zone-map` to check the target and cheap `fx/sub-val` lookups of the **stored** active ID and pending slot. **Enable only when** the edited ID exists and at least one holds: cycle mode, a pending switch exists (any target, including `:cycle`), or the stored ID differs from the edited ID. Comparing the *stored* ID allows an explicit choice to replace a dangling ID that merely displays as Left / Right. No beat, pulse, wall clock or runtime snapshot read in a subscription; follow [Reactive state in this app](../../docs/CLJFX_STATE_MANAGEMENT.md#reactive-state-in-this-app).
3. **`laser-show.views.components.zone-maps-panel`** (`views/components/zone_maps_panel.clj:1–14,62–99,208–234,262–269`): keep the selected highlight, title, lanes and warnings attached to the *edited* map. Where the list showed `●`, show **“Manual”** only on the shared marker row. Replace “in use” with a wrapping status **below** the title/button row (**NEW** control ID `zone-map-mode`): **“Cycle mode · Manual fallback: Unison”** in cycle mode; **“Manual map: Unison”** in manual mode, using the resolved name. Append **“ · Switch queued”** while app state holds a pending request. A paused cycle uses the manual wording. Bind the existing button to the projected availability; tooltip: **“Choose this map now, pause any running Map cycle, and clear any queued switch.”** Both list and editor subscribe through their own supplied contexts; browsing never takes over or follows the tick.
4. **`laser-show.events.handlers.zone-maps`** (`events/handlers/zone_maps.clj:1–13,163–192,194–208`): correct the companion Delete text only. For the stored manual map: **“If this is still the manual map, deleting it selects Left / Right and pauses a running Map cycle.”** Replace the unconditional “Nothing else uses it” fallback with **“Delete this map from the show?”** The current check counts pad keys/settings, not cycle references. Keep the actual deletion and confirmation-time checks.

## 5. State placement

| Key / path | Owner and writer | Saved / undo / dirty |
|---|---|---|
| `[:ui :zone-maps :selected]` (`state/domains.clj:219–220`) | Session UI; selection, creation and deletion handlers return it through the pipeline (`events/handlers/zone_maps.clj:65–89,194–213`). List/editor read `selected-zone-map-id`. | No / no / no. |
| `[:ui :dialogs :confirm :message]` | Session UI; the delete request supplies the text (`events/handlers/zone_maps.clj:179–191`); the dialog subscribes via `subs/dialog-data` (`views/dialogs/confirm.clj:39–61`). | No / no / no. |
| `[:zone-map :active]`, `:pending`, `:cycle-paused?` (`state/domains.clj:439–448`) | Live **app state**, not a frame buffer. Immediate commit, pad scheduling and matching due event write the slot; CYCLE or switching a cycle cell on can resume it (`routing/zone_maps.clj:149–178,214–236`; `events/handlers/timing.clj:185–192`; `events/handlers/effects.clj:44–50`). | **Only `:active` is saved**. None enters undo/dirty; Open/New clear pending and pause (`state/persistent.clj:68–73,193–216`; `state/history.clj:24–29,57–79`). |
| `[:zone-maps id]` and cycle definitions in `[:chains :effect-chains]` | Existing show content, read only by this projection; map edits are document edits, cell `:active?` is a live flag (`state/domains.clj:432–437`; `state/persistent.clj:81–86`; `state/history.clj:14–29`; `events/handlers/zone_maps.clj:68–96`). | Definitions: saved / yes / yes. A takeover changes neither definitions nor cell activation. |
| Tick's routing, cycle pick and frames | Runtime only: the clock publishes to `show-time/!snapshot` (`services/show_clock.clj:275–304`; `animation/show_time.clj:352–362,385–399`). | No / no / no. No new timer or async report. |
| Manual marker and editor projection (§4) | Derived while reading the context. No writer and no additional app-state key. | No / no / no. |

## 6. Events and effects

| Intent / effect | Dispatcher → handler / effect owner |
|---|---|
| `:zone-maps/select`, `{:event/type :zone-maps/use-now :map id}` (existing) | Row click and button (`views/components/zone_maps_panel.clj:72,228–234`) → `events/handlers.clj:52–54` → `events/handlers/zone_maps.clj:210–213,254–258,294–295`. Send only the target ID, not a sampled map, queued ID or pause decision. Both handlers return pure `:state` data. Show Lock allows both (`events/show_lock.clj:66–76`). |
| `:zone-maps/request-delete` (existing; wording only) | Delete button (`views/components/zone_maps_panel.clj:240–244`) → `events/handlers/zone_maps.clj:179–192,292–293`: returns `:state` with the dialog. Confirm dispatches its stored `:zone-maps/delete` (`views/dialogs/confirm.clj:81–86`; `events/handlers/ui.clj:84–96`). |
| `:state` (existing) | Registered `events/core.clj:719`; commits the handler result on the single event thread (`events/core.clj:165–173,674–679,764–779`). **No new effect.** In contrast, pad pointer → press → schedule uses the existing `:dispatch` and `:show-clock/read` effects (`views/components/zone_map_pads.clj:133–151,235–248`; `events/handlers/zone_maps.clj:218–252`; `events/core.clj:175–178,299–309,719–723`). Use now does not read a clock or wait for a beat. |
| `:show-clock/zone-map-due` (existing, unchanged) | Built with the pending request ID (`animation/show_time.clj:210–224`), dispatched after publish (`services/show_clock.clj:290–305`). The pure handler commits only if that ID still matches (`events/handlers/timing.clj:185–192`); a report arriving after takeover cleared the slot does nothing. |

## 7. Five cases to pin down

`U` = Unison, `L` = Left / Right, `O` = Odd / Even (`routing/zone_maps.clj:24–35`). The tick column is an **independent routing oracle** under normal resolution, not an input to any editor selector. The button always acts on the edited ID.

| Cycle; stored manual / edited; pending | Pick → tick map | Editor mode; manual badge; Use now | Click result |
|---|---|---|---|
| Off; `L / O`; nil | nil → `L` | Manual; `L`; enabled | Manual `O`; cycling remains off. |
| On; `U / U`; nil; default cycle, beat 4 | `O` → `O` | Cycle; none; **enabled** | Manual `U`, cycle paused now. |
| On; `U / U`; nil; cycle lists only `U` | `U` → `U` | Cycle; none; **enabled** | Same map ID, but the cycle is now paused. |
| On; `U / U`; nil; no known maps in the cycle | nil → `U` | Cycle; none; **enabled** | Pauses the cycle; the UI never said another map was routing. |
| Paused; `U / U`; `{:id 7 :map :cycle :at-beat 8.0 :bar? false}`; beat 8 before commit | `L` → `L` (due resume) | Manual; `U`; queued, **enabled** | Clears pending, keeps `U` and pause. Delayed due `7` is ignored; UI then shows Manual, `U`, **disabled**. |

**Invariants:** Exactly one “Manual” badge in manual mode, none in cycle mode. Those labels are true even before the first tick. They say nothing about frames sent or light displayed. A stable manual choice with no pending switch is the ordinary no-op; changing the inspected row alone never changes routing.

## 8. Acceptance tests and fixtures

- **NEW** `laser-show.subs-test/map-editor-mode-and-takeover-test` in `T/subs_test.clj`: use the real `context` fixture and context advancement (`T/subs_test.clj:24–44`), B2 cell `{:active? true :items [(zone-patterns/cycle-instance)]}` (`T/events/zone_maps_handlers_test.clj:283–289`; default maps/rate: `routing/zone_patterns.clj:99–108,139–143`). Table-test §7; compare every map's manual marker with `subs/map-pad`'s `:active?`. Advance the same context to paused/no pending; ensure an absent target is not actionable and a dangling stored ID can be explicitly replaced by its displayed default. Keep `laser-show.subs-test/map-cycle-state-sub-test` (`T/subs_test.clj:1129–1149`).
- **Extend** `laser-show.events.zone-maps-handlers-test/map-switch-pauses-a-running-cycle-test` (`T/events/zone_maps_handlers_test.clj:293–312`): use its clean `with-cycle` and `run` pipeline fixtures (`T/events/zone_maps_handlers_test.clj:13–19,283–289`). Use now on the **same** manual ID, then with a pending resume, followed by the old due report. Assert manual ID, pause and pending together, unchanged undo stack and clean flag. Existing `switch-is-live-test` already covers an unknown target (`T/events/zone_maps_handlers_test.clj:117–129`). Update the pinned Delete messages in `laser-show.events.zone-maps-handlers-test/delete-map-asks-first-test` (`T/events/zone_maps_handlers_test.clj:221–246`) and `laser-show.ui-test.zones-test/deleting-a-map-asks-first-test` (`T/ui_test/zones_test.clj:478–502`); include a non-manual custom map in an enabled cycle with no pad keys/settings: the prompt must not say it is unused.
- **NEW** `laser-show.ui-test.zones-test/map-editor-can-take-over-on-the-manual-map-test` in `T/ui_test/zones_test.clj`. Use its `with-toolkit`/`with-app` fixtures and `open-zones-tab!` (`T/ui_test/zones_test.clj:19–22,421–425`; hardware-free mount: `T/ui_test/fixtures.clj:48–59`). Seed Unison manual + inspected and the on cycle via `events/write-state!` (`events/core.clj:853–867`); no clock needed. Wait for visible cycle-mode text, no manual badge, and an **enabled** `#zone-map-use-now`. Click it; wait for the state pause **and** re-query the displayed manual label, Unison badge and disabled button. Click another row: title changes, manual badge stays, button enables. Actual control interaction follows `T/ui_test/zones_test.clj:438–442,472–475`.
- **Existing runtime cross-check:** `laser-show.services.show-clock-test/map-cycle-sets-the-effective-map-test`, synthetic 120 BPM ticks and two in-memory laser records (`T/services/show_clock_test.clj:957–999`); and `laser-show.routing.zone-maps-test/effective-map-order-with-a-cycle-test`, including due resume and no pick (`T/routing/zone_maps_test.clj:231–249`). Neither needs physical output.

## 9. Scope

The editor reports **selection mode and the available action**. An exact “map on last routing tick” display would require sampling a fresh `[:routing :map]` at the FX node, with an unavailable state for absent/stale ticks (lifecycle precedent: `views/components/clock_view.clj:31–53,58–77`). This repair does not need that readout. Do not move frames, beats or picks into the app DB, guess a map from a cycle's configured list, or change pad timing or routing precedence.
