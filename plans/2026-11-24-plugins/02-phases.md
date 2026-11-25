# 02 · Phases P0–P5

Each phase lists its changes per namespace, the tests it adds (as test vars with fixtures), and how to tell it is done. Every phase ends with the full unit suite (`clj -M:<os>:test`) and, from P1 on, the UI suite (`clj -M:test-ui:<os>:test-ui-headless`), per `.agents/skills/targeted-ui-testing`.

**Fixtures:** plugin files under NEW `test-resources/plugins/`, one per behaviour, never on a classpath (as `test-resources/addons/README.md` says for add-ons):

| File | Body | Used by |
|---|---|---|
| `bar_flash.clj` | the example in `01` §1 | P1–P3 |
| `logs.clj` | `(p/log "hello" 1)` at the top level | P1 |
| `bad_syntax.clj` | an unbalanced `(` on line 9 | P1 |
| `bad_api.clj` | `{:plugin/api 2}` | P1 |
| `java_io.clj` | `(:require [clojure.java.io :as io])` | P1 |
| `throws_on_7.clj` | an unresolved symbol on line 7 | P1 |
| `spins.clj` | `(p/on :beat (fn [_] (loop [] (recur))))` | P1, P3 |
| `confirms.clj` | sends `:grid/trigger-cell` with `:confirmed? true` | P2 |
| `unlocks.clj` | sends `:vibe/set-show-lock` | P2 |
| `floods.clj` | 300 `:timing/tap-tempo` in one call | P2 |
| `holds.clj` | presses `:vibe/press :hit` and never releases | P2 |
| `watches_bpm.clj` | `(p/watch [:timing :bpm] …)` logging old and new | P3 |
| `on_input.clj` | taps tempo on MIDI note 60 | P3 |

The UI and runtime tests copy fixtures into a temp plugins folder, using `jar_builder`'s `temp-dir!` and `delete-tree!` (`test/laser_show/output/jar_builder.clj:28`, `:33`).

---

## P0 · Sandbox spike (~1 d)

**Changes**
- `deps.edn` (`:11-29`): add `org.babashka/sci {:mvn/version "0.15.60"}`.
- `laser-show.plugins.sandbox` (NEW). It has the fns below, configured per `01` §9.

  | Fn | Purpose |
  |---|---|
  | `base-ctx []` | the shared base context, built once |
  | `plugin-ctx [base api-ns]` | a fork of the base, with the plugin's own API |
  | `read-manifest [source]` | the first form's manifest, read without evaluating, or `{:error …}` |
  | `eval-source! [ctx source {:deadline-ns :out}]` | evaluates the source against a deadline |
  | `call! [ctx f args {:deadline-ns :out}]` | calls a plugin fn against a deadline |
  | `with-deadline` | the interrupt-fn's volatile deadline |

  `laser-show.plugin` is only a stub here (`p/log`).
- No app wiring.

**Tests**
- `laser-show.plugins.sandbox-test` (NEW): one `deftest` per row of `01` §9, e.g.
  - `slurp-is-refused-test`
  - `system-exit-is-refused-test`
  - `interop-is-refused-test`
  - `future-is-refused-test`
  - `java-io-require-is-refused-test`
  - `core-vars-are-read-only-test`
  - `read-eval-is-a-read-error-test`
  - `endless-loop-is-interrupted-test`
  - `endless-reduce-is-interrupted-test`
  - `contexts-are-separate-test`
  - `println-goes-to-the-console-test`
- `laser-show.plugins.sandbox-test/manifest-is-read-without-evaluating-test`: a manifest whose body would `(p/log …)` logs nothing after `read-manifest`.
- `laser-show.plugins.sandbox-bench-test/costs-test` (tagged `:performance`, so `:test` skips it, `deps.edn:132-147`). It prints:
  - `base-ctx` init time
  - `sci/fork` time
  - eval of `bar_flash.clj` and of a 300-line fixture
  - 10 000 `call!`s of an empty fn, with and without the interrupt-fn
