# Phase ZR: the rig in its own file

**Written:** 2026-11-19   **Written for commit:** `4fa9eee` (`master`). First written at `90e35c7`. Re-checked at `4fa9eee`, after vibe Phases 3 (`38f92ad`), 4a and 4b (`6715fb4`, `f5a4f84`), 5 (`9f5d78a`, fixed in `0faa18c`) and 6 (`4a4a10a`), and UX Phase 3 (`b9b9d55`). Of the files cited here, vibe Phase 6 moved lines only in `events/core.clj` and `state/domains.clj`. Every line below is at `4fa9eee`. Z0–Z4 aren't on `master` yet (Z0 is being built in a worktree), so the Z0, Z1 and Z2 names used here are the ones `02`, `03` and `04` propose. Vibe Phase 7, in progress in a worktree, edits two files ZR edits (§11). **Re-check at HEAD before starting.**
**Status:** Proposed. Not to be built yet (D-8).   **Needs:** Z2 (stable ids, `[:rig]`, the conversion of old hardware, `:zone-groups/show`), and through it Z0 (`routing.refs/replace-refs` and `cues-using`, the confirm dialog `views/dialogs/confirm.clj`, `:projectors/remove`). Best after Z4 (00 §4), when the show/rig split is final. Z1's `:safety` and Z3's `:fit` live inside each projector map, so they come along with no extra code. A show's copy of a safety zone is only ever read through Z1's `safety/normalize` (S-ZR-8).
**Implements:** D-30; spec §12 (the rig rows "after ZR: the rig file is saved automatically"); 00 §7 R-9.   **Size:** ~3–4 d

After this phase you can:

