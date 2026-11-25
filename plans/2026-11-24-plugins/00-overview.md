# Plugins: user code that drives the app

**Written:** 2026-11-24   **Status:** Proposed   **Last verified against:** `7b320b3`
**Supersedes / Superseded by:** none
**Related findings:** review 02 M6 (`plans/2026-11-07-improvement-review/02-events-state.md:293-323`: domains as the schema, an optional schema per event; the plugin action catalog is that schema for the events plugins may send). Output drivers (`plans/2026-11-19-output-drivers/`) for the add-on folder and its platform rules. cljfx reactivity `02` (readable show files), `03` (Stop in force), `12` (audience indicators), `32` (explicit snapshot reads) (`plans/2026-11-23-cljfx-reactivity/`). See §7.

Paths are under `src/laser_show/` unless they start with `test/`, `test-resources/`, `plans/` or `docs/`. **NEW** marks anything that doesn't exist yet.

| Doc | What |
|---|---|
| `00-overview.md` | This doc: current behaviour, design, decisions, state, events, fit with other plans, risks, questions |
| `01-plugin-api.md` | **The contract**: the plugin file, the `laser-show.plugin` API, the action catalog and its two checks, hooks and their payloads, the state view, limits, data examples, invariants |
| `02-phases.md` | Phases P0–P5: changes per namespace, tests, how to check each is done |

## Start here

Your answers of 2026-11-24 set the scope. Plugins are **sandboxed Clojure (SCI)**. They live in **a per-machine folder and in shows**. They **send actions, read and watch state, and react to hooks**. They **reload live**. They **never get past the safety zones or SHOW LOCK**.

After this plan:

1. **A plugin is one `.clj` file.** Drop it into `~/LaserShowProjects/plugins/` (`%USERPROFILE%\LaserShowProjects\plugins\` on Windows) and it runs. Save the file and it reloads within about a second. Settings → Plugins lists each plugin with its state, its errors (with line numbers) and its console.
2. **Plugin code runs in SCI**, the Small Clojure Interpreter (`org.babashka/sci`, EPL-1.0, the same licence as Clojure). It has no file, network, thread or Java access. The only way to reach the app is the `laser-show.plugin` namespace (`01` §2). Each plugin has its own SCI context, so plugins can't see each other.
3. **Plugins send actions as event maps**, the same events the UI and the trigger bindings send (`{:event/type :grid/trigger-cell :col 0 :row 0}`). Each action passes two checks:
   - **On the plugin thread, the gate** (NEW `laser-show.plugins.gate`). The event must be in the **action catalog** (`01` §3), carry only the keys declared for it, and stay within the plugin's rate limit. The gate then stamps the event with its plugin.
   - **In the event pipeline, the guard** (NEW `wrap-plugin-guard`). Using the state at that moment, it refuses light while the show is stopped or in BLACKOUT, cues that can reach the audience, and anything from a paused or replaced plugin.

   SHOW LOCK and the Z1 safety stage then apply as they do to anyone. No catalog event can confirm a safety question, widen a zone, turn SHOW LOCK off, save, open or exit.
4. **Plugins read** a plain-data view of the show (`p/state`) and the show clock (`p/clock`). They **watch** paths and **hook** beats, bars, inputs, cues starting and stopping, vibe, tempo, transport, BLACKOUT and SHOW LOCK changes, and a show being loaded. They also have timers (`01` §4).
5. **Plugin code never runs on the event, FX, show-clock, engine or device threads.** It runs on one NEW `plugin-runner` thread, and every call has a time budget (`01` §6). A plugin that hangs or keeps failing is stopped; the show keeps playing.
6. **Shows can carry scripts.** **Copy into show** embeds a plugin's source in the show (a NEW `scripts.edn` entry in the zip). Opening a show from elsewhere asks before its scripts run, and a "remember" choice is kept per machine by the script's hash.
7. **A PLUGINS switch on the Perform tab** mutes every plugin at once: hooks stop and their actions are refused. Plugins stay loaded, so turning it back on is instant.

Build order: **P0** (SCI spike, sandbox and probes) → **P1** (folder, runner, Settings section, live reload, reads) → **P2** (actions: catalog, gate, guard, PLUGINS switch) → **P3** (hooks, watches, timers) → **P4** (show scripts and trust) → **P5** (editing actions and virtual controls). P3 and P4 both build on P2 and can be built side by side.

| Phase | What you get | Size |
|---|---|---|
| P0 | `org.babashka/sci` dependency; `laser-show.plugins.sandbox` (a context per plugin, the allowed namespaces, the interrupt budget, output to the console); sandbox probes; measured init, fork and eval times. Nothing visible | ~1 d |
| P1 | The plugins folder, discovery and manifest checks, the runner and supervisor threads, load, unload and live reload, `[:plugins]` state, the Settings → Plugins section with its console. API: `p/log`, `p/state`, `p/clock`, `p/plugin` | ~2.5 d |
| P2 | `p/dispatch!` for the performance events (`01` §3.3), the gate, the guard, show-lock refusals sent to the console, rate limits, and the PLUGINS switch | ~2.5 d |
| P3 | `p/on`, `p/watch`, `p/every`, `p/after`: hooks derived in the pipeline, the `plugin-clock` thread for beat and bar, input from the router | ~2.5 d |
| P4 | `scripts.edn` in shows, the `[:scripts]` document root, Copy into show and Save to folder, the trust prompt and the per-machine trust file | ~2 d |
| P5 | Editing actions (rename, param set, moves) with coalesced undo; virtual controls: `p/control!`, `p/press!`, `p/release!` | ~2 d |

## 1. Current behaviour (file:line)

**There is no plugin or scripting support.** `deps.edn:11-29` has no SCI and no scripting library. The only code loaded at runtime is output-driver add-on jars. They are compiled Clojure with no sandbox (`output/addons.clj:1-15`), loaded once at startup (`app.clj:227`), and can only add drivers. Users drive the app today in three ways:

- **The UI.** Every control dispatches an event map (`events/core.clj:811-835`).
- **Trigger bindings.** An input from MIDI, OSC, keyboard or gamepad goes into `router/dispatch!` (`input/router.clj:103-128`) on its device's thread. The global mapper (`events/handlers/input_mapper.clj:81-112`, registered at `:116-120`) turns it into `{:event/type :trigger/input}` on the event thread, or writes a CC to the fast store (`input/store.clj:10-15`). `triggers/handle-input` (`events/handlers/triggers.clj:187-197`) resolves the binding, and `run-action` (`:178-185`) dispatches the action's event with `:source-trigger` and `:source-input`.
- **The REPL**, for developers (`README.md`, "through the REPL"; `dev/user.clj:49-79`).

**The event pipeline** (`events/core.clj:645-653`) wraps `handlers/handle-event` (`events/handlers.clj:31-86`) in this order, innermost first: `show-lock/wrap-show-lock` (`events/show_lock.clj:162-173`), `wrap-normalize-timelines`, `wrap-history` (`events/handlers/history.clj:272-324`), `wrap-dirty` (`:369-393`), `output-sync/wrap-output-sync` (`events/handlers/output_sync.clj:25-34`), `rig-autosave/wrap-rig-autosave`, `wrap-lost-update-check`, then `with-fx-event`. One thread, `laser-show-events`, runs every event (`events/core.clj:764-790`). Only that thread may write state (`state/core.clj:108-128`, installed at `app.clj:238`).

**What an event can do to safety and the show lock.** These facts decide the gate's design:

| Fact | Where |
|---|---|
| Nothing records where an event came from. UI, input, the show clock (`services/show_clock.clj:304`) and the REPL all call `events/dispatch!` | `events/core.clj:811-835` |
| The show lock decides by `:event/type` alone (`blocked-events`, `pattern-edit?`, widening `safety-edits`) and tells the user with a status-bar notice | `events/show_lock.clj:32-93`, `:100-116`, `:120-124`, `:155-160` |
| `:vibe/set-show-lock` isn't refused, so an event can unlock the show | `events/handlers/vibes.clj:105-110`, `:223` |
| The Z1 questions read `:confirmed?` from the event, so an event with `:confirmed? true` skips them | `events/handlers/projector.clj:674`, `:703`, `:764`; `events/handlers/rig.clj:165`; `events/handlers/history.clj:92` |
| `:ui/confirm` continues whatever `:confirm-event` the confirm dialog holds, and `:ui/update-dialog-data` can write that dialog's keys | `events/handlers/ui.clj:84-96`, `:52-73` |
| `:dev/write-state` runs any function on the event thread | `events/handlers/dev.clj:22`; `events/core.clj:466-471` |
| `:file/exit` with `:force? true :rig-force? true` skips both unsaved-changes questions | `events/handlers/menu.clj:33-43`, `:206-215` |
| An event with `:source-trigger` adds no undo step | `events/handlers/history.clj:287` |
| An event with `:fx/sync` makes `dispatch` block until handled (cljfx 1.10.6 `wrap-async`) | — |
| Event maps have no schema. An unknown domain is logged and ignored (`events/handlers.clj:83-86`); most handlers ignore an unknown type silently | e.g. `events/handlers/grid.clj:232` |
| Triggering a cue while stopped starts playing: `activate-cue` sets `[:playback :playing?]` | `animation/cue_timing.clj:331-346`; `events/handlers/grid.clj:35-52` |
| BLACKOUT ignores the other vibe roles, but grid cues still play during it, by design | `vibes/engine.clj:469-488`, `:505`; `subs.clj:1724-1740` |
| Whether a cue or effect cell can reach the audience | `vibes/library.clj:132-145` (`audience-cue?`, `audience-cell?`), `:173` (`audience-fx?`) |

**Where hooks could come from today:**

- **Beat and bar.** No such event exists. The show clock ticks at 60 Hz on `show-clock` (`services/show_clock.clj:68`, `:316-331`). It publishes a snapshot and wakes waiters through `show-time/frame-signal` (`animation/show_time.clj:385-394`, `:454-462`). Beats come from `show-time/global-beats` (`:331-334`). Bars are 4 beats counted from global beat 0 (`routing/zone_maps.clj:113-115`). `:beat-index` reaches app state only inside `:show-clock/status`, at most every 250 ms (`services/show_clock.clj:176-203`).
- **Cues, vibe, tempo, transport, BLACKOUT, SHOW LOCK.** No "changed" events. The state paths are:
  - cues: `[:playback :active-cues]` (`state/domains.clj:74-82`)
  - vibe: `[:vibe-runtime :current]` (`vibes/engine.clj:238-245`)
  - tempo: `[:timing :bpm]` (`state/domains.clj:38-39`)
  - transport: `[:playback :playing?]`
  - BLACKOUT: `[:vibe-runtime :blackout?]`
  - SHOW LOCK: `[:ui :show-lock?]` (`events/show_lock.clj:141-144`)

  `wrap-output-sync` already compares state before and after each event (`events/handlers/output_sync.clj:25-34`), which is the pattern a hook needs.
- **Input.** `router/register-global-handler!` and `unregister-handler!` (`input/router.clj:63-79`) let anything subscribe, on the device's thread.
- **Show loaded.** `:file/project-loaded` (`events/handlers/menu.clj:114-135`) and `:file/new-project` (`:71-80`). Startup has no such event (`app.clj:228`).
- **Timers.** Only `:dispatch-later`, a future with sleep and no cancel (`events/core.clj:180-186`).

**Files and the UI:**

- **Nothing watches files.** Add-ons load once at startup. Their folder code is `addons-dir` (`output/addons.clj:50-58`, JVM property `laser-show.addons-dir`), `hidden-name?` (`:63-66`), `addon-file?` (`:68-74`) and `files-in` (`:83-86`).
- **A show zip has exactly three entries** (`state/persistent.clj:41-45`, mapping `:49-86`). `project-files` (`:138-147`) writes only those, so any other entry is lost on save.
- **Opening a show asks nothing about trust.** It only checks that the rig matches (`events/handlers/menu.clj:114-135`).
- **Settings is a tab** (`views/tabs/settings.clj:22-82`). It has an Add-ons section (`views/components/addon_settings.clj:68-138`, mounted at `views/tabs/settings.clj:78`).
- **The shared confirm dialog** (`views/dialogs/confirm.clj`; default at `state/domains.clj:197`) supports an optional `:choice` combo (`views/dialogs/confirm.clj:6-9`).
- **Logs go only to the console** (`resources/logback.xml`). There is no in-app log view.

## 2. Root cause

The app has one way in, the event map, and it trusts everything that comes through it. The UI is trusted because a person clicked; triggers are trusted because a person learned the binding. Safety is enforced partly by handlers (Z1's `:confirmed?`), partly by the show lock on event *types*, and partly by the people who confirm dialogs. None of them knows who sent an event. Code from outside the app would therefore get every power the REPL has: skipping the safety questions, unlocking the show, writing state, exiting. There is also no thread where outside code may run, nothing that turns state changes into callbacks, and nowhere in a show or on the machine to keep scripts.

## 3. Design in one page

```
 plugins/*.clj ─┐                       ┌─ p/state, p/clock (snapshots, read-only)
 show scripts ──┼─► supervisor ─► RUNNER (plugin-runner thread, one SCI ctx per plugin)
 (if allowed)   │   (poll 1 s,      │  ▲   p/dispatch! ─► GATE (catalog, keys, rate, stamp :plugin/source)
                │    watchdog,      │  │                    │
                │    status 250 ms) │  │                    ▼  events/dispatch!
                │                   │  │   ┌──────────────── event thread ──────────────────────────┐
                │                   │  │   │ handler ◄ show-lock ◄ PLUGIN GUARD ◄ … ◄ PLUGIN SYNC   │
                │                   │  │   │                                  (adds :plugins/sync,  │
                │                   │  │   │                                   :plugins/notify)     │
                │                   │  │   └────────────────────────────────────┬───────────────────┘
                │                   │  └──── hooks (queued, per plugin) ◄───────┘ :plugins/notify effect
                │                   │  ◄──── :beat / :bar ◄── plugin-clock thread (show-time/frame-signal)
                │                   └──◄──── :input ◄─────── router global handler (device threads)
```

- **The sandbox** (NEW `laser-show.plugins.sandbox`). One base SCI context is built once. Each plugin gets `sci/fork` of it (fork cost measured in P0). Plugins see only `clojure.core`, `clojure.string`, `clojure.set`, `clojure.walk`, `clojure.edn` and `laser-show.plugin`. They get no `:classes`, a `:load-fn` that returns nil, and `print`/`println`/`prn` that write to the plugin's console. Every call has a budget, enforced with SCI's `:interrupt-fn` and the interruptible core (`sci.interrupt/clojure-core`).
- **The runtime** (NEW `laser-show.plugins.runtime`) owns:
  - the registry atom: each plugin's ctx, generation, hooks, watches, timers, console, rate bucket and counters;
  - the **runner**: one `ScheduledThreadPoolExecutor` thread, `plugin-runner`, that runs loads, hooks and timers;
  - the **supervisor**: one scheduled thread, `plugin-supervisor`, that polls the folder every 1000 ms, watches the runner's call deadlines, and publishes `:plugins/status` at most every 250 ms when it changed (the `output-status` pattern, `backend/output_runtime.clj:338-351`);
  - the **clock thread**, `plugin-clock`, which waits on `show-time/frame-signal` like an engine and fires `:beat` and `:bar`.
- **The gate** (NEW `laser-show.plugins.gate`) is pure: `(admit catalog event plugin now-ms bucket)` gives back an admitted event or a refusal. The catalog (NEW `laser-show.plugins.catalog`) is data: event type → keys, value checks, `:lights?`, a doc string, and stamps such as `:input-time`.
- **The guard** (NEW `laser-show.plugins.guard/wrap-plugin-guard`) is pure middleware. It looks only at events that carry `:plugin/source`, or whose `:source-input` came from a plugin's virtual control (P5).
- **Plugin sync** (NEW `laser-show.plugins.sync/wrap-plugin-sync`) is pure middleware, like `wrap-output-sync`:
  - when `wanted` (what should run, from state) changes, it adds `:plugins/sync`;
  - when state changed and a plugin is loaded, it adds `:plugins/notify {:type :before :after}`.

  The `:plugins/notify` effect diffs the paths that hooks and watches need (with `identical?` first) and queues hooks. It never blocks.
- **Show scripts** live in a NEW `[:scripts]` document root, saved as NEW `scripts.edn` in the show zip. Trust is per machine, in NEW `plugin-settings.edn` in the plugins folder.

## 4. Decisions

| # | Decision | Why |
|---|---|---|
| PL-1 | The language is Clojure, interpreted by SCI (`org.babashka/sci`, newest 0.15.60 as of 2026-11-17). There are no compiled plugins and no Java interop | Your answer. Clojure matches the app. A sandbox makes sharing scripts and running them from shows safe. Live reload is just a re-eval. Jar add-ons stay for drivers (output-drivers OD-4) |
| PL-2 | A plugin is one `.clj` file. Its first form is an `ns` with an attr-map manifest (`01` §1), read with SCI's parser **without evaluating it** | One file to share, edit and embed in a show. The manifest is checked before any code runs, as add-on descriptors are (`output/addons.clj:135-146`) |
| PL-3 | Plugin ids: `:machine/<file-stem>` for the folder, `:show/<script-id>` for show scripts | The two sources can't collide, and the origin is visible in every log line |
| PL-4 | All plugin code runs on one `plugin-runner` thread, never on the event, FX, show-clock, engine or device threads | One thread means no concurrency inside SCI contexts and a clear order. The real-time threads stay untouched (`services/show_clock.clj:258-305` has a 16.7 ms budget; engines read no app state, `backend/multi_engine.clj:23-28`) |
| PL-5 | Actions are event maps from the **action catalog**, an allow-list with a key list per event. Unknown keys are refused, not stripped | An allow-list can't leak a new event by accident. Refusing unknown keys tells the author why, and it refuses `:confirmed?`, `:force?`, `:fx/sync`, `:source-trigger`, `:history/*`, `:state` and `:f` by construction |
| PL-6 | Two checks. The gate (shape, catalog, rate) runs on the plugin thread. The guard (state-dependent rules) runs in the pipeline when the event is handled | Only the event thread sees the state an event will change. A plugin's snapshot may be stale by the time the event runs (Stop pressed in between) |
| PL-7 | The gate adds `:plugin/source` and `:plugin/generation`. Plugins can't set either. Every middleware and handler that cares reads them | Gives the pipeline the origin it lacks (§2) without changing UI or trigger events |
| PL-8 | Stop and BLACKOUT win. While `[:playback :playing?]` is false or `[:vibe-runtime :blackout?]` is true, the guard refuses every `:lights?` action. A plugin may press BLACKOUT on but never off. `:transport/play` is not in the catalog | Your answer ("never bypass"), made concrete: plugins only act while a person has the show running, and making it dark always works |
| PL-9 | The guard refuses a cue (`audience-cell?`) or an effect cell (`audience-fx?`) that can reach the audience, whatever any vibe's **Allow audience cells** says | Audience scanning is a person's decision (README "Safety"). API 1 has no grant for it |
| PL-10 | Safety events, `:projectors/identify`, rig set-up, `:ui/*`, `:dev/*`, `:file/*`, `:system/*`, `:addons/*`, `:plugins/*`, `:history/*`, `:vibe/set-show-lock` and `:transport/play` are never in the catalog. A test enforces this (`01` §3.4) | §2's bypasses. SHOW LOCK stays a person's switch |
| PL-11 | SHOW LOCK applies unchanged. When it refuses a plugin's event, the text goes to that plugin's console, not the status bar | The lock stays the authority (`events/show_lock.clj:162-173`), and a plugin can't flood the operator with notices |
| PL-12 | Plugin edits enter undo like the user's, coalesced per plugin (`:history/coalesce-key [:plugin id]`, added by the gate, `events/handlers/history.clj:288`). Performance actions touch no document root, so they add no step | Undo stays honest about what changed the show, without one step per beat |
| PL-13 | Reads are a plain-data **state view** of fixed roots (`01` §5) plus `p/clock`. Plugins never get the context atom, device handles or undo stacks | `[:backend]` holds live devices and functions (`state/domains.clj:529-543`). The view is EDN, so it can't leak host objects into SCI |
| PL-14 | Hooks come from three places: the pipeline (`:plugins/notify`), `plugin-clock` (beat and bar), and a router global handler (input). Each only queues; none runs plugin code | Keeps PL-4. Each source already exists (§1) |
| PL-15 | Live reload polls modification time and size every 1000 ms, on the supervisor. A change is reloaded once it has been stable for 300 ms | Nothing watches files today (§1). The JDK `WatchService` polls on macOS anyway. Polling a few dozen files costs nothing and behaves the same on every OS (output-drivers OD-12) |
| PL-16 | Reload = stop (`:plugin/stop` hook within budget), drop the ctx and every hook, watch and timer, bump the generation, then load again. Queued work from the old generation is dropped, by the runner and by the guard | A reload can't leave callbacks behind |
| PL-17 | The PLUGINS switch (`[:plugins :paused?]`, session) mutes: no hooks are delivered and the guard refuses every action. It doesn't unload | A live operator needs "stop the robots" in one press, with nothing lost |
| PL-18 | Show scripts are copies. **Copy into show** puts a plugin's source in `[:scripts]`; **Save to folder** writes one out to edit. A show script runs only if its SHA-256 is trusted on this machine (`plugin-settings.edn`) or was allowed this session (`[:plugins :allowed-hashes]`). Opening a show with any other script asks first | Your answer "Both". Shows travel without a folder to match. Trust goes by hash, not by show, so an edit, an undo or a paste can never run a script nobody allowed |
| PL-19 | Machine plugins run when found unless disabled in Settings. The disabled list lives in `plugin-settings.edn` | The folder belongs to this machine's user, as add-ons do (output-drivers Q-7) |
| PL-20 | A hook that throws is logged with its line. 10 errors in 10 s stop the plugin ("Stopped after repeated errors"). A call over its budget is interrupted. A call stuck in host code (where the interrupt can't fire) for 5 s gets the runner replaced and the plugin stopped for the session | SCI's interrupt only fires in interpreted code (SCI `doc/interrupt.md`). The JVM can't kill a thread, so the stuck one is abandoned |

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:plugins]` NEW defstate (`state/domains.clj`, next to `backend` `:517`): `{:dir nil :seq 0 :list {} :order [] :paused? false :settings {:disabled #{} :trusted #{}} :allowed-hashes #{}}` | app state, not a document root, not saved | see the rows below | no/no |
| `[:plugins :dir]` | app state | `:plugins/started` (from `app/start!`) | no/no |
| `[:plugins :list id]` `{:id :name :version :origin :state :generation :error :counts :console :actions-refused}` (`01` §7) | app state | `:plugins/status` (from the supervisor, ≤ 4/s, only on change) | no/no |
| `[:plugins :order]` | app state | `:plugins/status` | no/no |
| `[:plugins :paused?]` | app state, session. New and Open keep it, like the show lock (`[:ui :show-lock?]`) | `:plugins/set-paused` | no/no |
| `[:plugins :settings]` (`:disabled` ids, `:trusted` hashes) | app state, mirrored from NEW `plugin-settings.edn` | `:plugins/started` (read at start), `:plugins/set-enabled`, `:plugins/allow-show-scripts`; each returns `:plugins/write-settings` | no/no |
| `[:plugins :allowed-hashes]` `#{"sha256:…"}`: the show scripts allowed this session | app state, session; cleared by `persistent/reset-session` (`state/persistent.clj:193-222`) on New and Open | `:plugins/allow-show-scripts` adds the hashes of the scripts the prompt listed | no/no |
| `[:scripts]` NEW document root `{:items [{:id "bar-flash" :name "Bar flash" :source "(ns …)" :enabled? true}]}` | document: show zip entry NEW `scripts.edn` (`:owner :show`). Joins `state/persistent.clj:41-45`, `:49-86` and `state/history.clj:57-60` | `:plugins/copy-into-show`, `:plugins/remove-show-script`, `:plugins/set-show-script-enabled` | yes/yes |
| `[:ui :dialogs :confirm]` (existing, `state/domains.clj:197`) | UI | `:plugins/check-trust` opens it with a `:choice` (`01` §8) | no/no |
| Plugin registry `!plugins` NEW | runtime atom in `laser-show.plugins.runtime` | the runner (loads and unloads); the supervisor (status); the gate's rate buckets | n/a |
| Runner, supervisor and clock threads NEW | runtime, in `laser-show.plugins.runtime` | `runtime/start!` and `runtime/stop!` from `app/start!` and `app/stop!` | n/a |
| Folder scan memory `{file {:mtime :size :hash :stable-since}}` NEW | runtime atom (supervisor) | the supervisor | n/a |
| Virtual control values (P5) `[:plugin id key]` | the fast input store (`input/store.clj:10-15`), as MIDI CCs are | `p/control!` on the runner, through `router/dispatch!` and the mapper (`events/handlers/input_mapper.clj:40-44`) | n/a |

Nothing is `swap!`ed from outside the event flow. Plugin status, settings and trust come in as events; the console reaches state only in `:plugins/status`.

Views: Settings → Plugins (NEW `views/components/plugin_settings.clj`) uses NEW subs `subs/plugin-rows`, `subs/plugins-dir`, `subs/show-script-rows`. The Perform tab's switch uses `subs/plugins-paused?` (NEW).

## 6. Events and effects

| Event / effect key | Exists? | Dispatched from (file:line) | Pure? |
|---|---|---|---|
| `"plugins"` domain → NEW `events/handlers/plugins.clj` `handle` | NEW route in `events/handlers.clj:44-82` | — | — |
| `:plugins/started {:dir :settings}` | NEW | `app/start!` after the dispatch fn is set (`app.clj:237`) and `:addons/loaded` (`:246`); `app/start-test!` with `:plugins-dir` (`:354-398`) | pure |
| `:plugins/status {:plugins {id status} :order}` | NEW | the supervisor | pure; ignores a status older than the one in state (by `:seq`) |
| `:plugins/reload {:id}`, `:plugins/reload-all` | NEW | Settings row and header | pure; returns `:plugins/command` |
| `:plugins/set-enabled {:id :enabled?}` | NEW | Settings row | pure; updates `[:plugins :settings :disabled]`; `:plugins/write-settings`; sync follows |
| `:plugins/set-paused {:paused?}` | NEW | Perform tab switch; Settings header | pure; sync follows |
| `:plugins/open-folder` | NEW | Settings header | pure; returns the existing `:system/open-folder {:path :on-failed}` (`events/core.clj:419-440`) |
| `:plugins/open-folder-failed {:path}` | NEW | `:system/open-folder`'s `:on-failed` | pure; `h/show-notice` (`events/helpers.clj:61-75`) |
| `:plugins/refused {:id :event-type :reason}` | NEW | the guard, and the show lock's refusal for plugin events (`events/show_lock.clj:155-160`) | pure; returns `:plugins/log` |
| `:plugins/copy-into-show {:id}` | NEW (P4) | Settings row of a machine plugin | pure; returns `:plugins/read-source {:id :file}` (the file from `[:plugins :list id :file]`), which reads it on a future and dispatches `:plugins/source-read` |
| `:plugins/source-read {:id :source :hash}` | NEW (P4) | the `:plugins/read-source` effect | pure; writes `[:scripts]` and adds the hash to `:trusted` (you copied it) |
| `:plugins/remove-show-script {:id}`, `:plugins/set-show-script-enabled {:id :enabled?}` | NEW (P4) | Settings show-script rows | pure; undoable |
| `:plugins/save-to-folder {:id}` | NEW (P4) | Settings show-script row | pure; returns `:plugins/write-file` |
| `:plugins/file-written`, `:plugins/file-write-failed` | NEW (P4) | `:plugins/write-file` | pure; notice |
| `:plugins/check-trust` | NEW (P4) | dispatched by `:file/project-loaded` (`events/handlers/menu.clj:114-135`) and `:file/new-project` (`:71-80`) via `:dispatch` | pure; if any enabled script's hash is in neither `:trusted` nor `:allowed-hashes`, opens the confirm dialog listing those scripts, with `:confirm-event {:event/type :plugins/allow-show-scripts :hashes #{…}}` |
| `:plugins/allow-show-scripts {:hashes :remember}` | NEW (P4) | the confirm dialog's `:confirm-event` through `:ui/confirm` (`events/handlers/ui.clj:84-96`), and Settings' **Allow…** (which opens the same prompt) | pure; adds `:hashes` to `:allowed-hashes`; with `:remember :always` also to `[:plugins :settings :trusted]`, returning `:plugins/write-settings` |
| Cancel in the trust prompt | exists (`:ui/close-dialog`, `views/dialogs/confirm.clj:80`) | — | Nothing is allowed. Settings shows those scripts as "Not allowed yet" with **Allow…** |
| `:plugins/sync` (effect) | NEW | `wrap-plugin-sync` when `sync/wanted` changes | effect: hands `{:paused? :disabled :show-scripts}` to the runtime (non-blocking) |
| `:plugins/notify` (effect) | NEW (P3) | `wrap-plugin-sync` when state changed and a plugin is loaded | effect: diffs and queues hooks (non-blocking; `01` §4.2) |
| `:plugins/command` (effect) | NEW | reload, reload-all | effect: queues on the runner |
| `:plugins/log` (effect) | NEW | `:plugins/refused` | effect: appends to the plugin's console ring |
| `:plugins/write-settings` (effect) | NEW | the handlers above | effect: writes `plugin-settings.edn` (temp file, then move, like `rig_store/write!`, `state/rig_store.clj:158-180`) on a future; failure → notice |
| `:plugins/read-source`, `:plugins/write-file` (effects) | NEW (P4) | the handlers above | effects on a future; results come back as events |
| Every catalog event (`01` §3.3) | exists | NEW `p/dispatch!` on `plugin-runner` → `gate/admit` → `events/dispatch!` (`events/core.clj:811`) | pure handlers, unchanged |
| `:trigger/input` for virtual controls (P5) | exists (`events/handlers/triggers.clj:187-197`) | `p/press!` → `router/dispatch!` (`input/router.clj:103`) → mapper (`events/handlers/input_mapper.clj:107-110`) | pure |
| `:file/project-loaded`, `:file/new-project` | exist; each gains a `:dispatch :plugins/check-trust` (P4) | `events/handlers/menu.clj:104-106`, `:71-80` | pure |
| Show lock refusal | exists (`events/show_lock.clj:155-160`); for an event with `:plugin/source` it returns `:dispatch :plugins/refused` instead of `h/show-notice` (P2) | — | pure |

## 7. Fit with other plans

| Plan | Overlap | How it fits |
|---|---|---|
| Output drivers (`plans/2026-11-19-output-drivers/`) | The add-ons folder and its rules: `addons-dir` (`output/addons.clj:50-58`), hidden and AppleDouble files (`:63-66`), `ensure-dir!` (`:419-428`), `:system/open-folder` (`events/core.clj:419-440`), and the test runner's folder isolation (`test/laser_show/test_runner.clj:188-197`) | P1 moves `hidden-name?` and `files-in` into NEW `laser-show.common.files`, used by both folders, so OD-12's platform rules exist once. The plugins folder sits next to `addons/`. Its JVM property is NEW `laser-show.plugins-dir`, isolated by the runner the same way. Add-on drivers and plugins never meet: plugins can't send `:addons/*` (PL-10) |
| cljfx reactivity `02` (readable show files) | It makes Open reject a present-but-unreadable entry and Save verify before replacing | `scripts.edn` is optional: a show without it loads with `{:items []}`. Once `02` lands, a present but unreadable `scripts.edn` rejects the Open like any other entry. P4 adds its round-trip to `02`'s tests (`02-phases.md` P4) |
| cljfx reactivity `03` (Stop in force) | A cue ending must not restart transport | PL-8 reads `[:playback :playing?]` when the event runs. With `03` in, Stop stays in force, so plugins can't sneak light back after it |
| cljfx reactivity `12` (audience indicators) | It shows which cells can reach the audience | PL-9 calls the same `vibes/library.clj` predicates, so what the guard refuses is what `12` marks |
| cljfx reactivity `32` (explicit snapshot reads) | It renames and documents the state read API (`state/core.clj:84-96`) | The state view (`01` §5) uses whatever `32` names the background snapshot read. P1 cites `get-raw-state` today |
| cljfx reactivity `24` (input learning) | One owner per Learn attempt | P5's virtual controls are learnable inputs (`:source :plugin`). `24`'s single listener catches them like any other source; nothing else changes |
| Review 02 M6 (schema for state and events) | — | The catalog is the first real per-event schema. It covers only plugin events; M6 can build on its key specs later |
| Live performance UX (`plans/2026-11-21-live-performance-ux/`) | The Perform tab header | The PLUGINS switch sits next to SHOW LOCK (`views/tabs/perform.clj:52-71`). P2 checks the layout against that plan's mockups |

## 8. Risks

- **Runaway plugin code.** `:interrupt-fn` with `sci.interrupt/clojure-core` stops interpreted loops and the core sequence functions, but not a long call in host code (SCI `doc/interrupt.md`). Since plugins have no `:classes`, the only host code they reach is `clojure.core` and our API. PL-20's runner replacement covers the rest; the abandoned thread may still burn CPU until it returns. P0's probes list what is bounded.
- **Memory.** SCI doesn't bound allocation, so `(vec (range 1e9))` can exhaust the heap (`-Xmx2048m`, `.github/workflows/release.yml:198-204`). The interruptible `range` and `repeat` (part of the interruptible core) stop it at the call budget, but a large `apply str` is host code. Mitigation: a 256 KiB source limit, and console and hook payloads are capped. Out-of-process plugins are the full answer; see Alternatives.
- **Event-thread load.** Every action is one event on the one event thread the UI shares. Initial limits are 100 actions/s sustained per plugin, burst 200, `:plugins/notify` with `identical?` first, and 64 watches per plugin. All of these are *unverified*: P2 and P3 measure `events/flush!` latency with 4 plugins at their limit (`02-phases.md`).
- **Beat hook latency.** `plugin-clock` sees a beat on the tick after it is crossed, at most 16.7 ms late at 60 Hz. The action then waits for the event thread and the next tick, so a cue started on `:bar` begins about 17–35 ms after the bar (*unverified*, P3 measures). Tight sync belongs in built-in modulators. The `:beat` payload carries `:late-ms` so plugins can see it.
- **The state view isn't a stable API.** It is the app's own state shape, which can change between versions. API 1 guarantees only the hook payloads, `p/clock` and the catalog (`01` §5).
- **Shows as a carrier.** A show's scripts can trigger cues within the rules (PL-8 to PL-11) once allowed. The prompt names every script and what scripts can do (`01` §8).
- **SCI on the uberjar.** SCI is pulled in through `b/compile-clj` (`build.clj:87-89`) like any dependency. P0 builds the uberjar and runs a plugin from it to make sure AOT doesn't break SCI.

## 9. Questions

| # | Question | Proposed answer |
|---|---|---|
| Q-1 | PL-8: may plugins only make light while a person has the show playing? Should Play stay out of the catalog? | Yes to both. A plugin that starts a show from timecode can come later as a separate grant |
| Q-2 | PL-9: refuse audience-reaching cues from plugins outright? | Yes in API 1 |
| Q-3 | PL-18: are show scripts copies (Copy into show / Save to folder), or should Settings have an in-app editor? | Copies. An editor is a later add-on to P4 |
| Q-4 | P5: which editing actions first? | `:chain/update-param` through a plugin-friendly `:plugins/set-param` (by cell and effect id), `:cue-chain/set-name`, `:grid/move-cell`, `:grid/clear-cell`, `:zones/set-label`, `:zone-maps/rename` (all in `events/handlers/history.clj:148-245`) |
| Q-5 | Is a beat hook about 1–2 ticks late acceptable? | Yes, for control logic. Revisit after P3's measurement |

## 10. Alternatives considered

- **Trusted jar plugins** (the add-on loader, `output/addons.clj:430-459`). Full speed and power, but no sandbox (so shows can't carry them), restart only (OD-4), and a build step for authors. Kept for drivers only.
- **An out-of-process API** (WebSocket, JSON or OSC) for any language. True isolation (CPU and memory), but every hook crosses a socket and authors run a second program. Possible later on top of the same catalog, gate and guard: the remote client would be one more plugin origin.
- **GraalJS or Python in-process.** A second language runtime in the uberjar and weaker sandbox guarantees on the JVM. SCI matches the app's language and data model.
- **Running hooks on the event thread.** It would remove a queue, but a slow plugin would freeze the UI and every input. Rejected by PL-4.
- **Plugins sending trigger actions** (`events/handlers/triggers.clj:106-155`) instead of events. A stable vocabulary, but too small for "drive any part" (no BPM, vibe select, params). P5's virtual controls give plugins this path as well.
- **Stripping forbidden keys instead of refusing them.** Silent, and a new dangerous key would get through until someone listed it. An allow-list of keys refuses it by default.
- **`WatchService` for live reload.** It is a poller on macOS and differs per OS, so PL-15 polls everywhere.
- **`add-watch` on the context atom** for hooks. It would see every write, but it lives outside the pipeline and is harder to test. The middleware-plus-effect pattern already exists (`events/handlers/output_sync.clj:25-34`).
- **A pre-beat hook** (firing X ms before a beat so the action lands on it). Cue starts aren't quantized (`routing/zone_maps.clj:121-133` covers only zone maps and pattern pads), so it wouldn't land exactly either. Left for a plan on quantized cue starts.
- **Folder plugins** (`plugin.edn` plus several namespaces). Better for big plugins, but they don't embed in a show as easily. Single files first; a folder form can be added under the same manifest keys.