- An uberjar smoke check: `clj -T:build uber` (`build.clj:54-102`), then `java -cp target/…standalone.jar clojure.main -e "(require 'laser-show.plugins.sandbox) …"` evaluates `bar_flash.clj`. It is recorded in this plan; it isn't a CI step.

**Done when:** every probe passes on macOS, Windows and Linux; the measured costs are written into `01` §6 and `00` §8, replacing "unverified"; the uberjar check passes.

---

## P1 · Folder, runner, reads, Settings (~2.5 d)

**Changes**

- **`laser-show.common.files`** (NEW): `hidden-name?` and `files-in`, moved from `output/addons.clj:63-66` and `:83-86`, so the two folders share one set of platform rules (output-drivers OD-12). `laser-show.output.addons` requires them, with no behaviour change.

- **`laser-show.plugins.files`** (NEW):

  | Fn | Purpose |
  |---|---|
  | `plugins-dir []` | the folder: JVM property `laser-show.plugins-dir`, else `~/LaserShowProjects/plugins`, as `output/addons.clj:50-58` does |
  | `plugin-file? [f]` | a non-hidden `*.clj` file |
  | `file-id [f]` | the plugin id from the file name (`01` §1) |
  | `read-settings [dir]` | reads `plugin-settings.edn` with `clojure.edn` |
  | `write-settings! [dir settings]` | writes it to a temp file, then moves it into place, like `state/rig_store.clj:158-180` |
  | `sha256 [source]` | the source's hash |

- **`laser-show.plugins.runtime`** (NEW):
  - The registry: `!plugins` (`00` §5).
  - `start! [{:dir :dispatch :settings}]`: creates the threads.
  - `stop! [timeout-ms]`: runs each plugin's `:plugin/stop`, sends its releases, then stops the threads.
  - `sync! [wanted]`.
  - `command! [cmd]`: `:reload id` or `:reload-all`.
  - `await-idle! [timeout-ms]`: for tests and the REPL, like `events/flush!`.
  - The supervisor's scan → stable-for-300 ms → load or reload loop (PL-15). Its watchdog (PL-20), and the `:plugins/status` publisher (≤ 4/s, only on change, `:seq`).
  - P1's API: `p/plugin`, `p/log`, `p/state`, `p/clock`.
- **`laser-show.plugins.view`** (NEW): `state-view [state]` (`01` §5).
- **`laser-show.plugins.sync`** (NEW):
  - `wanted [state]` gives `{:paused? :disabled :show-scripts}`;
  - `wrap-plugin-sync [handler]` adds `:plugins/sync` when `wanted` changes. Same shape as `events/handlers/output_sync.clj:25-34`.
- **`laser-show.state.domains`** (existing): NEW `(defstate plugins …)` (`00` §5), after `backend` (`:517`).
- **`laser-show.events.handlers.plugins`** (NEW): `handle` for `:plugins/started`, `:plugins/status`, `:plugins/reload`, `:plugins/reload-all`, `:plugins/set-enabled`, `:plugins/open-folder`, `:plugins/open-folder-failed`.
- **`laser-show.events.handlers`** (existing, `:44-82`): route `"plugins"`.
- **`laser-show.events.core`** (existing):
  - effects `:plugins/sync`, `:plugins/command` and `:plugins/write-settings` join the map (`:719-753`) and its docstring (`:681-718`);
  - `sync/wrap-plugin-sync` joins the pipeline (`:645-653`) next to `output-sync/wrap-output-sync`.
- **`laser-show.app`** (existing):
  - `start!` (`:203-266`): read the settings, dispatch `:plugins/started` after `:addons/loaded` (`:246`), and call `plugins-runtime/start!` after `input-mapper/start-mapper!` (`:258`).
  - `stop!` (`:308-343`): call `plugins-runtime/stop! 1000` first, before the renderer is unmounted (`:326`), so its releases still reach the event thread; then `events/flush!`.
  - `start-test!` (`:354-398`): takes `:plugins-dir`, and starts the runtime only when it is given, as add-ons do (`:386-388`).