- **Start a new show and keep your rig.** New Project keeps the lasers, their order, safety zones, calibration, groups and middle-laser setting.
- **Open anyone's show safely.** Your safety zones and calibration never change. When the show expects groups or lasers this rig doesn't have, a short report says so and fixes each one in a click.
- **Stop saving the rig.** Rig changes save themselves within half a second. The show's `*` now means only the show changed.
- **Carry on from your old shows.** The first time you open one on a rig with no laser turned on yet, the app offers to set the rig up from it.
- **Never lose the rig to a broken file.** A file that can't be read is kept aside and the backup is used (or an empty rig), and the app says which.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| Where the rig lives | `state/persistent.clj:48-61` | inside the show zip: `hardware.edn` holds projectors, virtual projectors and groups (`:53-56`; Z2 adds `[:rig]`), and `content.edn` holds the calibration chains `[:chains :projector-effects]` (`:61`) |
| New Project | `:193-203`; `events/handlers/menu.clj:50-59` | `replace-document` with `{}` resets **every** saved path, so the projectors vanish (needs/08 P-10). The next scan brings them back disabled, with default calibration. While streaming, the engines stop (`test/laser_show/events/output_sync_test.clj:53`, `test/laser_show/events/menu_file_handlers_test.clj:264-267`). Then the starter cues, vibes and, since vibe Phase 5, the Master at `[0 3]` come in (`state/persistent.clj:199-201`) |
| Open | `:179-191` (`replace-document` `:145-149`, `replace-at-path` `:128-143`); `events/handlers/menu.clj:88-92` | replaces every saved path, rig included: the show's projectors, calibration (and, after Z1, safety zones) replace this machine's. The engines re-sync to them (`menu_file_handlers_test.clj:187-193`). Until ZR, Z1 shows a notice after Open saying so (D-34) |
| Save | `:108-117` (`project-files`); `events/handlers/menu.clj:105-117` | writes the three files, rig and show together (effect cells vibe mode switched on are written off, `vibe-switches-off`, `state/persistent.clj:94-106`) |
| Learned controls | `[:config :input]` (`state/domains.clj:266-267`): `:trigger-map` (cue cells' keys and the five vibe pads') and `:macro-map` (the two faders', vibe Phase 5) | saved with the show in `project-metadata.edn`, undoable content (`state/history.clj:17-18`). Open replaces them and New empties them (`test/laser_show/state/persistent_test.clj:306`), so after New the Master is at full with no fader learned |
| Undo and "unsaved" | `state/history.clj:14-22` (policy), `:48-50` (roots), `:153-173` | projectors and chains are undoable content, so a rig edit marks the show unsaved |
| Discovery | `events/handlers/projector.clj:159-186` | backfilled into history and the saved baseline (`:182-186`) |
| App-level files | `state/persistent.clj:20-22` | none. The only app folder is `default-projects-dir`, `~/LaserShowProjects`, where the file choosers start (`events/handlers/menu.clj:71-72`, `:139-140`) |
| Writing a file | `state/serialization.clj:141-166` | `save-to-file!` `spit`s straight into the target, so a crash mid-write leaves a broken file. `load-from-file` (`:168-198`) and `deserialize` (`:41-69`) return nil or a default on errors |
| Effects | `events/core.clj:170-191`, `:464-482`, `:491-539` | `:project/save` writes on the event agent, inline |
| Startup | `app.clj:124-187` | a fresh starter document (`:144-148`), then a scan (`:175-176`). `start-test!` (`:253-275`) mounts the app with a given state. `stop!` (`:221-242`) drains the output runtime |
| Leaving | `events/handlers/menu.clj:32-48`, `:175-182` | Exit asks first only when the show is dirty |

## 2. Why / root cause

The rig belongs to the machine and the venue: which lasers hang where, what they may light, how they're calibrated. The show is the art. Today one file holds both, so:

- New Project throws the rig away;
- opening a show from another venue overwrites the safety zones and calibration in front of you (T-8);
- every rig tweak marks the show unsaved.

D-30 splits them.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| The path specs of `persistent-state-mapping` (`state/persistent.clj:33-61`) and `replace-at-path` (`:128-143`) | each spec gains `:owner` (`:show` or `:rig`). The same code loads either part |
| `history/backfill-history` and `backfill-baseline` (`state/history.clj:281-307`), through a handler's `:history/backfill` | the startup load of the rig, and discovery (as today): never undone, never unsaved |
| `wrap-output-sync` (`events/handlers/output_sync.clj:25-34`) | the shape of NEW `wrap-rig-autosave`: compare roots by identity, add an effect |
| The output runtime's daemon threads (`backend/output_runtime.clj:224-264`) | the shape of the rig writer: one thread, the newest document wins, a `flush!` at exit |
| `serialization/serialize`, `deserialize` (`state/serialization.clj:25-69`) | the rig file's EDN. The write goes through a temp file and an atomic move, not `spit` (`:158-159`) |
| `show-notice` (`events/helpers.clj:109-123`) | the recovery and write-failure notices |
| The unsaved-changes guard and dialog (`events/handlers/menu.clj:23-48`; `views/dialogs/unsaved_confirm.clj`) | Exit after a failed rig write asks first, with its own text |
| Z2: `routing.rig/stable-id`, `match-discovered`, `effective-order`, `laser-ref`; `migration/zones-z2`; Z0's `routing.refs/replace-refs`; `:zone-groups/show` | matching a show's lasers and groups to this rig, renaming pins, converting an old show's hardware before an import |
| Z0's `refs/cues-using` | "used by 14 cues" and "3 cues are pinned to SR Beam" in the report |
| Z0's confirm dialog (`views/dialogs/confirm.clj`, `:ui/confirm`) | the questions for Use the show's… and Forget this laser… |
| Z0's `:projectors/remove`, as Z2 extends it (04 §4: the order entry and the pins go too) | Forget this laser… (S-Z0-4) |
| Z1's live-safety rule (D-28): `:projectors/set-safety`'s check, `safety/normalize` then `widens?` while the laser is outputting (03 §4) | "Use the show's setup for Laser L" goes through it: growing the area while the laser outputs asks first |

## 4. Changes per namespace

- `laser-show.state.persistent` (existing):
  - Each path spec gets `:owner`. `:show`: `[:config]` (the learned controls in `[:config :input]` with it, S-ZR-3), `[:timing :bpm]`, `[:grid :size]`, `[:zone-groups]`, `[:chains :cue-chains]`, `[:chains :effect-chains]` (and Z4's paths). `:rig`: `[:projectors]`, `[:virtual-projectors]`, `[:rig]`, `[:chains :projector-effects]`.
  - `replace-document` (`:145-149`) takes the owners to replace. `apply-loaded-project` (`:179-191`) and `new-project-state` (`:193-203`) replace `:show` paths only.
  - Z2's `migration/zones-z2` is split in two. Its **show rows** (the built-in groups and their references) run on every Open, judging "empty" by **the zip's** hardware (Z2 already passes it in). Its **rig rows** (Exclude from All, `:all` out of the lists, stable ids) run only on a show's hardware, just before a set-up or an import. Then `rig-file/rename-pins`, then `mark-clean`.
  - `new-project-state` seeds `[:zone-groups]` with the defaults plus the rig's copy of every group that has a laser (`rig-file/group-copy`), so the rig's groups come along. It still adds the starter cues, vibes and the Master (`:199-201`).
  - `project-files` (`:108-117`) is unchanged: Save still writes the rig into the zip as a copy (Q-ZR-2).
  - NEW `default-rig-file` beside `default-projects-dir`: `~/LaserShowProjects/rig.edn`, or the JVM property `laser-show.rig-file` when set (Q-ZR-1).
- NEW `laser-show.state.rig-file` (pure):
  - `rig-paths`; `rig-document [state]` (§8's shape); `apply-rig-document [state doc]`; `valid-rig-document? [doc]`.
  - `rig-changed? [old new]`: identity on the four rig paths, plus the definitions of the groups the rig's lasers are in.
  - `group-copy [state]`: the previous copy, overridden by the show's current definitions, kept to groups some laser lists.
  - `set-up? [state]`: some projector is enabled (Z2's `rig/enabled?`).
  - `rename-pins [state hardware]`: `:auto.laser/<show id>` → `:auto.laser/<rig id>` wherever the stable ids match (the zip's hardware gives show id → stable id; Z2's `stable-id` reads a pre-Z2 projector's `:unit-id`). Through Z0's `routing.refs/replace-refs`, so cues, tracks, clips and the preview cells follow.
  - `match-report [state hardware chains]`: §8's report data.
  - `set-up-from-show`, `import-new-lasers`, `use-show-setup`, `adopt-group`, `repin`, `add-groups-to-show`: each a pure state → state for one button (§6). The first three take a show's safety zone only through `safety/normalize` (S-ZR-8). Forget has no function here: it is Z0's remove (§6).
- NEW `laser-show.state.rig-store` (I/O at the edge):
  - `read! [path]` → `{:status :doc :notice}` (§7's table). An unreadable file is renamed to `rig-unreadable-<yyyy-MM-dd-HHmmss>.edn`, then `rig.edn.bak` is tried.
  - `write! [path doc]`:
    1. write `rig.edn.tmp` and force it to disk;
    2. copy the current `rig.edn` to `rig.edn.bak`;
    3. move the temp file onto `rig.edn` with `ATOMIC_MOVE` (plain `REPLACE_EXISTING` where the file system has no atomic move).

    It also writes a two-line `;;` header.
  - `lock! [path]` / `unlock!`: `FileChannel/tryLock` on `rig.edn.lock`, held for the session; the OS releases it if the app dies. An `OverlappingFileLockException` (the same JVM) counts as locked.
  - The writer: one daemon thread, `rig-writer`. `submit! [path doc on-result]` keeps only the newest document and writes it 500 ms after the last submit. `flush! [timeout-ms]` writes the pending one now.
- `laser-show.state.history` (existing):
  - `content-changed?` (`:153-159`) splits into `show-content-changed?` (grid, zone groups, config without prefs, cue and effect chains) and `rig-content-changed?` (projectors, virtual projectors, `[:rig]`, `[:chains :projector-effects]`).
  - `undoable-changed?` is either. `persisted-changed?` is `show-content-changed?` or prefs.
  - The policy table (`:14-22`) gains a "rig" row: undo yes, unsaved **no**.
- `laser-show.state.domains`: NEW runtime `(defstate rig-file …)` `{:path nil :status :none :read-only? false :written-at nil :error nil :group-copy {}}`. It isn't a document root, isn't saved with the show, and isn't reset by New or Open.
- NEW `laser-show.events.handlers.rig-autosave/wrap-rig-autosave`: after each event, when `rig-changed?`, a path is set and the rig isn't read only, it adds `:rig/write {:path :doc}` and stores the new `[:rig-file :group-copy]`. In `handle-event-pipeline` (`events/core.clj:464-482`) it goes beside `wrap-output-sync`, outside `wrap-dirty`, so it sees each event's final state, undo and redo included.
- `laser-show.events.core`: NEW effect `:rig/write` → `rig-store/submit!`, whose result comes back as `:rig/written` or `:rig/write-failed` through `dispatch`.
- `laser-show.events.handlers.rig` (Z2's, existing) gains the events in §6.
- `laser-show.events.handlers.history` (`event-descriptions`, `:125-163`): "Set Up Rig From Show", "Forget Laser" and a label for each report button. Each handler returns `{:state state}` for a bare event, since `history-labels-name-real-events-test` calls them that way.
- `laser-show.events.handlers.menu`:
  - `handle-file-project-loaded` (`:88-92`) runs `rig-file/match-report` after the load. With no laser turned on and lasers in the zip, it opens the set-up question; with a ⚠ row, the report.
  - It no longer returns **Z1's notice after Open** ("Safety zones and calibration come from <file>", D-34; 03 §4), because from ZR on, Open never replaces safety or calibration. The report covers what is different instead.
  - `guard-needed?` (`:32-36`) is also true for Exit while `[:rig-file :status]` is `:write-failed`.
- `laser-show.app`:
  - `start!` (`:124-187`): after building the starter state, it locks and reads the rig (`rig-store/lock!`, `read!`) and applies it before `init-state!`; `[:rig-file :path]` is set. After mounting, it dispatches `:rig/startup-notice` when there is a notice, then scans (`:175-176`), so the scan matches against the loaded rig.
  - `stop!` (`:221-242`): `rig-store/flush! 2000`, then `unlock!`.
  - `start-test!` (`:253-275`) reads no rig file unless the given state names one in `[:rig-file :path]`, so UI tests never touch `~/LaserShowProjects`.
- Views:
  - NEW `views/dialogs/rig_report.clj` (the set-up question and the report), registered in `views/root.clj` like the other dialogs;
  - the Projectors tab header gains the rig's status line and the read-only banner;
  - a disabled card's menu (Z2's, beside Identify) gains "Forget this laser…" (Z0's remove, through `:rig/forget-projector`).

## 5. State placement

| Path | Lives in | Written by | Undo? | Unsaved (show)? |
|---|---|---|---|---|
| `[:projectors]`, `[:virtual-projectors]`, `[:rig]`, `[:chains :projector-effects]` | **the rig file**; a copy in the zip on each Save | rig edits; the report's buttons; discovery (backfill); the startup load (backfill) | rig edits: yes. Discovery and startup: no | **no**: saved automatically |
| `[:zone-groups]` (definitions) | the show; the rig file keeps a copy of those with lasers | the Zone Groups tab; the report's Add to this show | yes | yes |
| `[:rig-file :group-copy]` | runtime, written into the rig file | the startup load; `wrap-rig-autosave` | no | no |
| `[:rig-file :path :status :read-only? :written-at :error]` | runtime | startup; `:rig/written`; `:rig/write-failed` | no | no |
| `[:ui :dialogs :rig-report]` `{:open? :mode (:set-up \| :report) :file :report :hardware :chains}` | UI | Open; the Projectors tab link | no | no |
| `[:config]`, with the learned controls `[:config :input :trigger-map]` and `:macro-map` | the show, as today (S-ZR-3) | as today | yes | yes |
| zone maps, the active map, targets (Z4) | the show | – | as Z4 says | as Z4 says |

Undo stays one history (Q-ZR-5). Undoing a card move moves it back, and the file follows. Z1's check on Undo and Redo (Q-Z1-4) still asks before an undo grows a live laser's area. History is still cleared by New and Open (`reset-session`, `state/persistent.clj:151-169`), so nothing after an Open can undo back into the previous rig.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:file/new-project` | yes, **changed**: keeps the rig and brings its groups | File > New | yes |
| `:file/project-loaded` | yes, **changed**: keeps the rig, renames pins, then the report or the set-up question | `:project/load` | yes |
| `:file/exit` | yes, **changed**: asks first after a failed rig write | File > Exit; closing the window | yes |
| `:rig/startup-notice {:text :level}` | **NEW** | `app/start!` after mounting | yes (`show-notice`) |
| effect `:rig/write {:path :doc}` | **NEW** | `wrap-rig-autosave` | effect (the rig-writer thread) |
| `:rig/written {:at}` | **NEW** | the rig writer | yes |
| `:rig/write-failed {:error}` | **NEW** | the rig writer | yes (a notice; status `:write-failed`) |
| `:rig/retry-write` | **NEW** | "Retry" on the status line | yes (+ `:rig/write`) |
| `:rig/set-up-from-show` | **NEW** | Set up rig | yes, one undo step "Set Up Rig From Show". Each safety zone goes through `safety/normalize` (S-ZR-8) |
| `:rig/import-new-lasers` | **NEW** | the report | yes, undoable. Safety as for the set-up |
| `:rig/adopt-group {:group-id :from}` | **NEW** | the report: use the rig's same-named group | yes, undoable (rig) |
| `:rig/repin {:from :to}` | **NEW** | the report: Pin them to…, Send them to All | yes, undoable (show, unsaved) |
| `:rig/add-groups-to-show {:group-ids}` | **NEW** | the report | yes, undoable (show, unsaved) |
| `:rig/use-show-setup {:projector-id :confirmed?}` | **NEW** | the report, after its confirm (Z0's dialog) | yes, undoable (rig). Z1's check, as `:projectors/set-safety` does it (03 §4): the show's zone through `safety/normalize`, refused if it can't be read; then, while the laser outputs and the zone widens, Z1's "Allow light in a larger area?", which re-sends the event with `:confirmed? true` |
| `:rig/open-report` | **NEW** | the Projectors tab link | yes |
| `:rig/request-forget {:projector-id}` | **NEW** | a disabled card's menu | yes: opens Z0's confirm with §7's text (and Z2's choice for pinned cues, 04 S-13) |
| `:rig/forget-projector {:projector-id}` | **NEW**, a thin wrapper that runs Z0's `:projectors/remove` (02 §4: projector, chain, list state, virtual projectors, selection, Z1's Identify; from Z2 also the order entry and the laser's pins, 04 §4). The rig file follows through `wrap-rig-autosave`, as for any rig edit | the confirm's button (`:ui/confirm`) | yes, undoable "Forget Laser" |
| `:zone-groups/show` | Z2 | the report: Choose lasers… | yes |
| `:ui/close-dialog :rig-report` | yes | Not now; Close | yes |

## 7. UX

### Saving, without asking

The Projectors tab header has one line about the rig file, with the path in its tooltip ("~/LaserShowProjects/rig.edn"):

| State | Line |
|---|---|
| ok | "Rig saved 14:02" (muted) |
| a write pending | "Saving rig…" |
| another window has it | (amber banner) "This rig is open in another Laser Show window. Changes here aren't saved." |
| a newer file | (amber banner) "This rig file comes from a newer version of Laser Show. It is used, but not saved." |
| a write failed | (red) "Rig not saved: <reason>" [Retry] |

### Starting up

| The file | What happens | Notice |
|---|---|---|
| missing (never written, or deleted on purpose) | an empty rig; the scan fills it. A leftover `rig.edn.bak` is not used: deleting the file is how you start over | none |
| readable | it | none |
| unreadable or the wrong shape | kept aside as `rig-unreadable-2026-11-19-140312.edn`; the backup if it reads, else an empty rig | "Your rig file couldn't be read, so its backup from 19 Nov 14:02 is in use. The broken file was kept as rig-unreadable-2026-11-19-140312.edn." / "Your rig file couldn't be read. It was kept as rig-unreadable-2026-11-19-140312.edn, and the rig starts empty." |
| newer version | read only | the banner above |
| locked by another window | read only | the banner above |

### Opening a show on a rig with no laser turned on

```
┌ Set up your rig from friday.zip? ─────────────────────────────────┐
│ No laser on this rig is turned on yet. friday.zip was made with 4: │
│   Laser L · Laser C · Laser R · Audience                           │
│ Their order, groups, safety zones and calibration become your rig. │
│ Lasers found on this network keep their addresses.                 │
│ Matched by IP only, check them: Audience (10.0.0.12)               │
│                                        [Not now]  [Set up rig]     │
└────────────────────────────────────────────────────────────────────┘
```

**Set up rig** copies each of the show's lasers onto the rig:
- a laser already on the rig (same stable id, for example found by the startup scan) keeps its id and its address, and takes everything else from the show;
- a laser that isn't on the rig is added, and shows "Not found in the last scan" until a scan finds it.

The order and the middle-laser setting come from the show. No laser is outputting yet (none is on), so Z1's confirm doesn't come up: a laser the set-up turns on starts with the show's zone, as a laser you turn on starts with its own. Each zone comes in through `safety/normalize` (S-ZR-8). One it can't read is kept as it is, so that laser stays dark ("Safety error: output off", Z1's fail-closed rule) until you set its zone, and the question names it: "Audience's safety zone in friday.zip can't be read: it stays dark until you set one." **Not now** leaves the rig as it is; the next show opened on it asks again.

### The report

It opens after an Open only when a ⚠ row exists. "What doesn't fit (2)" in the Projectors tab header reopens it while the show is open.

```
┌ friday.zip on this rig ───────────────────────────────────────────────┐
│ Your rig's safety zones and calibration are kept.                      │
│                                                                        │
│ ⚠ Graphics (used by 14 cues) has no laser on this rig.                │
│     [Use this rig's "Graphics" (2 lasers)]   [Choose lasers…]          │
│ ⚠ 3 cues are pinned to SR Beam, which isn't on this rig.              │
│     [Pin them to… ▾]   [Send them to All]                              │
│ ℹ friday.zip was made on 6 lasers; this rig has 4. Left, Right, Odd   │
│   and Even follow this rig.                                            │
│ ℹ This rig's groups Beams and Crowd 2 aren't in this show.            │
│     [Add them to this show]                                            │
│ ℹ The show has its own safety and calibration for Laser L. This rig's │
│   are in use.  [Use the show's for Laser L…]                           │
│                                    [Import 2 new lasers…]   [Close]    │
└────────────────────────────────────────────────────────────────────────┘
```

- **A group with no laser here.** "Use this rig's …" shows only when a rig group has the same name; it adds the show's group to those lasers (`:rig/adopt-group`). "Choose lasers…" opens the group in the Zone Groups tab (`:zone-groups/show`).
- **A missing pinned laser.** "Pin them to…" lists this rig's lasers in order. "Send them to All" re-targets to `:all`. Both are show edits: undoable, unsaved.
- **Use the show's for Laser L…** asks first: "Replace Laser L's safety zone and calibration with the show's? This changes where Laser L can draw." [Replace] [Cancel]. While Laser L outputs and the change grows its area, Z1's "Allow light in a larger area?" comes too. A show's zone that `safety/normalize` can't read isn't offered: the row says "The show's safety zone for Laser L can't be read."
- **Import 2 new lasers…** adds the show's lasers whose stable id isn't on the rig, with their setup, their safety zones as for the set-up. It never changes a laser that's already here.

### New Project, Exit, Forget

- **New Project** says nothing new. The rig stays, and the rig's groups that have lasers appear in the new show. The starter cues, vibes and Master come as today, and the learned controls start empty, as today (S-ZR-3).
- **Exit after a failed write:** "Your last rig changes couldn't be saved: <reason>. They include safety zones and calibration. [Try again] [Quit anyway] [Cancel]"
- **Forget** (a disabled card's menu): "Forget 10.0.0.9 #1? Its calibration and safety zone leave the rig. A scan that finds it again adds it as a new laser." [Forget] [Cancel]. With cues pinned to it, Z2's "Send its cues to" choice comes in the same dialog (04 S-13). Z0's Remove… in the inspector does the same with Z0's words.

## 8. Data examples and invariants

**The rig file**, `~/LaserShowProjects/rig.edn`:

```clojure
;; Laser Show rig: your projectors, their order, safety zones and calibration.
;; Saved automatically. Delete this file to start with an empty rig.
{:rig-file/version 1
 :projectors {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :port 7255 :service-id 1
                                     :unit-id "0A 1B 2C 3D 4E 5F 60 71 82 93 A4 B5 C6 D7 E8 F9"
                                     :stable-id [:unit "0A 1B 2C 3D 4E 5F 60 71 82 93 A4 B5 C6 D7 E8 F9" 1]
                                     :enabled? true :scan-rate 30000
                                     :output-config {:color-bit-depth 8 :xy-bit-depth 16}
                                     :zone-groups [:graphics] :tags #{} :exclude-from-all? false
                                     :safety {:limit [-0.9 -0.8 0.9 0.9] :blocked []}     ; Z1
                                     :fit {:min-fps 20 :reduce-points? true}}}            ; Z3
 :virtual-projectors {}
 :rig {:order [:projector-10-0-0-5-1 :projector-10-0-0-6-1 :projector-10-0-0-7-1] :middle :split :split-width 0.75}
 :chains {:projector-effects {:projector-10-0-0-5-1 {:items [{:effect-id :rgb-curves …} {:effect-id :corner-pin …}]}}}
 :zone-groups {:graphics {:id :graphics :name "Graphics" :color "#9B59B6"}}}   ; the copy: groups with lasers
```

**The report** for §7's example:

```clojure
(rig-file/match-report state show-hardware show-chains)
;; => {:file "friday.zip" :show-lasers 6 :rig-lasers 4
;;     :groups-without-lasers [{:group-id :graphics :name "Graphics" :cues 14
;;                              :same-name {:group-id :group-77 :lasers 2}}]
;;     :missing-pins [{:ref :auto.laser/projector-10-0-0-9-1 :name "SR Beam" :cues 3}]
;;     :rig-groups-not-in-show [{:group-id :group-81 :name "Beams" :lasers 3}
;;                              {:group-id :group-90 :name "Crowd 2" :lasers 1}]
;;     :setup-differs [{:projector-id :projector-10-0-0-5-1 :name "Laser L" :what #{:safety :calibration}}]
;;     :matched-by-ip []
;;     :new-lasers [:projector-10-0-0-9-1 :projector-10-0-0-10-1]}
```

**A pin follows its laser.** The show says `:auto.laser/projector-10-0-0-5-1`, and its `hardware.edn` gives that projector the stable id `[:unit "0A 1B …" 1]`. On this rig that unit is `:projector-10-0-0-57-1` (it was found at .57). The load renames the pin to `:auto.laser/projector-10-0-0-57-1` before `mark-clean`, so the show isn't unsaved. Saving it writes this rig's ids and this rig's copy, so the next Open on either rig renames again.

**Invariants**

1. Opening a show never changes a rig path. Only the set-up question and the report's buttons do, and only when pressed. So Z1's invariant 9 loses its Open exception, and its D-34 notice goes.
2. New Project leaves every rig path `identical?`, so the engines don't restart.
3. The rig file follows the rig: within 0.5 s of the last change, and before the app exits.
4. A write never leaves a half-written `rig.edn`. The previous good file is `rig.edn.bak`.
5. The app always starts, whatever the rig file holds, and says when it used the backup or an empty rig.
6. Only one window writes the rig file.
7. Rig edits are undoable and never mark the show unsaved. Show edits mark it as before.
8. A show saved after ZR opens in a build from before ZR exactly as it did, because the zip still carries the rig.
9. Pins and preview cells that name a laser follow the unit, by stable id, from rig to rig.
10. Discovery's new lasers and moves reach the rig file without an undo step.
11. A safety change from a show (the set-up, an import or one laser's setup) is always a choice the user made, and on a live laser it follows Z1's rule (D-28). A show's zone is read only through `safety/normalize`, so it never widens on the way in, and one that can't be read leaves its laser dark, never at the full area (S-ZR-8).

## 9. Tests

Fixtures to reuse:
- `test/laser_show/state/persistent_test.clj:21-35` (a `test-output/` file and its clean-up), copied for a `test-output/rig/` folder;
- `test/laser_show/events/menu_file_handlers_test.clj:28-54` (`document-state`, `with-runtime`, `assert-runtime-kept`) and `:145` (`zip-contents-of`);
- `test/laser_show/events/output_sync_test.clj:17-34` (`streaming-state`, `run`);
- `test/laser_show/state/history_test.clj:8-34` (`sample-state`);
- Z2's migration fixtures (old `hardware.edn` maps).

Unit tests:

- `laser-show.state.rig-store-test` (NEW, temp files):
  - `missing-file-is-an-empty-rig-test`, `round-trip-test`, `header-comment-is-skipped-test`;
  - `unreadable-file-is-kept-aside-and-the-backup-used-test`, `unreadable-file-without-backup-starts-empty-test`, `deleted-file-starts-empty-even-with-a-backup-test`, `newer-version-is-read-only-test`;
  - `write-is-atomic-test` (a failing write leaves the old file and its backup), `second-lock-is-refused-test`;
  - `writer-keeps-only-the-newest-test`, `flush-writes-the-pending-document-test`.
- `laser-show.state.rig-file-test` (NEW, pure):
  - `rig-document-holds-only-rig-paths-test`, `group-copy-keeps-groups-with-lasers-test`, `rig-changed-ignores-show-edits-test`, `rig-changed-sees-a-renamed-member-group-test`;
  - `rename-pins-by-stable-id-test` (pre-Z2 hardware with `:unit-id` only, too);
  - `report-lists-groups-without-lasers-test`, `report-offers-the-same-named-rig-group-test`, `report-lists-missing-pins-test`, `report-is-empty-on-the-same-rig-test`, `report-names-ip-only-matches-test`;
  - `set-up-keeps-rig-ids-and-addresses-test`, `set-up-converts-old-hardware-first-test`, `import-adds-only-new-lasers-test`, `use-show-setup-changes-one-laser-test`, `adopt-group-adds-the-show-group-test`;
  - `show-safety-is-normalized-on-the-way-in-test` (unordered corners and integers come in as `normalize` gives them), `unreadable-show-safety-stays-dark-test` (set-up and import keep it as it is, so `[:safety id]` fails closed; Use the show's… refuses it).
- `laser-show.state.persistent-test` (existing):
  - NEW `open-keeps-the-rig-test`, `open-never-changes-safety-or-calibration-test`, `new-project-keeps-the-rig-test`, `new-project-brings-the-rigs-groups-test`, `save-still-writes-the-rig-copy-test`, `old-built-ins-are-judged-by-the-shows-own-hardware-test`;
  - `test-load-project` (`:149-187`) and `test-round-trip` (`:189-209`) now expect the rig untouched by the load, and the zip to still hold the projectors. `new-project-has-the-master-test` (`:301-307`) passes unchanged.
- `laser-show.state.history-test` (existing): `rig-edit-is-undoable-but-not-unsaved-test`, `show-edit-is-still-unsaved-test`, `projector-effects-count-as-rig-test`.
- `laser-show.events.rig-autosave-test` (NEW, through `handle-event-pipeline`):
  - `rig-edit-requests-a-write-test`, `show-edit-requests-no-write-test`, `undo-of-a-rig-edit-requests-a-write-test`, `discovery-requests-a-write-test`, `member-group-rename-requests-a-write-test`;
  - `read-only-rig-requests-no-write-test`, `no-path-requests-no-write-test`;
  - `write-failure-shows-a-notice-and-retries-test`.
- `laser-show.events.rig-handlers-test` (Z2's): `forget-is-z0s-remove-test` (the same state as `:projectors/remove`, one undo step "Forget Laser", and a write request), `use-show-setup-asks-before-widening-a-live-laser-test`.
- `laser-show.events.menu-file-handlers-test` (existing):
  - `project-loaded-replaces-document-test` (`:156-193`): the projectors stay the rig's, and no refresh while streaming;
  - `new-project-resets-document-only-test` (`:243-267`): the projectors stay, no refresh; the Master is still the only effect cell;
  - Z1's `open-says-where-safety-comes-from-test` goes, and `new-and-open-take-the-notice-away-test` (`:269-280`) expects no notice after Open again;
  - NEW `open-asks-to-set-up-an-empty-rig-test`, `open-shows-the-report-only-with-a-warning-test`, `exit-after-a-failed-rig-write-asks-first-test`.
- `laser-show.events.output-sync-test` (existing): the "new project" row (`:53`) now expects no refresh; NEW `open-keeps-the-engines-test`.

UI tests:

- `laser-show.ui-test.rig-report-test` (NEW; `with-toolkit` once, `with-app` each, each with its own `[:rig-file :path]` under `test-output/`):
  - `open-on-an-empty-rig-asks-to-set-it-up-test`;
  - `report-choose-lasers-opens-the-group-test`, `report-pin-to-repins-the-cues-test`;
  - `read-only-rig-shows-a-banner-test`, `rig-status-line-says-saved-test`.

Run, targeted first:
1. `clj -M:mac-arm:test -n laser-show.state.rig-store-test -n laser-show.state.rig-file-test -n laser-show.state.persistent-test -n laser-show.state.history-test -n laser-show.events.rig-autosave-test -n laser-show.events.rig-handlers-test -n laser-show.events.menu-file-handlers-test -n laser-show.events.output-sync-test -n laser-show.events.projector-handlers-test -n laser-show.events.history-handlers-test`
2. `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.rig-report-test`, then `-n laser-show.ui-test.rig-strip-test -n laser-show.ui-test.main-window-test -n laser-show.ui-test.setup-dialogs-test`
3. The full suites: `clj -M:mac-arm:test`, then `clj -M:test-ui:mac-arm:test-ui-headless`.

## 10. Commit plan

Each step is one commit: the named tests first (run them and record them red), then the code, then the step's tests and the touched namespaces.

1. `refactor(state): every saved path says whether it belongs to the show or the rig; nothing changes yet`. Tests: the persistence tests unchanged, plus `rig-document-holds-only-rig-paths-test`.
2. `feat(rig): the rig file, ~/LaserShowProjects/rig.edn: written atomically with a backup, one window at a time, and a file that can't be read is kept aside instead of stopping the app`. Only `rig-store` and its tests; nothing calls it yet.
3. `feat(rig): rig changes save themselves within half a second and at exit, and no longer mark the show unsaved`. The middleware, the writer, the history split, `app` start and stop, the status line. Tests: `rig-autosave-test`, the history tests, `rig-status-line-says-saved-test`.
4. `feat(rig): New Project keeps the rig and its groups, and opening a show never touches the rig's safety zones or calibration; lasers pinned in a show follow the same units here`. Z1's notice after Open goes in the same commit. Tests: the persistence and menu tests above (Z1's two notice tests as changed), `output-sync-test`. From here the zip's rig is only a copy.
5. `feat(rig): the first show opened on a rig with no laser turned on offers to set the rig up from it`. Tests: `set-up-*`, `show-safety-is-normalized-on-the-way-in-test`, `unreadable-show-safety-stays-dark-test`, `open-asks-to-set-up-an-empty-rig-test`, the UI test.
6. `feat(rig): after opening a show, a report says which of its groups have no laser here and which pinned lasers are missing, with a fix for each`. Tests: `report-*`, `adopt-group-*`, `use-show-setup-*`, the UI tests.
7. `feat(rig): forget a laser that's gone for good, now that New Project no longer clears the rig`. Tests: `forget-is-z0s-remove-test`.
8. `plans: ZR done (<sha>)`: as built, deviations, tests, red-first results and verification, written back into this doc.

## 11. Out of scope / alternatives considered

- **Named venue profiles and a Rig menu** (Save rig as…, Open rig…, recent venues; needs/08 R-21): later. ZR keeps one rig file per machine (00 §8). Copying `rig.edn` to another machine or a USB stick works today, because the stable ids belong to the lasers, not the machine.
- **An "authored on" summary in the show and slots to fill on arrival** (R-22, Q-R13): later. The zip's rig copy already gives the report what it needs.
- **Daily copies of the rig** (`rig-backups/rig-2026-11-19.edn`, keeping 7): considered. They would undo a mistaken "Set up rig" days later, but `.bak` and undo cover the session, so later.
- **Reloading the rig when another window writes it**: rejected for now (Q-ZR-4 C). Two windows editing one rig is rare, and a read-only second window is simple and safe.
- **A separate undo history for the rig**: rejected (Q-ZR-5 C). Two Undo commands confuse more than they help.
- **The network settings** (`[:projector-ui :broadcast-address]`, `state/domains.clj:339-340`): they belong to the machine, but nothing edits them yet (R-18). The rig file has room for them.
- **Controls that travel with the machine.** The learned controls (`[:config :input :trigger-map]` for the cue cells and the five vibe pads, `:macro-map` for the two faders) and the MIDI and OSC settings (`[:config :midi]`, `[:config :osc]`, saved prefs) are machine-like, but D-30 doesn't list them, and vibe Phase 3 left a controller profile out too (`plans/2026-11-18-vibe-mode/03-phase3-setup-and-grouping.md:186`, in git history at `8b4b7d7`). ZR v1 does nothing with them: they stay show data (S-ZR-3), so after ZR New Project keeps the rig but still starts with no key, pad or fader learned, and Open brings the show's. A profile file beside `rig.edn`, on the same writer, is the later step.
- **An ignore list for other people's lasers** (R-18): later. "Forget" handles a laser that's gone; an ignored one would come back with the next scan.
- **Writing on the event thread**, as `:project/save` does (`events/core.clj:170-181`): rejected. A pretty-printed rig with calibration curves can take tens of milliseconds, too long for a slider drag.
- **Vibe Phase 6** (`4a4a10a`, landed: auto-advance and one tap tempo) changes nothing ZR uses. Its new settings, `[:config :vibes :settings :rotation]`, are show data like the rest of `[:config :vibes]`, and the AUTO switch and the tap tempo are session state.
- **Merge risk: vibe Phase 7** (safety and hardening, in progress in a worktree). It wraps `handlers/handle-event` in its show lock, innermost in the same `->` chain where ZR adds `wrap-rig-autosave` (`events/core.clj:464-482`), and it edits `state/domains.clj`, where ZR adds the `rig-file` domain. Its lock blocks `:file/new-project` and `:file/open`, whose handlers ZR changes, and the events that re-route the engines (its §7), so whichever lands second adds ZR's rig-changing events (`:rig/set-up-from-show`, `:rig/import-new-lasers`, `:rig/use-show-setup`, `:rig/adopt-group`, `:rig/forget-projector`) to its `blocked-events`. `rig-changed?` then sees nothing while the lock is on, and the file follows as usual after it.

## 12. Spec questions and open questions

### Spec questions (what this doc chose)

- **S-ZR-1 · A show's group with no laser on this rig** routes nowhere, which §12 allows: membership is the rig's, definitions are the show's. **Chosen:** say so in the report, and never assign lasers by itself.
- **S-ZR-2 · Undo after ZR.** §12 says "Undo? yes" with automatic saving. **Chosen:** one history, as today. Rig edits stop marking the show unsaved.
- **S-ZR-3 · What D-30 doesn't list.** **Chosen:** virtual projectors go in the rig (they hang off physical projectors). `[:config]` stays with the show: the window, the preview cells, the IDN defaults, the MIDI and OSC settings, and the learned controls in `[:config :input]` (the cue cells' and the vibe pads' keys, and since vibe Phase 5 the faders' `:macro-map`). ZR v1 doesn't move the controls, though they belong to the performer's machine as much as the rig does (§11). The network settings wait for R-18.
- **S-ZR-4 · "Never overwrites safety" and the first import.** **Chosen:** a show's safety and calibration reach the rig only by an explicit choice: the set-up question on a rig with no laser turned on, or one laser's "Use the show's…" with its confirm.
- **S-ZR-5 · Pins across rigs.** `:auto.laser/<id>` names an id, and ids differ from machine to machine. **Chosen:** the load renames pins by stable id (the zip's rig copy gives id → stable id). A pin to a unit that isn't here is a report row.
- **S-ZR-6 · Group ids across shows.** Two shows' "Graphics" groups have different ids unless they kept the default `:graphics`. **Chosen:** match by id. A same-named rig group is offered in the report, never merged by itself.
- **S-ZR-7 · Old built-ins judged on which rig?** §11 decides "empty built-in Left" by its members. **Chosen:** the members in the show's own `hardware.edn` (where it was authored), not this rig's.
- **S-ZR-8 · A show's safety zone on the way in.** The spec (§10) and Z1 cover edits on this machine; a zone read from a zip is another way in, and a hand-edited or older file may not be normalized, or may not be readable at all. **Chosen:** every way in (the set-up, an import, Use the show's…) reads the show's `:safety` through Z1's `safety/normalize`, as `:projectors/set-safety` does, and stores what it gives. One it can't read is never replaced by the full area: the set-up and an import copy it as it is, so the stage fails closed and that laser stays dark until its zone is set (Z1 invariant 6), and the question names it; Use the show's… refuses it, as `set-safety` would. On a live laser, Use the show's… then follows D-28 (Z1's `widens?` and confirm).

### Open questions for the user (★ = recommendation)

**Q-ZR-1 · Where the rig file lives.**
- **A ★** `~/LaserShowProjects/rig.edn`, beside your shows. It is the only folder the app already owns (`state/persistent.clj:20-22`), it is easy to find, back up and copy, and the Open dialog only lists `.zip` files, so it never gets in the way.
- **B** The system's app-data folder (`~/Library/Application Support/…`, `%APPDATA%`). That's conventional, but hidden.
- **C** Next to the app itself. It breaks when the app is updated or read-only.

**Q-ZR-2 · Does a saved show still carry a copy of the rig?**
- **A ★** Yes, as today (`hardware.edn` and the chains). An older build opens it unchanged, and this build reads it only to offer set-up and to write the report.
- **B** No. The show is smaller, but older builds and other machines lose the setup.

**Q-ZR-3 · The first show opened on a rig with no laser turned on.**
- **A ★** Ask: Set up rig / Not now. It asks again on the next Open, until a laser is turned on.
- **B** Set it up silently, with a notice. That's closest to today, but a stranger's show could fill your rig.
- **C** Never import. Every machine starts its rig from a scan.

**Q-ZR-4 · Two Laser Show windows at once.**
- **A ★** The second one uses the rig read only, with a banner.
- **B** The last one to write wins.
- **C** Each reloads when the file changes.

**Q-ZR-5 · Undo for rig edits.**
- **A ★** Undoable, in the one history, and not "unsaved" (saved automatically).
- **B** Not undoable, like discovery.
- **C** A separate rig history.

**Q-ZR-6 · Forgetting a laser** (New Project no longer clears old lasers, and every scan on every network adds disabled ones). Z0 already gives every laser Remove… in the inspector (02 §4), so this is only about a disabled card's menu item and the rig's words for it.
- **A ★** In ZR: "Forget this laser…" on a disabled card (Z0's remove).
- **B** Wait for discovery hygiene (needs/08 R-18), with Remove… as the only way until then.
