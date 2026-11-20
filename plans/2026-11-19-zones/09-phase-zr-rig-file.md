# Phase ZR: the rig in its own file

**Written:** 2026-11-19   **Written for commit:** `4fa9eee` (`master`). First written at `90e35c7`. Re-checked at `4fa9eee`, after vibe Phases 3 (`38f92ad`), 4a and 4b (`6715fb4`, `f5a4f84`), 5 (`9f5d78a`, fixed in `0faa18c`) and 6 (`4a4a10a`), and UX Phase 3 (`b9b9d55`). Of the files cited here, vibe Phase 6 moved lines only in `events/core.clj` and `state/domains.clj`. Every line below is at `4fa9eee`. Z0–Z4 aren't on `master` yet (Z0 is being built in a worktree), so the Z0, Z1 and Z2 names used here are the ones `02`, `03` and `04` propose. Vibe Phase 7, in progress in a worktree, edits two files ZR edits (§11). **Re-check at HEAD before starting.**
**Status:** Built on branch `zones-zr`, not merged (`987c05b`..`f46c7d3`, as built in §13). Built off `6bedf25` while Z3 was built in its own worktree.   **Needs:** Z2 (stable ids, `[:rig]`, the conversion of old hardware, `:zone-groups/show`), and through it Z0 (`routing.refs/replace-refs` and `cues-using`, the confirm dialog `views/dialogs/confirm.clj`, `:projectors/remove`). Best after Z4 (00 §4), when the show/rig split is final. Z1's `:safety` and Z3's `:fit` live inside each projector map, so they come along with no extra code. A show's copy of a safety zone is only ever read through Z1's `safety/normalize` (S-ZR-8).
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

#### Raised while building (2026-11-20; the build follows ★)

**Q-ZR-7 · Which of a show's lasers the set-up and Import bring.** A show's `hardware.edn` holds every output its venue's scans found, the ones that were off too.
- **A ★ (built)** Only the lasers the show had on ("made with 4"). The question lists those, and the report counts those.
- **B** All of them, the ones that were off coming in off. Nothing is lost, but another venue's spare outputs fill the strip with "Not found in the last scan" cards.

**Q-ZR-8 · An imported laser's On box.**
- **A ★ (built)** As the show had it, "with their setup" (§7). While streaming, its engine starts at the show's address, which on this network may be another device.
- **B** Off, as a scan finds a new laser, until you tick On on its card.

**Q-ZR-9 · A pin to a laser this rig doesn't have, after Save.** Save writes this rig's copy into the zip (Q-ZR-2), so the laser a cue is pinned to, not being here, isn't in the saved `hardware.edn` any more. Opened on another rig, the pin can then only be matched by its id, no longer by its unit.
- **A** Save also keeps the opened show's entries for the lasers its cues are pinned to that aren't on this rig, so the pin finds its unit again on the next rig.
- **B ★ (built)** As is: the report says the pin is missing, here and on the next rig, and Pin them to… fixes it.

**Q-ZR-10 · The show lock and Add them to this show.** The lock refuses every report button that changes the rig or re-routes cues (§13). Add them to this show only adds group definitions, which re-route nothing.
- **A ★ (built)** Allowed while locked.
- **B** Refused, as every other report button is.

## 13. As built (2026-11-20, `987c05b`..`f46c7d3`, on branch `zones-zr`)

Built in worktree `zones-zr` off `6bedf25` (Z0, Z1, Z2 and vibe Phase 7 on `master`), while Z3 was built in its own worktree. Not merged. §1's lines were re-checked at `6bedf25`, where Z1 and Z2 had moved them; the code below is described as built.

### Where it landed