- **`test/laser_show/test_runner.clj`** (existing): NEW `isolate-plugins-dir!` next to `isolate-addons-dir!` (`:188-197`), called at `:200-201`.
- **`laser-show.subs`** (existing): NEW `plugin-rows`, `plugins-dir`, `plugins-paused?`, next to `addon-rows` (`subs.clj:758-779`).
- **`laser-show.views.components.plugin-settings`** (NEW): the Plugins section, modelled on `views/components/addon_settings.clj:68-138`.
  - Header: `#plugins-open-folder-button`, `#plugins-reload-all-button`.
  - The folder path.
  - Rows `#plugin-row-N`, each with `-state`, `-error`, `-reload`, `-enabled`, `-console-toggle` and `-console` (the last 50 lines).
  - With no plugins, `#plugins-empty`: "Drop a .clj file into the plugins folder. See docs/PLUGINS.md."
- **`laser-show.views.tabs.settings`** (existing, `:22-82`): a new group label "AUTOMATION" and the section, after Add-ons (`:78`).
- **`docs/PLUGINS.md`** (NEW): the folder, the file, `p/log`, `p/state`, `p/clock`, the console, the limits. **`README.md`**: a short Plugins section linking to it.
- **CI** (`.github/workflows/build-and-test.yml:143-145`): add `-n laser-show.plugins.runtime-test` to the macOS and Windows shard-1 step that already runs the add-on loader tests. File times and moves differ per OS.

**Tests**
- `laser-show.common.files-test/hidden-and-appledouble-are-skipped-test`. The existing `laser-show.output.addons-test/hidden-and-appledouble-files-are-ignored-test` (`test/laser_show/output/addons_test.clj:460-476`) must still pass.
- `laser-show.plugins.files-test`:
  - `file-id-test`: `bar_flash.clj` → `:machine/bar-flash`, `Bar-Flash.clj` → `:machine/bar-flash`;
  - `settings-round-trip-test`;
  - `settings-write-is-atomic-test`, with an injected move failure as in `test/laser_show/state/rig_store_test.clj:122-145`.
- `laser-show.plugins.sandbox-test/manifest-table-test`: the eight rows of `01` §1.
- `laser-show.plugins.view-test`:
  - `view-has-only-listed-roots-test`;
  - `view-round-trips-as-edn-test` (invariant 7), on `domains/build-initial-state` and on starter state from `state/templates.clj`.
- `laser-show.plugins.runtime-test`, with a temp folder, a recording dispatch and `await-idle!`:
  - `loads-every-plugin-file-test`
  - `failed-plugin-reports-line-test` (`throws_on_7.clj` → `:line 7`)
  - `edit-reloads-within-two-seconds-test` (rewrite `logs.clj`; the generation goes from 1 to 2; the console has both loads)
  - `half-written-file-waits-until-stable-test` (two writes 100 ms apart load once)
  - `deleted-file-unloads-test`
  - `disabled-plugin-doesnt-load-test`
  - `load-budget-test`: a top level that spins → `:failed` "Loading took longer than 2 s"
  - `stuck-runner-is-replaced-test`: a stub API fn that sleeps 6 s, exposed only in this test → `:stopped`; a second plugin still runs
  - `plugin-code-runs-on-the-runner-test` (invariant 1)
  - `status-is-published-on-change-only-test`
- `laser-show.events.plugins-handlers-test`:
  - `started-writes-dir-and-settings-test`
  - `older-status-is-ignored-test` (`:seq`)
  - `set-enabled-writes-settings-test` (returns `:plugins/write-settings` and changes `wanted`)
  - `open-folder-test`
- `laser-show.plugins.sync-test`:
  - `sync-added-only-when-wanted-changes-test`;
  - `sync-not-added-for-unrelated-events-test`.
