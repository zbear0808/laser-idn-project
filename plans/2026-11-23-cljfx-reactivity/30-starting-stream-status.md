# 30 — Show “Starting…” until an output reports streaming

**Status:** Implemented in `5d36428` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24). Planned as: Proposed · **Last verified against:** `7b320b3801b8`

**Supersedes / Superseded by:** none · **Related findings:** [reactivity audit, toolbar projection](../2026-11-23-cljfx-reactivity-audit.md#L74); [output-runtime O-10](../2026-11-16-output-runtime/00-overview.md#L68) and [Phase 4 as built](../2026-11-16-output-runtime/04-phase4-engine-ownership.md#L200-L212).

Source citations are relative to `src/laser_show/`; `T/` means `test/laser_show/`.

## 1. Current behaviour

A fresh headless evaluation of `laser-show.subs/connection-status` (`subs.clj:481–519`) on this input returned `{:running? true :state :streaming :errors [] :status-text "Streaming to 0 of 1"}`. The fixture supplies the rig names and a published report as EDN; no app or engine was started.

```edn
{:projectors {:a {:name "A"} :b {:name "B"}}
 :backend {:streaming {:running? true
                       :status {:engines 1 :wanted 1 :pending? false
                                :projectors {:a {:state :starting}}}}}}
```

The toolbar therefore shows a green indicator and a **Stop** button (`views/toolbar.clj:217–228,262–300`; colors: `css/layout.clj:52–63`). The individual card reads the same report and says **Starting…** (`subs.clj:422–426`; `views/components/rig_strip.clj:66–82,203–229`). Two other selector results: one streaming + one starting, queue drained → green “Streaming to 1 of 2”; an error during a pending sync → “Starting…” even though the error is already in the tooltip vector (`subs.clj:498–519`).

## 2. Root cause: two different kinds of “started”

- **Command progress.** The runtime registers the engine, calls `start!` and finishes its lifecycle command (`backend/output_runtime.clj:146–158,181–196`). `start!` launches the engine thread; opening the driver and trying the first frame happen on that thread (`backend/streaming_engine.clj:489–501,543–581`). `:pending?` means the lifecycle worker is busy or its queue is nonempty. It does not mean “a driver is still starting” (`backend/output_runtime.clj:294–309`).
- **Per-output report.** `status` lists the **union** of wanted and registered IDs. A wanted ID with no engine is `:starting`, or `:error` if a start failure was recorded (`backend/output_runtime.clj:294–309`). For a registered engine, `engine-status` checks, **in order**: safety error; a live running thread whose open/last-attempt timer exceeds 1,000 ms; recorded engine/provider/projector-effects error (an engine error can carry `:busy?`); driver-reported error; a recorded thread that isn't alive. Any of those gives `:error`. Otherwise zero `:frames-sent` gives `:starting`, and a positive count gives `:streaming` with estimated FPS (`backend/output_runtime.clj:32–37,201–208,235–262`). Warnings, scan-rate limits and low-FPS flags do not turn streaming into an error. Errors can supersede earlier streaming and subsequently clear. There is no per-output `:stopping`, `:idle` or `:mixed`.
- **What is counted.** `:frames-sent` starts at zero. A not-ready tick can be skipped. The normal loop increments the count after the driver call returns, including for an empty frame; the error path's recovery send does not increment it (`backend/streaming_engine.clj:60–82,259–265,304–328,396–441`). This is local engine/driver status, not confirmation of packet receipt, physical projection or light emission.
- **Projection bug.** The selector already subscribes to the published report. It counts `:streaming` and `:error` entries, ignores `:starting`, then defaults to `:streaming` for a nonpending, wanted, error-free set (`subs.clj:495–507`). The wrong label doesn't require a missed render.

## 3. Reuse / extend

| Existing contract | Use here |
|---|---|
| [Phase 4, §10: dispatch, ownership, status and toolbar](../2026-11-16-output-runtime/04-phase4-engine-ownership.md#L204-L212); [output-driver error example](../2026-11-19-output-drivers/02-phases.md#L452) | Retain the intent-driven button, runtime publisher, recognized progress colors, stable “Streaming to 1 of 2 · 1 error” wording and named error tooltip. Use today's idle text/link (`views/toolbar.clj:283–289`), not Phase 4's historical “0 of 0”. |
| `streaming-status`, `connection-status` (`subs.clj:416–426,481–519`) | Derive the aggregate once from the supplied context. The rig names at `subs.clj:497–501` label diagnostics; they are not a second source of membership or counts. |
| `wanted` and `status` (`backend/output_runtime.clj:53–66,294–309`) | `:wanted` counts eligible outputs: enabled (default true), with a nonblank host for IDN; another driver's output is wanted even if the driver is missing. `:engines` is the registry size, **not** the numerator or denominator. |

## 4. Changes per namespace

- `laser-show.subs` (`subs.clj:1–16,481–519`): change `connection-status` and its return contract per §7. **NEW `:mixed`** is a value of the existing derived `:state` only: some outputs report streaming and the rest starting. It lets consumers show progress **and** retain the reported rates. Keep the existing return keys; no new namespace or helper is needed.
- `laser-show.views.toolbar` (`views/toolbar.clj:1–6,217–228,269–300`): map `:mixed` to the existing amber connecting class. The label, tooltip, idle-only Projectors link and intent-driven button already consume the selector correctly.
- `laser-show.views.status-bar` (`views/status_bar.clj:1–5,181–219`): in `output-health-text`, show the selector's transition text for `:starting`; include `:mixed` in the rate/count branch so the rate of an already streaming output stays visible. Perform's status line already uses the shared text (`views/tabs/perform.clj:161–176`); its `vibe-output-banner` subscribes to the aggregate and only special-cases off/idle (`subs.clj:2187–2198`). Neither needs its own aggregation.

## 5. State placement

| Key / value | Owner and writer | Save / undo / dirty? |
|---|---|---|
| `[:backend :streaming :running?]` and `[:backend :streaming :status]` (`state/domains.clj:517–523`) | Session data in the app context: intent from the Start/Stop handlers; latest immutable sample from the status event's path-only effect (§6). The toolbar subscribes via `connection-status`, which uses `streaming-status` (`views/toolbar.clj:268–269`; `subs.clj:416–420,495–497`). | No to all three. Backend is outside the saved paths and document roots (`state/persistent.clj:68–86`; `state/history.clj:57–79`). |
| `[:projectors id :name]` (`subs.clj:497–501`) | Existing rig configuration; the settings handler applies a rename (`events/handlers/projector.clj:396–408,426`). Read only to name errors here. | The rig is saved and undoable; changing a name isn't a show-dirty edit (`state/persistent.clj:75–79`; `state/history.clj:17–23`). |
| Registry, queue, wanted/failed/busy bookkeeping; per-engine stats | Runtime atoms (`backend/output_runtime.clj:69–87,146–175,181–196,363–376`); lifecycle commands manage engines, the engine thread updates its stats (`backend/streaming_engine.clj:118–137,396–418`). | No to all three. No runtime deref from a view. |
| Aggregate state/text/error vector, including **NEW `:mixed`** | Pure return value of `connection-status`, recomputed from its subscriptions; no writer or additional database path (`subs.clj:481–519`; [reactive-state guide](../../docs/CLJFX_STATE_MANAGEMENT.md#L5-L13)). | No to all three. |

## 6. Events and effects

No event or effect changes. The existing path explains why the report can lag a click:

| Event / effect (all existing) | Dispatcher → handler → effect |
|---|---|
| `:idn/start-multi-streaming`, `:idn/stop-multi-streaming` | Toolbar action `views/toolbar.clj:290–300` → route `events/handlers.clj:64–65` → pure handlers return `:state` and `:multi-engine/start` or `:multi-engine/stop` (`events/handlers/connection.clj:15–26,40–46`). Rig changes while on can request `:multi-engine/refresh` through middleware (`events/handlers/output_sync.clj:13–34`). |
| `:state`, `:multi-engine/start`, `:multi-engine/refresh`, `:multi-engine/stop` | Registered at `events/core.clj:719–729`; state commits first (`events/core.clj:655–672`). The lifecycle effects enqueue work (`events/core.clj:569–574,583–587`); driver I/O stays on its runtime threads. Per-output threads are `idn-streaming-<id>` / `output-streaming-<id>`, default target 60 fps (`backend/streaming_engine.clj:10–16,38–40,104–117,461–466`). |
| `{:event/type :output/status :status sample}`, `:output/write-status` | `output-status` polls at a 250 ms fixed delay and dispatches when its sample differs from app state (`backend/output_runtime.clj:30,311–329,342–353`). The pure handler returns only the registered path-write effect (`events/handlers/connection.clj:28–34,43–46`; `events/core.clj:576–581,729`). With the app mounted this runs on `laser-show-events` (`app.clj:95–112`; `events/core.clj:764–785`). It is a periodic observation, **not** a request-ID-matched completion of Start/Stop; it never writes intent. No new callback, polling timer or per-frame event. |

## 7. Decision table and invariants

Use one sample. Let **M** be its `:wanted`, **R** its `:engines` (zero for a nil sample), and **N/S/E** the numbers of `:streaming`/`:starting`/`:error` reports. A sample is **comparable** when non-nil, nonpending and reporting exactly M distinct IDs. The union includes every wanted ID, so equal cardinality means no extra retiring IDs. An empty, comparable set is idle while intent is on; show an **N of M** ratio only for a comparable set with M > 0. The snapshot has no per-entry “wanted” bit (`backend/output_runtime.clj:294–309,371–375`); don't clamp N or join to a newer rig to manufacture a ratio.

First matching row wins. “Off/on” means **operator intent**, not the engine's running flag. Indicator classes for green/amber/red are respectively `status-indicator-connected`, `status-indicator-connecting`, `status-indicator-disconnected` (`views/toolbar.clj:217–228`; `css/layout.clj:52–63`).

| Condition | Aggregate `:state` | Toolbar text | Indicator / button |
|---|---|---|---|
| Off, R > 0 | `:stopping` | `Stopping…` | Amber / Stream |
| Off, R = 0 (including nil; a queue flag alone is not a registered engine) | `:stopped` | `Not streaming` | Red / Stream |
| On, E > 0 | `:error` | If a ratio is valid: `Streaming to N of M` for N > 0, **NEW** `0 of M streaming` otherwise; append ` · S starting` if S > 0. Without a valid ratio use **NEW** `Updating outputs…`. In both cases append ` · E error` or ` · E errors`. | Red / Stop |
| On, no comparable sample yet; no errors | `:starting` | `Starting…` for startup; `Updating outputs…` when the sample already contains a streaming output or extra retiring IDs. Never a ratio during reconciliation. | Amber / Stop |
| On, comparable, M = 0 | `:idle` | `No projectors enabled` + existing Projectors link | Amber / Stop |
| On, comparable, S = M > 0 | `:starting` | `Starting…` (no ratio) | Amber / Stop |
| On, comparable, N > 0 and S > 0, E = 0 | **NEW `:mixed`** | **NEW** `Streaming to N of M · S starting` | Amber / Stop |
| On, comparable, N = M > 0 | `:streaming` | `Streaming to N of M` | Green / Stop |

**Example fixtures.** Each row has intent on. Input is `[R M pending? {id report …}]` using `A` = `{:state :starting}`, `T` = `{:state :streaming :fps 59.8}`, `X` = `{:state :error :error "open failed"}`. These expand directly to the EDN in §1.

| Input | Expected `[state text]` |
|---|---|
| `[1 1 false {:a A}]` | `[:starting "Starting…"]` |
| `[2 2 false {:a T :b A}]` | `[:mixed "Streaming to 1 of 2 · 1 starting"]` |
| `[1 2 false {:a A :b X}]` (B failed before registration) | `[:error "0 of 2 streaming · 1 starting · 1 error"]` |
| `[2 1 true {:old T :a T}]` (old engine retiring) | `[:starting "Updating outputs…"]`, never “2 of 1” |
| `[2 1 true {:old X :a T}]` | `[:error "Updating outputs… · 1 error"]`, with the reported reason in the tooltip |

**Invariants:** For a comparable sample, N + S + E = M; only published `:streaming` counts in N. Green requires *all* wanted outputs to report `:streaming` and M > 0. Warnings on those entries don't increase E. Errors take precedence over starting/mixed/pending while on; off intent takes precedence over old reports. Keep one tooltip entry per published error, sorted by ID, using the rig name or ID fallback (`subs.clj:497–501`; `views/toolbar.clj:277–289`). Neither a status event nor the aggregate changes what the button will do.

## 8. Acceptance tests and fixtures

| Test var | Fixture and assertion |
|---|---|
| Extend `laser-show.subs-test/connection-status-says-why-nothing-streams-test` (`T/subs_test.clj:580–593`) | Existing `base-state` and real cljfx `context` (`T/subs_test.clj:24–36`). Keep settled idle/full/stopped; add on + nil, off + stale error with R > 0, and off + pending with R = 0. Assert intent along with state and label. |
| **NEW** `laser-show.subs-test/connection-status-follows-published-projector-states-test` (in `T/subs_test.clj`) | Advance the same fixture's context with the §7 samples. Include pending → drained **without changing A** → T, mixed → full, all failed with zero engines, and an extra retiring ID even with the pending flag cleared. Check exact ratios, singular/plural, sorted error names/ID fallback and tooltip entries clearing on recovery. A streaming entry with a warning still counts once. |
| Extend `laser-show.views.status-bar-test/output-health-text-test` (`T/views/status_bar_test.clj:111–132`) | Its plain maps already test errors and a no-rate fallback. Supply the selector's `:status-text` in its starting fixtures: verify both `Starting…` and `Updating outputs…`, and that the mixed aggregate with T + A still yields `59.8 fps`. |
| **NEW** `laser-show.ui-test.toolbar-test/toolbar-follows-published-start-progress-test` (in `T/ui_test/toolbar_test.clj:1–14`) | Headless `with-toolkit` / `with-app` (`T/ui_test/fixtures.clj:18–25,48–59`), no DAC fixture. Seed intent and names with `events/write-state!` (`events/core.clj:853–865`); dispatch synthetic `:output/status` reports through `events/dispatch!` (`events/core.clj:811–835`). Wait for each stored sample **and** re-query `#streaming-status-label`, `#streaming-indicator` class and `#stream-btn` for all-starting → mixed → error → full. Assert “Stop” throughout and that the error tooltip goes away. Use `wait-for!`, `assert-text!` and `has-style-class?` (`T/ui_test/testfx.clj:113–116,174–188,637–643`); no Stream click, timer race or hardware. |

Retain these existing contract tests:

- `laser-show.backend.output-lifecycle-test/stop-does-not-wait-for-a-blocking-open-test` and `laser-show.backend.output-lifecycle-test/stuck-open-shows-not-responding-test` (`T/backend/output_lifecycle_test.clj:824–857`): recording driver with delayed open plus loopback fake DAC; registry/cleanup fixtures at `:36–83,715–726`. They distinguish a drained queue from an open still in progress, and pin starting → stuck error → streaming.
- `laser-show.events.output-sync-test/output-status-is-not-an-edit-test` (`T/events/output_sync_test.clj:16–36,132–146`): marked-clean streaming fixture; a sample changes neither history nor project dirty state and requests no sync.
- `laser-show.ui-test.streaming-status-test/toolbar-shows-streaming-n-of-m-test`, `laser-show.ui-test.streaming-status-test/toolbar-counts-errors-test`, `laser-show.ui-test.streaming-status-test/no-projectors-enabled-links-to-the-projectors-tab-test` (`T/ui_test/streaming_status_test.clj:58–82,128–143`): existing mounted app + loopback fake DAC, including a stubbed resolution failure (`:25–50`). Preserve their full, partial-with-error and idle outcomes.

## 9. Out of scope

**#18 owns output thumbnails**, both what a frame represents and when its preview expires ([audit lines 73 and 90](../2026-11-23-cljfx-reactivity-audit.md#L73-L90)). This plan changes the projection of published engine states, not frame capture, driver behavior, lifecycle ordering or the meaning of a successful driver return.