| # | Commit | Step (§10) |
|---|---|---|
| 1 | `987c05b` | each path spec of `persistent-state-mapping` gets `:owner` (`:show` or `:rig`); `persistent/owned-paths`; `replace-document` takes the owners to replace. NEW `state/rig_file.clj`: `version`, `rig-paths`, `group-copy`, `rig-document`, `valid-rig-document?`, `newer?`, `apply-rig-document` |
| 2 | `ddd35a5` | NEW `state/rig_store.clj`: `read!` (`:missing :ok :recovered :reset :newer`), `write!`, `try-lock`/`release!`, `lock!`/`unlock!`, the `rig-writer` thread (`submit!`, `flush!`, `write-delay-ms` 500), `install-shutdown-hook!`. `persistent/default-rig-file`: the JVM property `laser-show.rig-file`, else `~/LaserShowProjects/rig.edn`. The test runner points the property at a temp folder |
| 3 | `42ca8c6` | the history split (`show-content-changed?`, `rig-content-changed?`; the policy table's rig row); `(defstate rig-file …)`; `rig-file/rig-changed?`; NEW `events/handlers/rig_autosave.clj` (`wrap-rig-autosave`, `write-request`, `writable?`) beside the output sync; the `:rig/write` effect; `:rig/written`, `:rig/write-failed`, `:rig/retry-write`, `:rig/startup-notice`; `app/start!` (`load-rig`: lock, read, apply, the rig's groups joined into the starter show), `stop!` (flush, unlock), `start-test!` (a rig file only when the state names one); NEW `views/components/rig_file_status.clj`, one row in the Projectors tab |
| 4 | `74eaaf2` | Open and New replace the `:show` paths only; `migration/zones-z2-show` (on Open, judged by the zip's hardware) and `zones-z2-rig` (before a set-up or an import); `rig-file/pin-map`, `rename-pins` before `mark-clean`; New brings `group-copy`; Z1's D-34 notice after Open goes |
| 5 | `993e195` | `rig-file/set-up?`, `set-up-question`, `set-up-from-show`; `:rig/set-up-from-show`; Open keeps `[:ui :dialogs :rig-report]` and opens the question on a rig with no laser on; NEW `views/dialogs/rig_report.clj` (the question), mounted in `views/root.clj` under the shared confirm |
| 6 | `67882a8` | `rig-file/match-report`, `warning-count`, `import-new-lasers`, `use-show-setup`, `adopt-group`, `add-groups-to-show`, `repin`; `:rig/open-report`, `:rig/choose-lasers`, `:rig/adopt-group`, `:rig/repin`, `:rig/add-groups-to-show`, `:rig/import-new-lasers`, `:rig/request-use-show-setup`, `:rig/use-show-setup`; the report view; What doesn't fit (n) (`#rig-report-link`) |
| + | `5e6942e` | Exit after a failed rig write asks first (§4's `guard-needed?` item, which step 3 left out): the unsaved-changes dialog with `:reason :rig`, Try again (`:file/confirm-retry`, `#unsaved-retry`), Quit anyway, Cancel |
| 7 | `f46c7d3` | `:rig/request-forget`, `:rig/forget-projector` (dispatches Z0's `:projectors/remove` with `:history/description "Forget Laser"`); Forget this laser… on a disabled card's menu (`#rig-card-forget-<id>`) |
| 8 | (this commit) | as built |

### Deviations from the plan

- **The path seam is the JVM property `laser-show.rig-file`,** read at each call (`default-rig-file` is a function, as `dev-config` reads `laser-show.dev`). `laser-show.test-runner/-main` sets it to a fresh temp folder unless it is set, for every test alias. `start-test!` reads and writes no rig file unless the state names one in `[:rig-file :path]` (§4); the UI tests that need one give each test its own temp folder. `rig-store-test/default-rig-file-test` checks that under the runner the default isn't in the home folder.
- **`rig-paths` is a literal in `rig-file`:** `persistent` requires `rig-file` (`group-copy`, `rename-pins`), so `rig-file` can't read the mapping. `rig-document-holds-only-rig-paths-test` checks that it and the mapping's `:owner :rig` paths agree, and that every saved path has an owner.
- **`rig-changed?` compares a rig path by value when it isn't identical:** a scan that finds nothing new still rebuilds `[:projectors]`, and would have written the file each scan.
- **`[:rig-file]` gained `:requested` and `:written`,** a number per write request, so the line says "Saving rig…" exactly while a write is pending. The statuses are the read's (`:missing :ok :recovered :reset :newer`), `:locked`, then `:saved` or `:write-failed`. `:rig/written` and `:rig/write-failed` carry the path, and one for another path (a test or a window before) is ignored.
- **Copy not in §7:** "Rig saves automatically" while nothing has been written yet; "Everything in friday.zip fits this rig." for a report with no rows; singular forms ("1 cue is pinned to SR Beam, …", "Pin it to…", "This rig's group Beams isn't in this show. [Add it to this show]"); a line names what differs ("The show has its own safety zone for Laser L. This rig's is in use."); the Exit question's title is "Rig not saved"; Use the show's… asks "Replace Laser L's safety zone with the show's?" when only the zone differs.
- **A recovered rig is written back at once** (`:rig/retry-write` after mounting): otherwise rig.edn, kept aside, stays missing, and the next start begins empty, as a deleted file does.
- **`write!` backs up only a rig.edn that reads,** so a broken file never replaces the good backup.
- **`lock!` never stops the app:** a lock file that can't be made (no folder, no permission) leaves this window writing, and a write that fails says why. A second lock in the same process is refused from a registry, without opening the lock file again: on POSIX, closing a second channel would let go of the first lock. A read-only window never moves an unreadable file aside, and its notice leaves out "kept as …".
- **A shutdown hook flushes the pending rig** (`rig-store/install-shutdown-hook!`), beside `stop!`'s flush.
- **"The show's lasers" are the projectors it had on** (Q-ZR-7): the set-up and Import bring those, and the question's list and the report's counts use those.
- **Pins:** `pin-map` gives each show laser its id here; one that isn't here keeps its id unless a laser here has that id, then takes a free one (`…-2`), so its pins reach nothing here rather than another laser. The set-up and Import also rename a pin whose laser got another id between the Open and the button (a scan in between): the dialog keeps the Open's mapping (`:pins`), and after a set-up or Import the mapping applied.
- **The report is worked out live** (`rig-report/report`, a sub over the dialog's `:hardware` and `:chains`), not stored at Open, so a row goes once it is fixed. `[:ui :dialogs :rig-report]` is `{:open? :mode :file :hardware :chains :pins}`; `:same-name` carries the rig group's `:name`.
- **Events beyond §6:** `:rig/request-use-show-setup` (Use the show's… opens the Replace? confirm), `:rig/choose-lasers` (closes the report, then Z2's `:zone-groups/show`), `:file/confirm-retry`.
- **Use the show's… changes the zone and the calibration only;** a show laser with no zone or chain gives none. The set-up also brings the show's virtual projectors on the lasers it brings, and asks Z1's question when a laser turned on since the question came up would get a larger area.
- **Exit's rig question is the unsaved-changes dialog with its own text,** and landed as its own commit (`5e6942e`) between steps 6 and 7. With unsaved show changes too, the show's question comes first, then the rig's.
- **Forget's notice, when cues were pinned, is Z0's** ("Removed 10.0.0.9 #1 · 2 cues now go to All").
- **The show lock** (vibe Phase 7) refuses `:rig/set-up-from-show`, `:rig/import-new-lasers`, `:rig/request-use-show-setup`, `:rig/use-show-setup`, `:rig/adopt-group`, `:rig/repin`, `:rig/request-forget` and `:rig/forget-projector`; it lets through `:rig/add-groups-to-show` (definitions only, Q-ZR-10), `:rig/open-report`, `:rig/choose-lasers` and the rig file's own events.
- **`migration/zones-z2` stays** for its tests; Open runs `zones-z2-show`, still only for a file with no `:rig`.
- **Tests changed:** Z2's `reorder-is-undoable-and-unsaved-test` is `reorder-is-undoable-and-saves-itself-test`; `projector-handlers-test/handle-projectors-add-effect-test` expects calibration to be undoable but not unsaved; `history-test/rig-is-undoable-content-test` expects no unsaved change; `persistent-test`'s `test-load-project`, `test-round-trip`, `rig-is-saved-with-the-hardware-test`, `old-show-loads-clean-test` and `safety-round-trips-test` expect Open to leave the rig and the zip to hold the copy; `output-sync-test`'s "new project" row is now `open-keeps-the-engines-test`; `menu-file-handlers-test`'s `project-loaded-replaces-document-test`, `new-project-resets-document-only-test` and `clean-project-skips-the-guard-test` expect the projectors kept and no refresh; Z1's `open-says-where-safety-comes-from-test` is gone and `new-and-open-take-the-notice-away-test` expects no notice after Open; `show-lock-test`'s starter state holds an opened show, a laser in Graphics and a cue pinned to a laser that isn't here, for one real edit of each newly blocked kind.

### Tests added

- `laser-show.state.rig-file-test` (NEW, 19): `rig-document-holds-only-rig-paths-test`, `group-copy-keeps-groups-with-lasers-test`, `rig-changed-ignores-show-edits-test`, `rig-changed-sees-a-renamed-member-group-test`, `rename-pins-by-stable-id-test`, `set-up-keeps-rig-ids-and-addresses-test`, `set-up-converts-old-hardware-first-test`, `show-safety-is-normalized-on-the-way-in-test`, `unreadable-show-safety-stays-dark-test`, `set-up-question-test`, `report-lists-groups-without-lasers-test`, `report-offers-the-same-named-rig-group-test`, `report-lists-missing-pins-test`, `report-is-empty-on-the-same-rig-test`, `report-names-ip-only-matches-test`, `report-says-where-setup-differs-test`, `import-adds-only-new-lasers-test`, `use-show-setup-changes-one-laser-test`, `adopt-group-adds-the-show-group-test`.
- `laser-show.state.rig-store-test` (NEW, 12, each in its own temp folder): §9's eleven and `default-rig-file-test`.
- `laser-show.events.rig-autosave-test` (NEW, 9): §9's eight and `startup-notice-is-shown-test`.
- `laser-show.views.components.rig-file-status-test` (NEW): `status-line-text-test`, `path-text-test`.
- `laser-show.state.history-test`: `rig-edit-is-undoable-but-not-unsaved-test`, `show-edit-is-still-unsaved-test`, `projector-effects-count-as-rig-test`.
- `laser-show.state.persistent-test`: `open-keeps-the-rig-test`, `open-never-changes-safety-or-calibration-test`, `new-project-keeps-the-rig-test`, `new-project-brings-the-rigs-groups-test`, `save-still-writes-the-rig-copy-test`, `old-built-ins-are-judged-by-the-shows-own-hardware-test`.
- `laser-show.events.rig-handlers-test`: `set-up-is-one-undo-step-test`, `set-up-asks-before-widening-a-live-laser-test`, `use-show-setup-asks-before-widening-a-live-laser-test`, `report-buttons-test`, `forget-is-z0s-remove-test`.
- `laser-show.events.menu-file-handlers-test`: `open-asks-to-set-up-an-empty-rig-test`, `open-shows-the-report-only-with-a-warning-test`, `exit-after-a-failed-rig-write-asks-first-test`.
- `laser-show.events.output-sync-test`: `open-keeps-the-engines-test`.
- UI, `laser-show.ui-test.rig-report-test` (NEW, 7, each with its own temp folder): `rig-status-line-says-saved-test`, `read-only-rig-shows-a-banner-test`, `open-on-an-empty-rig-asks-to-set-it-up-test`, `report-choose-lasers-opens-the-group-test`, `report-pin-to-repins-the-cues-test`, `closing-after-a-failed-rig-write-asks-test`, `forget-a-laser-from-its-card-test`.

### Red first

| Step | Before the change |
|---|---|
| 1 | `rig-file-test` couldn't load: no namespace |
| 2 | against stubs of the API: 12 tests, 31 failures, 5 errors (`default-rig-file-test` failed because nothing set the property yet) |
| 3 | against stubs: 15 tests, 61 failures. The two UI tests, against the tab without the line: 2 errors |
| 4 | 16 tests, 41 failures |
| 5 | 8 tests, 37 failures; the UI test: 1 error |
| 6 | 13 tests, 64 failures; the two UI tests: 2 errors |
| + | `exit-after-a-failed-rig-write-asks-first-test`: 5 failures, 1 error. Its UI test was written after the code and run against the old menu and dialog: 1 error |
| 7 | `forget-is-z0s-remove-test`: 12 failures; the UI test: 1 error |

Green on their first run, **not run red**: `rig-changed-ignores-show-edits-test` and `show-edit-requests-no-write-test` (vacuous against the stubs: nothing asked for a write yet), `show-edit-is-still-unsaved-test` (today's behaviour, kept) and `save-still-writes-the-rig-copy-test` (Save already wrote the copy). The step-3 fix that joins the rig's groups into the saved baseline at startup was checked red by taking it out: 1 failure in `rig-status-line-says-saved-test`.

### Verification

| Where | Unit suite (`clj -M:mac-arm:test`) | UI suite (`clj -M:test-ui:mac-arm:test-ui-headless`) |
|---|---|---|
| `6bedf25` (the base: `8c30108`'s code, 04 §13) | 1,306 tests, 463,804 assertions, 0 failures | 173 tests, 806 assertions, 0 failures |
| `f46c7d3` (step 7) | **1,365 tests, 464,242 assertions, 0 failures, 0 errors** | **180 tests, 837 assertions, 0 failures, 0 errors** |

Each step also ran its own namespaces and §9's run list, and from step 3 on the events, state, views, subs, vibes and routing namespaces (about 800 tests), and the UI namespaces it touches. `~/LaserShowProjects` doesn't exist on this machine, before or after both full suites: no test, probe or test start created anything under the home folder.

### For the merge

Trial merges with `git merge-tree` (no branch, no worktree), on 2026-11-20:

- **Into `master` at `cd10149`** (it gained `0002052`, Q-Z1-5 A, in `events/show_lock.clj` and `show_lock_test.clj`): clean. The merged tree's `show-lock-test`, `rig-handlers-test`, `rig-autosave-test`, `history-handlers-test`, `menu-file-handlers-test`, `rig-file-test` and `persistent-test` pass (146 tests). Q-Z1-5's larger-zone check and ZR's blocked events don't meet: every ZR event that can change a zone is blocked outright.
- **With `zones-z3` at `65362f6`** (Z3 in progress): clean. In the merged tree the events, state, routing, backend, views and subs namespaces pass but one: Z3's `projector-handlers-test/set-fit-clamps-and-is-undoable-test` expects `:projectors/set-fit` to be "an unsaved change". After ZR a projector's `:fit` is the rig's, saved by the rig file: that assertion becomes `false?`, as `handle-projectors-add-effect-test`'s did here. `rig-report-test`, `rig-strip-test` and `setup-dialogs-test` pass in the merged tree.
- **Files Z3 also edits, and what ZR does in each:**
  - `state/domains.clj`: one NEW `(defstate rig-file …)` after `history`.
  - `events/handlers/history.clj`: seven labels after `:rig/set-split-width` (`:rig/set-up-from-show` … `:rig/forget-projector`).
  - `events/show_lock.clj`: eight events at the end of `blocked-events`.
  - `views/tabs/projectors.clj`: one require, and one child (`rig-file-status/rig-file-status`) between the title row and `rig-strip/rig-header`.
  - `views/components/rig_strip.clj`: `card-menu-items` gains Forget this laser… for a card that is off.
  - Not touched: `subs.clj`, `routing/rig.clj`, `events/handlers/projector.clj`, `views/toolbar.clj`.
- **For Z3 and later phases:** a new rig path (a top-level domain the rig owns) needs `:owner :rig` in the mapping, a place in `rig-file/rig-paths` (the test checks they agree) and in `history/rig-content-changed?`; anything inside a projector map (Z3's `:fit`) comes along with no change. A new event that changes a projector is a rig edit: undoable, never unsaved, and written by the autosave. A Z4 path the show owns needs `:owner :show`.
- **Other files this branch edits:** `events/core.clj` (the pipeline and the `:rig/write` effect), `app.clj`, `state/history.clj`, `state/persistent.clj`, `state/migration.clj`, `events/handlers/menu.clj`, `events/handlers/rig.clj`, `views/root.clj`, `views/dialogs/unsaved_confirm.clj`, `test/laser_show/test_runner.clj`.

### Found on the way

- **A pin to a laser this rig doesn't have loses its unit at Save** (Q-ZR-9): the zip's `hardware.edn` is this rig's copy (Q-ZR-2).
- **An imported laser keeps the show's On** (Q-ZR-8): while streaming, its engine starts at the show's address.
- **The rig file is checked for its shape only** (maps at the rig paths, a map per projector); a hand-edited value inside a projector map reaches the app as it is (a bad safety zone still fails closed).
- **`rig.edn.lock` stays in the folder** after the app quits. It is harmless: the lock itself is the OS's.
- **`clj -M:mac-arm -e` with the state namespaces loaded never exits** (something they load starts a non-daemon thread, likely the JavaFX toolkit through cljfx); seen while checking a written rig file by hand.
- **`history_test.clj` has three clj-kondo warnings from before** (unknown keys looked up in `extract-document-state`'s result).
- **The report's ℹ shows as a small i** in the dialog's font.