- `laser-show.ui-test.plugins-settings-test` (NEW). Its fixtures are `fixtures/with-toolkit` and `fixtures/with-app` (`test/laser_show/ui_test/fixtures.clj:48-59`), plus a temp plugins folder given to `app/start-test!` as `:plugins-dir`. Patterned on `test/laser_show/ui_test/addons_settings_test.clj:51-71`.
  - `lists-plugins-test`
  - `failed-plugin-shows-error-line-test`
  - `console-shows-log-lines-test`
  - `disable-and-enable-test`
  - `reload-button-test`
  - `empty-folder-hint-test`
  - `open-folder-button-test` (with `events/set-folder-opener!`, `events/core.clj:403-408`)

**Done when:** all of the above pass, and in the real app (app-repl and plorer skills) an edit to a plugin file shows a new console line within about 1.3 s.

---

## P2 · Actions (~2.5 d)

**Changes**
- **`laser-show.plugins.catalog`** (NEW): `catalog` (`01` §3.3), the named `checks`, and `describe` for `p/actions`.
- **`laser-show.plugins.gate`** (NEW): `admit` (`01` §3.1), and the token bucket (`take-token [bucket now-ms]`).
- **`laser-show.plugins.guard`** (NEW): `wrap-plugin-guard` (`01` §3.5). It uses `vibes.library/audience-cell?` (`vibes/library.clj:140-145`) and `audience-fx?` (`:173`).
- **`laser-show.events.core`** (existing, `:645-653`): `guard/wrap-plugin-guard` goes straight after `show-lock/wrap-show-lock`. It is outer to the show lock, so it runs first.
- **`laser-show.events.show-lock`** (existing): `refuse` (`:155-160`) returns `{:dispatch {:event/type :plugins/refused …}}` for an event with `:plugin/source` (PL-11), and the status-bar notice otherwise, as today.
- **`laser-show.plugins.runtime`** (existing after P1):
  - `p/dispatch!` and `p/actions`;
  - the per-plugin bucket and the held-press table (`01` §3.3);
  - releases sent on reload, stop, pause, failure and app exit.
- **`laser-show.events.handlers.plugins`**: `:plugins/refused` → the `:plugins/log` effect, and `:plugins/set-paused`.
- **`laser-show.events.core`**: the `:plugins/log` effect.
- **`laser-show.views.tabs.perform`** (existing, `show-lock-controls`, `:52-71`): NEW `plugins-switch` beside SHOW LOCK, `#perform-plugins-switch`. It is shown when at least one plugin is loaded, labelled "PLUGINS n" or "PLUGINS MUTED", and dispatches `:plugins/set-paused`.
- **`docs/PLUGINS.md`**: actions, the catalog, the rules (Stop and BLACKOUT win, audience, SHOW LOCK), and rate limits.

**Tests**
- `laser-show.plugins.catalog-test`:
  - `never-list-test` (`01` §3.4, invariant 3), which also checks `(empty? (set/intersection (set (keys catalog)) show-lock/safety-edits))`;
  - `every-entry-is-routed-test`: each type's namespace is routed by `events/handlers.clj:44-82`, and the type is a branch of its domain's `handle`. This reuses `dev/event_audit.clj:86-96`'s approach in a test;
  - `payloads-match-trigger-actions-test`: for each catalog type that `triggers/action-event` makes (`events/handlers/triggers.clj:106-155`), the trigger's keys ⊆ the entry's keys plus stamps.
- `laser-show.plugins.gate-test`:
  - `admit-table-test` (the gate rows of `01` §3.6);
  - `refuses-unknown-keys-test`;
  - `stamps-source-generation-and-input-time-test`;
  - `bucket-allows-burst-then-rate-test` (200 at t=0, then 1 per 10 ms).
- `laser-show.plugins.guard-test`, through `events/event-handler` (`events/core.clj:755-760`) with prepared states:
  - `g1-releases-always-pass-test` through `g9-audience-effect-cell-refused-test`, one test per row of `01` §3.5;
  - `non-plugin-events-unchanged-test`: the same events without `:plugin/source` behave as before, for every catalog type;
  - `stopped-show-refuses-light-test` (invariant 4).
- `laser-show.events.show-lock-test/plugin-refusal-goes-to-console-test`: with the lock on, `{:event/type :grid/clear-cell :col 0 :row 0 :plugin/source :machine/x :plugin/generation 1}` gives `:plugins/refused` and leaves `[:ui :notice]` unchanged. The existing show-lock tests stay as they are.
- `laser-show.plugins.runtime-test`:
  - `confirms-fixture-is-refused-test`
  - `unlocks-fixture-is-refused-test`
  - `floods-fixture-is-rate-limited-test` (200 queued, 100 refused, one console line)
  - `holds-fixture-is-released-on-reload-test` (`[:vibe-runtime :held :hit]` is cleared after a reload)
  - `paused-plugin-gets-no-hooks-and-sends-nothing-test`
  - `bar-flash-triggers-cell-test` (with a hand-run `show-clock/tick!`, as P3's clock isn't there yet; this test moves into P3)
- `laser-show.ui-test.perform-test` (existing): `plugins-switch-mutes-test` (NEW).
- `laser-show.plugins.event-load-test/four-plugins-at-limit-test` (tagged `:performance`): 4 plugins at 100 actions/s on empty-cell `:grid/trigger-cell`, which selects the cell (`events/handlers/grid.clj:59-79`). It records the p50 and p99 `events/flush!` latency against idle, in this plan.

**Done when:** the tests pass; the performance numbers are recorded and the rate limit is adjusted if p99 exceeds 50 ms; in the real app, `bar_flash.clj` with the clock driven by the REPL toggles `[0 0]`, and Stop silences it with "The show is stopped" in the console.

---

## P3 · Hooks, watches, timers (~2.5 d)

**Changes**
- **`laser-show.plugins.hooks`** (NEW): `derive [before after event-type]` (`01` §4.2), and `watch-changes [watches before-view after-view]`.
- **`laser-show.plugins.beats`** (NEW): `step [last tick]` (`01` §4.3), and `beat-timers [timers last tick]`.
- **`laser-show.plugins.sync`** (existing after P1): `wrap-plugin-sync` also adds `:plugins/notify {:type :before :after}` when `:state` changed and `[:plugins :order]` isn't empty.
- **`laser-show.events.core`**: the `:plugins/notify` effect calls `runtime/notify!`. It only offers to queues (invariant 6).
- **`laser-show.plugins.runtime`**:
  - `p/on`, `p/off`, `p/watch`, `p/every`, `p/after`, `p/cancel`;
  - `notify!`;
  - the `plugin-clock` thread on `show-time/frame-signal` (`animation/show_time.clj:454-462`), reading `show-time/latest` (`:396-399`) and `global-beats` (`:331-334`);
  - the router handler `::plugin-input` (`input/router.clj:63-70`), registered in `start!` and unregistered in `stop!`;
  - per-plugin queues with coalescing (`01` §4.6).
- **`docs/PLUGINS.md`**: hooks, payloads, watches, timers, and a note on latency.

**Tests**
- `laser-show.plugins.hooks-test`:
  - `cue-diff-table-test` (`01` §4.2);
  - `stop-fires-transport-not-cue-stopped-test`;
  - `show-loaded-on-open-and-new-test`;
  - `untouched-paths-cost-no-diff-test` (a `with-redefs` counter on the diff fns shows that identical roots skip them).
- `laser-show.plugins.beats-test`: `beat-table-test` (`01` §4.3), `beat-timers-test`.
- `laser-show.plugins.runtime-test`:
  - `bar-flash-triggers-cell-test` (moved from P2), driving `show-clock/tick!` by hand with `fixtures/with-clock` (`test/laser_show/ui_test/fixtures.clj:61-80`);
  - `watches-bpm-fixture-test` (`:timing/set-bpm 124.0` → console "120.0 → 124.0");
  - `on-input-fixture-taps-tempo-test` (`router/dispatch!` of `(input-events/note-on :midi 0 60 1.0)`, `input/events.clj:31-43`, → `:timing/tap-tempo` with `:plugin/source`);
  - `learn-only-keys-are-not-delivered-test`;
  - `spins-fixture-is-interrupted-and-stopped-test` (10 errors → `:stopped`);
  - `full-queue-coalesces-beats-test`;
  - `old-generation-hooks-dropped-test` (invariant 5);
  - `notify-never-blocks-test`: with a queue full, the notify effect returns in under 1 ms.
- `laser-show.plugins.latency-test/beat-hook-late-ms-test` (tagged `:performance`): with the real 60 Hz clock, it records `:late-ms` p50 and p99 and the time from `:bar` to the cue's first tick, in `00` §8.
- `laser-show.plugins.event-load-test/notify-cost-test` (`:performance`): 4 plugins × 64 watches, timing each `:plugins/notify` while `:show-clock/status` events stream in.

**Done when:** the tests pass and the latency is recorded; Q-5 is confirmed or the design revisited.

---

## P4 · Show scripts and trust (~2 d)

**Changes**
- **`laser-show.state.domains`**: NEW `(defstate scripts {:items []})`.
- **`laser-show.state.persistent`** (existing):
  - `zip-filenames` (`:41-45`) gains `:scripts "scripts.edn"`;
  - `persistent-state-mapping` (`:49-86`) gains `[:scripts]` `:owner :show`. A show without the entry loads `{:items []}` (`replace-at-path`, `:158-173`);
  - `reset-session` (`:193-222`) clears `[:plugins :allowed-hashes]`.
- **`laser-show.state.history`** (existing): `document-roots` (`state/history.clj:57-60`) and `extract-document-state` (`:62-79`) gain `:scripts`.
- **`laser-show.plugins.sync`**: `wanted` includes show scripts per `01` §8.
- **`laser-show.events.handlers.plugins`**:
  - `:plugins/copy-into-show`, `:plugins/source-read`, `:plugins/remove-show-script`, `:plugins/set-show-script-enabled`;
  - `:plugins/save-to-folder`, `:plugins/file-written`, `:plugins/file-write-failed`;
  - `:plugins/check-trust`, `:plugins/allow-show-scripts`.
- **`laser-show.events.handlers.history`**: descriptions for the undoable script events ("Copy Script into Show", "Remove Show Script", "Turn Show Script On/Off") in `event-descriptions` (`events/handlers/history.clj:148-245`).
- **`laser-show.events.show-lock`**: the three undoable script events join `blocked-events` (`events/show_lock.clj:32-93`) under a new "scripts" comment.
- **`laser-show.events.handlers.menu`** (existing): `handle-file-project-loaded` (`:114-135`) and `handle-file-new-project` (`:71-80`) add `:dispatch {:event/type :plugins/check-trust}`.
- **`laser-show.events.core`**: the `:plugins/read-source` and `:plugins/write-file` effects. Both run in futures; the second refuses to overwrite an existing file and offers `<id>-2.clj` instead.
- **`laser-show.views.components.plugin-settings`**:
  - a "This show's scripts" list: `#show-script-row-N` with `-state` ("Running", "Not allowed yet", …), `-allow`, `-enabled`, `-save-to-folder` and `-remove`;
  - **Copy into show** on machine rows.
- **`docs/PLUGINS.md`**: show scripts and trust.

**Tests**
- `laser-show.state.persistent-test`:
  - `scripts-round-trip-test` (save, then read through `project-files`, `:138-147`, and `zip-contents->file-data`, `:224-230`);
  - `old-show-without-scripts-loads-test`;
  - `open-clears-allowed-hashes-test`.
  - When cljfx-reactivity `02` lands, its unreadable-entry test gains a `scripts.edn` case.
- `laser-show.events.plugins-handlers-test`:
  - `check-trust-table-test` (the six rows of `01` §8);
  - `allow-always-writes-settings-test`;
  - `copy-into-show-trusts-its-hash-test`;
  - `undo-copy-unloads-script-test` (through `events/event-handler`, `:history/undo`);
  - `undo-restoring-unallowed-script-doesnt-run-it-test`;
  - `script-edits-refused-under-show-lock-test`.
- `laser-show.plugins.sync-test`:
  - `show-script-wanted-only-when-allowed-test` (invariant 9);
  - `machine-and-show-plugins-never-share-ctx-test` (invariant 8).
- `laser-show.ui-test.plugins-settings-test`:
  - `trust-prompt-on-open-test` (a fixture zip whose `scripts.edn` holds `bar_flash` with an untrusted hash → confirm dialog → **Run scripts** → the row says "Running");
  - `cancel-leaves-not-allowed-test`;
  - `copy-into-show-then-save-test`.

**Done when:** the tests pass, and a show saved on one machine and opened with a fresh `laser-show.plugins-dir` asks once and then runs its scripts.

---

## P5 · Editing actions and virtual controls (~2 d)

The scope depends on Q-4.

**Changes**
- **`laser-show.plugins.catalog`**: editing entries marked `:edit? true`. Proposed, from `events/handlers/history.clj:148-245`:
  - `:cue-chain/set-name`, `:grid/move-cell`, `:grid/clear-cell`, `:zones/set-label`, `:zone-maps/rename`;
  - **NEW** `:plugins/set-param {:cell [c r] :grid :effects|:cues :effect-id kw :param kw :value v :nth 0}`. It is plugin-friendly: its handler (in `events/handlers/plugins.clj`) finds the `nth` effect with `effect-id` in the cell's chain and returns `:dispatch` of `:chain/update-param` (`events/handlers/chain/core.clj:63-64`, `chain/params.clj:81-96`) with the `:effect-path` it found. Plugins don't need the index paths.

  The catalog allows `:plugins/set-param` as an exception to `01` §3.4's ban on the `:plugins` domain; `never-list-test` lists it as the one exception.
- **`laser-show.plugins.gate`**: adds `:history/coalesce-key [:plugin id]` for `:edit?` entries (PL-12).
- **`laser-show.plugins.runtime`**: `p/control!`, `p/press!`, `p/release!` (`01` §3.7). Presses are held and released on stop, like `:vibe/press`.
- **`laser-show.plugins.guard`**: also applies to events whose `:source-input` has `:source :plugin` (`events/handlers/triggers.clj:178-185`).
- **`docs/PLUGINS.md`**: editing, undo, virtual controls, and learning them.

**Tests**
- `laser-show.plugins.gate-test/edit-actions-coalesce-per-plugin-test`: 5 renames within 1 s make one undo step (`state/history.clj:384-409`).
- `laser-show.events.plugins-handlers-test/set-param-finds-effect-by-id-test`, and `set-param-unknown-effect-refused-test`.
- `laser-show.plugins.runtime-test`:
  - `virtual-control-reaches-store-test` (`[:plugin 0 :lfo]`, `input/store.clj:17-24`);
  - `virtual-press-runs-learned-binding-test`;
  - `virtual-press-obeys-guard-test` (a binding to `:trigger-cue` refused while stopped);
  - `virtual-press-is-learnable-test` (with `:input/start-learn`, `events/handlers/input.clj:285-316`).
- `laser-show.events.show-lock-test/plugin-edits-refused-under-lock-test`.

**Done when:** the tests pass, and `docs/PLUGINS.md` has a worked example of a plugin LFO learned as fader 2.

---

## After P5

Mark this plan Done and delete it (planning guide rule 11). Keep `docs/PLUGINS.md` as the reference.
