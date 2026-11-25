# Timeline shows: timecode rules and the transport (S4, S5 in detail)

**Status:** Proposed   **Last verified against:** `63240576`
**Supersedes / Superseded by:** none. It details `02-phases.md` S4 and S5. Where it disagrees with `00`/`01`/`02`, this file is the newer decision (§9 lists each one).
**Related findings:** `00` TS-6, TS-9, TS-10; `01` §4, §5, §7; reactivity 15 (chase versions and episodes), reactivity 03 (Stop holds intents; ends need matching position ids), reactivity 13 (refused native edits).

Paths are under `src/laser_show/`; `T/` is `test/laser_show/`. **NEW** marks anything that doesn't exist yet. Code citations are for today's code at `63240576`, before S1 and S2. Where S2 re-keys something (`[col row]` → `:show-id`, `[:chains :cue-chains]` → `[:chains :shows]`), the text says "after S2".

## 1. Current behaviour (file:line)

### 1.1 The event pipeline and how effects combine

**The pipeline.** `handle-event-pipeline` (`events/core.clj:840-878`) builds it with `->`, so the first wrapper listed is the innermost. In order:

| Line | Wrapper |
|---|---|
| :867 | `show-lock/wrap-show-lock` |
| :868 | step grid |
| :869 | `timeline-handlers/wrap-normalize-timelines` |
| :870 | `history-handlers/wrap-history` |
| :871 | `history-handlers/wrap-dirty` |
| :872-878 | safety, output sync, rig autosave, learn, lost update, fresh learn id, `with-fx-event` |

So `wrap-history` and `wrap-dirty` see whatever `wrap-normalize-timelines` returns.

**Effects are a plain map, one value per key.** The registered effects are at `events/core.clj:983-1027`. Three of them carry events:

| Key | Takes | Defined |
|---|---|---|
| `:dispatch` | one event, run inline | `effect-dispatch` :257-260 |
| `:dispatch-n` | a vector of events, run inline in order | `effect-dispatch-n` :262-266 |
| `:dispatch-later` | **one** map, `{:event :delay-ms}`. There is no vector form | `effect-dispatch-later` :268-274 |

`state-first` (:886) runs `:state` first, then the other effects. So an inline `:dispatch` sees this event's state.

**`h/show-notice`** (`events/helpers.clj:73-87`) returns two effects:
```clojure
{:state (assoc-in state [:ui :notice] {:id :text :level})
 :dispatch-later {:event {:event/type :ui/clear-notice :id id} :delay-ms 6000}}
```
`:ui/clear-notice` clears only its own id (`events/handlers/ui.clj:88-95`).

**No middleware adds a notice today.** A middleware that simply `merge`d `show-notice`'s keys into a handler's effects would do two kinds of damage. It would **silently drop** the handler's own `:dispatch-later`, for example the projector Identify timer (`events/handlers/projector.clj:762`). And its `:state` would overwrite the handler's.

**`[:ui :notice]` is UI state.**
- The undo snapshot and the dirty check read only `document-roots` (`state/history.clj:65-68`; `extract-document-state` :70-87).
- So a notice written inside `wrap-normalize-timelines` never enters undo or dirty tracking. It rides along in the same `:state` as the edit.

### 1.2 `wrap-normalize-timelines` today

`events/handlers/timeline.clj:540-568` does five things, in order:
1. It runs the handler.
2. It returns at once unless `[:chains :cue-chains]` changed (by `identical?`) or the document id changed (:556-559).
3. It normalizes the changed cues (`model/normalize-timeline-cues`, :560-562). It skips this for undo and redo (`restores-history?`, :477-482).
4. It calls `reconcile-chase-sources before after` (:563-565). That versions every changed follower (`versioned`, :484-490). A changed cue that no longer follows is **unversioned**: its version and its `:chasing?` marker are dropped, through `cue-timing/deactivate-cue` (`unversioned`, :492-504).
5. It replaces `:state` only (:566-568). **Every other effect passes through untouched.**

### 1.3 What the clock does with a cue that stops following

Once reconcile has removed both the version and the marker, the next tick has no timing, no position and no report for that cue. Here is why:
- `show-time/chase` seeds the chase only with episodes that pass `current-episode?` (`animation/show_time.clj:241-242`).
- `current-episode?` needs the episode's version to equal `playback/source-version` (:219-228).
- `source-version` is nil once the cue doesn't follow (`timeline/playback.clj:60-68`).
- `apply-chase` walks only followers (`playback.clj:99-101`).
- `chase-reports` only looks at chased cells, `:chasing?` markers and runs at followers (`show_time.clj:274-295`).

### 1.4 The chase window, loops and wrapping

**The window.**
- `apply-chase` sets the beat to `(seconds − tc-start) · bpm/60` (`chase-beat`, `playback.clj:82-86`).
- The cue is active while `0 ≤ beat < effective-length` and timecode isn't `:stopped` (:103-105).

**Chased cues never loop.**
- Chased timing lives in the runtime's `:chase`, not its `:runs`, so `advance` never steps it (`show_time.clj:190-205`).
- Only `update-cue-timing` wraps at a loop's end, and only for `:runs` (`animation/cue_timing.clj:174-190`).
- The chase sets the position directly from the timecode (`playback.clj:113-120`).
- **So a chased show with `:loop {:enabled? true}` plays straight through `[0, length)`, and the loop is ignored.**

A *manual* run of a looping show never ends: `at-end?` requires `(not looping?)` (`cue_timing.clj:183`).

**MTC doesn't wrap at 24 h.**
- The decoder takes the hours from 5 bits and extrapolates seconds linearly (`input/mtc.clj`).
- `tc/seconds->tc` doesn't wrap the hours (`timeline/timecode.clj:31-46`, :56-59).
- `parse-tc` accepts hours up to 99 (:71-77).
- A sender wrapping from 23:59:59 to 00:00:00 therefore looks like a jump back.

**Frames.**
- `seconds->tc` floors to the frame containing the time (:56-59).
- `tc->seconds` gives the start of a frame (:61-64).
- Drop-frame 29.97 maps frames to real seconds (:48-54, :61-64).

### 1.5 The transport and playing by hand

**Timeline transport.**
- The handlers `handle-play`, `pause`, `toggle-play`, `stop` and `seek` are at `events/handlers/timeline.clj:379-428`.
- Play, pause, toggle and stop are wrapped in `unless-chasing` (:360-363, :603-606). **`:timeline/seek` isn't** (:607).
- So a seek on a chased cue writes `:seek` into its `{:chasing? true}` marker (:426-428). The chase ignores it, because `reconcile-runs` skips chased intents (`show_time.clj:182`), but the request still lands in app state.

**Intents.**
- `activate-cue` writes the intent and sets `:playing? true` and `:active-cell` (`cue_timing.clj:331-346`).
- `deactivate-cue` removes it. Removing the last one sets `:playing? false` (:348-362).

**Transport Stop.**
- Stop sets `:playing? false`, keeps the intents, and issues a `:restart` (`events/handlers/timing.clj:92-101`).
- The restart resets every run to beat 0 (`show_time.clj:121-123`).
- The clock keeps stepping runs while stopped. **So a held timeline's published position restarts at 0 and keeps counting**, even though nothing is drawn.
- `subs/held-cue-count` and `output-held?` say so (`subs.clj:332-347`).
- Play only sets `:playing? true` (`timing.clj:68-73`).
- Starting any cue with `activate-cue` also sets `:playing? true` (`cue_timing.clj:345`), so it resumes every held cue.

**The end of a run.** `:show-clock/cue-ended` removes the intent only for the same run with the same `:position-ids`, and never for a follower (`timing.clj:160-178`).

**Keys.**
- The Transport menu's Play/Stop has the accelerator `[:space]` (`views/components/title_bar.clj:146`).
- The main window's key filter skips bindings for keys the focus owner claims (`views/key_triggers.clj:44`). `keys/claim-keys!` is at `views/keys.clj:30`, and `keys/list-key-target?` at :24.
- The timeline dialog claims `:space` and toggles play with it (`views/dialogs/timeline_cue_editor.clj:130-149`).
- `list.clj` has the pattern of a claim plus a `KEY_PRESSED` filter (`views/components/list.clj:818-860`).

**Views that redraw every frame.**
- `clock-view/ext-clock-redraw` (`views/components/clock_view.clj:86-104`) calls `(redraw! node snapshot user-data)` when the tick or the user data changes.
- Patterns to follow: `timeline_editor/redraw-readout!` (`views/components/visual_editors/timeline_editor.clj:597-610`, its desc at :612-632) and `slider_meters/redraw-meter!` (`views/components/slider_meters.clj`).

**Sliders.**
- `gesture/slider` (`views/components/gesture.clj:205`) sends every value change while pressed, as an undoable gesture. That is wrong for a seek bar.
- `gesture/capture` (:31), `capture!` (:63), `finish!` (:84) and the release handling (`slider-release!`, :138) can be reused on their own.

**Status bar and inspector.**
- Status bar: `playing-list` and `playing-status` (`views/status_bar.clj:90-111`), fed by `subs/playing-cues` (`subs.clj:371-383`).
- `subs/mtc-status` is at `subs.clj:281-285`.
- The inspector's `chase-toggle` (`views/components/visual_editors/timeline/inspector.clj:180-198`) is two toggle buttons that dispatch `:timeline/apply-op :set-chase` directly. They aren't wrapped in `applied-control`.
- The "Tempo isn't locked" warning is at :257-262, and the MTC rate warning at :163-178.

**Applied controls.** `applied-value/applied-control` (`views/components/applied_value.clj:217`):
- It sends `(on-change v)` once.
- After `settle-ms` (:44), if nothing applied, it puts back the subscribed `:value`.
- If `on-change` returns nil, that means "no edit", and it snaps back at once.

### 1.6 Tests and fixtures that exist

**MTC helpers:** `laser-show.input.mtc-test/qf-bytes` (`T/input/mtc_test.clj:11`) and `feed` (:6).

**`T/animation/show_time_test.clj`:**
- helpers `init!` :32 and `run-at` :50;
- chase tests `chase-activates-and-reports-test` :550, `chasing-timeline-follows-mtc-test` :630, `chase-takes-over-a-playing-cue-test` :669, `follow-off-clear-and-replace-prune-chase-test` :786 and `chase-reports-respect-explicit-stop-test` :968.

**Other tests:**
- `laser-show.views.status-bar-test/playing-list-test` (`T/views/status_bar_test.clj:98`).
- `timeline-readout-follows-the-clock-test` (`T/ui_test/timeline_editor_test.clj:292`).

**`org.clojure/test.check` is not a dependency** (`deps.edn` has no `test.check`).

## 2. Root cause

- **Effects hold one value per key, and a notice is two of them.** A middleware that "merges" `show-notice` drops either a `:dispatch-later` or a notice. Nothing in the codebase says how two notices or two timers from one event combine.
- **The timecode rule has no place in the pipeline today.** Its natural place is between normalizing and reconciling:
  - a turn-off made there is caught by reconcile and history for free;
  - after reconcile, it would leave a stale chase marker until the next report;
  - before normalizing, it would judge clip lengths that aren't final.
- **The overlap rule must match what the chase actually plays.** The chase plays linear seconds over half-open ranges, with no loop and no 24 h wrap. Otherwise the check passes while two shows light at once, or refuses shows that never meet. The floored TC display adds a trap: type the end you see into the next show's start, and it is refused.
- **The transport is spread over three owners:** the global gate (`:playing?`), the per-key intents, and the clock's runtime positions. A now-playing bar needs a sub that names one show without reading positions, plus a redraw path for the positions.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `wrap-normalize-timelines` (`events/handlers/timeline.clj:540-568`) | Gains one step between normalize and reconcile (§4.2). After S2 it walks `[:chains :shows]` |
| `reconcile-chase-sources`, `unversioned` (:492-538) | Unchanged. It drops the turned-off show's version and marker in the same event |
| `restores-history?` (:477-482) | Also skips the turn-off |
| `h/show-notice` (`events/helpers.clj:73-87`) | Unchanged. NEW `h/merge-notice` builds on its shape |
| `effect-dispatch-later` (`events/core.clj:268-274`) | Extended to take a vector of maps as well as one map |
| `model/effective-length` (`timeline/model.clj:475-478`); `tc/beats->seconds` (`timeline/timecode.clj:20`) | The range arithmetic |
| `tc/frames->tc` (private, `timecode.clj:31-46`) | Used by NEW `tc/seconds->tc-ceil` in the same namespace |
| `applied-value/applied-control` (`views/components/applied_value.clj:217`) | The TC checkbox, and both of the inspector's chase buttons |
| `clock-view/ext-clock-redraw` (`views/components/clock_view.clj:86-104`); `redraw-readout!` (`timeline_editor.clj:597-610`) | NEW `now-playing/redraw-now-playing!` |
| `gesture/capture!`, `capture`, `finish!` (`views/components/gesture.clj:31, :63, :84`) | The seek slider's press. Redraws are suppressed while it is pressed, and a seek is sent only when the end reason is `:release` |
| `keys/claim-keys!` (`views/keys.clj:30`); the filter pattern (`views/components/list.clj:818-860`) | The table claims `:space :enter :delete` |
| `cue-timing/activate-cue`, `deactivate-cue`, `request-seek` (`animation/cue_timing.clj:331-368`) | `:shows/play`, `stop`, `seek` |
| `subs/mtc-status` (`subs.clj:281-285`), `output-held?` (:343-347) | The header chip; the bar's held chip |

**NEW functions**, each justified:

| Function | Why |
|---|---|
| `h/merge-notice` (`events/helpers.clj`) | The only safe way for a middleware to add a notice to effects it didn't write (§4.1) |
| `tc/seconds->tc-ceil` (`timeline/timecode.clj`) | TC end must show the first frame at or after the end (§4.3), and only this namespace can reach `frames->tc` |
| `shows/tc-range`, `tc-overlap?`, `timecode-problem`, `invalid-timecode-ids`, `invariant-violations` (`timeline/shows.clj`) | One definition of the rule, shared by the handlers, the middleware, the subs and the tests |
| `subs/now-playing`, `subs/playing-shows`, `subs/show-timecode-problem` | The bar, the status bar, and the ⚠ and inspector hints |
| `views/components/now_playing.clj` | The bar, `seek-slider` and `redraw-now-playing!` |

## 4. Changes per namespace

### 4.1 `laser-show.events.helpers` and `laser-show.events.core` (existing): adding a notice safely

**`effect-dispatch-later`** (`events/core.clj:268-274`) takes either one `{:event :delay-ms}` map or a vector of them. Each map gets its own future. Update the effect's line in the `effects` docstring (:938).

**NEW `h/merge-notice [effects before text level]`.** `effects` must have `:state`; `before` is the event's own `:state` co-effect. It works in four steps:

1. **Find a fresh notice.** Let `prior` be `[:ui :notice]` in `(:state effects)`. It is *fresh* when its `:id` differs from the id at `[:ui :notice :id]` in `before`, which means the handler set it during this event.
2. **Write one notice.** If `prior` is fresh, the text becomes `(str (:text prior) " " text)` and the level the more severe of the two (`:error` > `:warning` > `:info`). Either way the notice gets a new id `n`, from `random-uuid`.
3. **Fix the timers.**
   - Read `:dispatch-later` as a sequence: none, one map, or a vector.
   - If `prior` is fresh, drop the entry whose event is `{:event/type :ui/clear-notice :id (:id prior)}`, since that notice was just replaced.
   - Add `{:event {:event/type :ui/clear-notice :id n} :delay-ms h/notice-ms}`.
   - Store a single map when one entry is left, otherwise a vector.
4. **Leave the rest alone.** `:dispatch`, `:dispatch-n`, the history keys and every other effect come back unchanged.

| Handler's effects | After `merge-notice … "Timecode off for 'A' …" :warning` |
|---|---|
| `{:state s}` | `{:state s+{:id n}, :dispatch-later {clear n}}` |
| `{:state s :dispatch {:event/type :ui/open-dialog …}}` | the same `:dispatch`, plus `:dispatch-later {clear n}` |
| `{:state s(notice n0 "Saved BPM") :dispatch-later {clear n0}}`, with `n0` fresh | notice `{:id n :text "Saved BPM Timecode off for 'A' …" :level :warning}`, `:dispatch-later {clear n}` |
| `{:state s :dispatch-later {:event {:event/type :projectors/identify-ended …} …}}` | `:dispatch-later [{identify…} {clear n}]` |
| `{:state s(notice n0 from an earlier event, not fresh)}` | notice `n` replaces it. `n0`'s old timer, already running, later clears nothing (`ui.clj:88-95`) |

**Invariants**
- Exactly one notice is on screen.
- Every notice id written has exactly one clear timer.
- No effect key other than `:state` and `:dispatch-later` changes.

### 4.2 `laser-show.events.handlers.timeline` (existing, `events/handlers/timeline.clj:540-568`)

This is the S2 shape of `wrap-normalize-timelines`. Keys are `(shows/play-key id)`; `old` and `new` are `[:chains :shows]` before and after the handler.

1. **Run the handler:** `effects (handler event)`. The fast path is unchanged: return at once when `[:chains :shows]` is `identical?` and the document id is equal.
2. **Normalize:** `normalized` = `model/normalize-timeline-cues old new`. Skipped when `restores-history?`.
3. **NEW: turn timecode off where the invariant breaks.**
   - Skipped when `restores-history?`, and when the document id changed (New or Open). Open's migration already enforces the invariant before `mark-clean` (`01` §6 step 3). A turn-off here would leave the file dirty, because `:file/project-loaded` sets the saved baseline inside its handler.
   - `changed-ids` are the ids whose entry isn't `identical?` to `old`'s.
   - `off` = `(shows/invalid-timecode-ids normalized changed-ids)`.
   - `checked` = `normalized`, with `:timeline/chase` dissoc'd for each id in `off`.
4. **Reconcile:** `after'` = `reconcile-chase-sources before (assoc-in after [:chains :shows] checked)`. A turned-off show has changed and doesn't follow, so `unversioned` removes its version and its `:chasing?` marker in this same state.
5. **Return:** `effects'` = `(assoc effects :state after')`. When `off` isn't empty, the result is `(h/merge-notice effects' before (turn-off-text …) :warning)`.

**The notice text** comes from NEW private `turn-off-text`:

| Case | Text |
|---|---|
| one show, overlap | *"Timecode off for 'A': it now overlaps 'B' (00:05:00:00–00:10:00:00)"* |
| one show, no BPM | *"Timecode off for 'A': it needs a BPM"* |
| one show, no length | *"Timecode off for 'A': it is empty"* |
| two or more | *"Timecode off for 2 shows: 'A' (overlaps 'B'), 'C' (needs a BPM)"* |

- A title is the show's `:name`, or "Untitled".
- A range is written `[tc/format-tc (tc/seconds->tc start fps)]–[tc/format-tc (tc/seconds->tc-ceil end fps)]`, at the **other** show's fps.

**Why this order.**
- Normalizing first means clip lengths are final.
- Reconciling after means the marker goes in the same event, so nothing waits for a clock report.
- The notice comes last, so it merges into the final effects.
- `wrap-history` then records one step under the handler's own label, because `:history/description` passes through untouched. `wrap-dirty` marks the file unsaved.

**Show lock.** The turn-off isn't a blocked event, because it only ever removes light. An edit the lock allows can turn timecode off, and it shows the same notice.

### 4.3 `laser-show.timeline.timecode` (existing)

**NEW `seconds->tc-ceil [seconds fps]`** returns the timecode of the first frame boundary at or after `seconds`, with 1 µs tolerance: `frames = ceil(seconds·fps − 1e-6)`, through `frames->tc`.

It is used for the TC end column (in `01` §3.1 it replaces `seconds->tc`), the notices and the ⚠ tooltip. A start typed exactly as the shown end then never overlaps (§7.2 row 4).

### 4.4 `laser-show.timeline.shows` (NEW, `01` §1)

**`tc-range [show]`** returns `{:start s :end e :fps f}`, where:
- `s` is `:timeline/tc-start`, or 0.0 when it's missing;
- `e` = `s + beats->seconds (effective-length show) tempo`;
- `f` = `(tc/cue-fps show)`.

It returns nil without a `:timeline/tempo-bpm`, or when `effective-length` ≤ 0. It ignores `:loop`, because that's what the chase plays (§1.4).

**`tc-overlap? [a b]`** is `(and (< (:start a) (- (:end b) eps)) (< (:start b) (- (:end a) eps)))`, with `eps` = 1e-6 s. That is plain seconds: no frame snapping, no modulo 24 h, no fps conversion. The chase compares plain seconds too (`playback.clj:85-86`, :105).

**`timecode-problem [shows id]`** checks `id` against every **other** show with `:timeline/chase :mtc`, in `(str id)` order, and returns the first problem:
- `{:reason :no-bpm}` without a locked tempo;
- `{:reason :empty}` when the length is 0;
- `{:reason :overlap :with id2 :range (tc-range other)}`;
- nil when there is no problem.

It is O(n) in the number of enabled shows.

**`invalid-timecode-ids [shows changed-ids]`:**
- It walks the changed ids that have `:timeline/chase :mtc`, in `(str id)` order.
- It checks each one against a **kept** set, which starts as every unchanged enabled show and grows with each changed show that passes.
- It returns a vector of `[id problem]` pairs. This changes `01` §1, which returned bare ids; the pairs let the notice name the reason.
- It is O(c·n) for c changed ids, and c is usually 1.

**`invariant-violations [shows]`** is for tests only. It sorts the enabled ranges by `:start` and checks each against its neighbour, O(n log n).

**Performance.**
- `tc-range` reduces over each enabled show's `:items` (`model/content-end`, `timeline/model.clj:470-473`).
- It runs on the `laser-show-events` thread, and only for events that change `[:chains :shows]`, never on the 60 Hz clock thread.
- The cost is *unverified*. S4's REPL check times `:timeline/apply-op` with 50 enabled shows of 200 clips each.

### 4.5 `laser-show.events.handlers.shows` (NEW; the S4 and S5 parts)

**`:shows/set-timecode {:show-ids :enabled?}`**
- **Enable.**
  - Take the ids in `shows/visible-ids` order, then any that aren't visible, in `(str id)` order.
  - Fold over them. Each id that passes `timecode-problem` against the shows accumulated so far gets `{:op :set-chase :source :mtc}` through `model/apply-op`; the others are refused.
  - If every id passes, return `{:state s' :history/description "Enable Timecode"}`.
  - If some were refused but any were enabled, return `(h/show-notice s' text :warning)` plus the description.
  - If all were refused, return `h/show-notice` on the unchanged document, so there is no undo step.
- **Disable.** Never refused. Labelled "Disable Timecode".
- **Already in that state.** Return `{:state state}`.
- The table sends `:show-ids #{id}`. The row menu sends the visible selected ids.

**`:shows/play {:show-id}`**
- Refused for an enabled show (`01` §5).
- Otherwise:
  1. If `[:ui :shows :manual]` is another show with an intent, deactivate it.
  2. Activate `[:show id]` from beat 0.
  3. Set `:manual` to the id.
- Like every `activate-cue`, it resumes a held transport (§1.5).

**`:shows/toggle-play`**
- `:manual` has an intent: pause or resume it, as `handle-toggle-play` does (`timeline.clj:407-413`).
- `:manual` is set but has no intent: `:shows/play` it.
- `:manual` is nil: play the first selected visible id, else the first visible id, else nothing.

**`:shows/stop`** calls `deactivate-cue` on `:manual`'s key. `:manual` stays set.

**`:shows/seek {:beat}`** calls `request-seek` on `:manual`'s intent, only when it isn't chasing.

**`:shows/next` and `:shows/previous`** work as in `01` §5.

**When `:manual` is enabled for timecode,** all of these do nothing (`{:state state}`). The bar disables them anyway.

**`:shows/duplicate`** (S3) copies the show without `:timeline/chase`, because a copy always overlaps its original.

### 4.6 `events/handlers/timeline.clj` `:timeline/seek` (existing, :607)

Wrap it in `unless-chasing`, as :603-606 are. A chased show's marker then never gets a `:seek`. Clicking the ruler of a chased show still moves the cursor, through `:timeline/set-cursor-beat` (:206-211).

### 4.7 `laser-show.subs` (existing)

**NEW `show-timecode-problem [id]`** returns `(shows/timecode-problem shows id)` for a show that isn't enabled, and nil for one that is. It reads `[:chains :shows]`. The inspector and the table's ⚠ use it.

**NEW `now-playing`** reads `[:ui :shows :manual]`, `ex/active-cues` and `[:chains :shows]`, and picks, in order:
1. `:manual`, when it has an intent → `:source :manual`;
2. else the first `[:show id]` with a `:chasing?` marker, in `shows/key-order` → `:source :timecode`;
3. else `:manual` without an intent → `:source :manual :state :stopped`;
4. else nil.

```clojure
{:show-id #uuid "6f1c…" :title "One More Time" :artist "Daft Punk"
 :source :manual            ; or :timecode
 :state :playing            ; :playing | :paused | :stopped
 :held? false               ; (and intent (not playing?)): Stop holds it
 :length 656.0              ; beats, model/effective-length
 :bpm 122.7 :approx? false  ; locked, else the global BPM with :approx? true
 :tc-start 3600.0 :fps 25 :loop? false}
```

It holds no position. It changes when a show starts, pauses or stops, when a chase marker comes or goes, and when the show is edited.

**NEW `playing-shows`** returns `[{:show-id :title}]` for the `[:show id]` intents, in `key-order`. It is empty while not playing, as `playing-cues` is.

### 4.8 `laser-show.views.components.now-playing` (NEW)

**`now-playing-bar`** subscribes to `now-playing` and `output-held?`. Its ids, which the tests use:

| Id | Node | Shown / enabled |
|---|---|---|
| `#now-playing` | the `:h-box` | always |
| `#now-playing-empty` | label "Nothing playing" | when the sub is nil |
| `#now-playing-prev`, `#now-playing-next` | buttons → `:shows/previous`, `:shows/next` | disabled for `:source :timecode` |
| `#now-playing-toggle` | button → `:shows/toggle-play`; play or pause icon by `:state` | hidden for `:source :timecode` |
| `#now-playing-tc-chip` | label `TC` | only for `:source :timecode` |
| `#now-playing-stop` | button → `:shows/stop` | disabled for `:timecode` or `:stopped` |
| `#now-playing-title`, `#now-playing-artist` | labels | from the sub |
| `#now-playing-elapsed`, `#now-playing-remaining`, `#now-playing-tc` | labels | text from `redraw-now-playing!` only |
| `#now-playing-seek` | the `seek-slider` | disabled for `:timecode` |
| `#now-playing-held` | label "HELD", with the tooltip "Stop (blackout) holds the output. Transport Play resumes it" | when `:held?` |
| `#now-playing-edit` | button ✎ → `:shows/open-editor` | when the sub is non-nil |

**NEW `redraw-now-playing! [box snapshot data]`**, where `data` is `{:show-id :length :bpm :approx? :tc-start :fps :loop?}`:
- `pos` = `(or (show-time/cue-beats snapshot [:show show-id]) 0.0)`.
- **Elapsed** is `tc/format-clock` of `beats->seconds pos bpm`.
- **Remaining** is `"-"` plus `format-clock` of `(length − pos)`, floored at 0. It reads `"loop"` when `:loop?`.
- **TC** is `format-tc` of `(seconds->tc (+ tc-start elapsed-s) fps)`, with a "≈" prefix when `:approx?`.
- **The slider** gets `(.setValue pos)`, **unless `(gesture/capture slider)` is set**, which means the user is dragging it.
- It writes a label only when its text changed, as `redraw-readout!` does.

**NEW `seek-slider`:**
- It is a `:slider` with `:id "now-playing-seek"`, `:min 0`, `:max length`, and **no `:value` prop**, so cljfx never moves it. It sits inside the `ext-clock-redraw` box.
- Its `:on-created` installs two filters:
  - `MOUSE_PRESSED`: `(gesture/capture! slider {:show-id id} on-end)`;
  - `MOUSE_RELEASED`: `Platform/runLater #(gesture/finish! slider gid :release)`, as `gesture/slider-release!` does.
- `on-end` with reason `:release` sends `events/dispatch! {:event/type :shows/seek :show-id (from capture) :beat (.getValue slider)}`.
- Any other reason sends no event, and the next redraw puts the clock's position back. The other reasons are `:blur`, `:window-blur`, `:detach`, `:replaced` and `:dispose`.
- It has `:focus-traversable false`, so keys never move it.
- It makes no history step: `:shows/seek` only changes `[:playback]`.

### 4.9 `laser-show.views.tabs.shows` (NEW in S2; the S4 and S5 parts)

**The MTC chip.** `#shows-mtc-chip` in the header reads `subs/mtc-status`. Its style follows `:state`, and its text is `:text`, or "MTC —" when stopped.

**The bar** sits at the bottom of the tab.

**Space and Enter in the table.**
- The table node gets `(keys/claim-keys! node #{:space :enter :delete})` and a `KEY_PRESSED` filter, as in `list.clj:818-860`.
- When `keys/list-key-target?`, Space sends `:shows/toggle-play` and Enter sends `:shows/play {:from-selection true}`. Both are consumed.
- With the focus outside the table, Space stays the Transport menu's Play/Stop.
- The claim also stops `key-triggers` from treating Space as a binding (`key_triggers.clj:44`).

### 4.10 `views/components/show_table.clj` (NEW; the S4 part)

**The TC cell:**

```clojure
{:fx/type applied-value/applied-control
 :property :selected
 :address [:show id :timecode]
 :value (:timecode? row)
 :on-change (fn [v] {:event/type :shows/set-timecode :show-ids #{id} :enabled? v})
 :desc {:fx/type :check-box :id (str "show-tc-" id)}}
```

- A refused tick leaves `:value` false. After `settle-ms` the box unticks, and `#status-notice` shows the refusal.
- **⚠** has the id `(str "show-tc-problem-" id)` and a tooltip from `show-timecode-problem`: "needs a BPM", "empty", or "would overlap 'Y' (range)".

### 4.11 `views/components/visual_editors/timeline/inspector.clj` (existing)

**`chase-toggle`** (:180-198). Wrap each toggle button in `applied-control` with `:property :selected`, and remove the `:on-action`s.

| Button | `:address` | `:value` | `:on-change` |
|---|---|---|---|
| Off | `[:show id :chase :off]` | `(nil? chase)` | `#(when % {:event/type :shows/set-timecode :show-ids #{id} :enabled? false})` |
| MTC | `[:show id :chase :mtc]` | `(= :mtc chase)` | the same, with `:enabled? true` |

A click on the button that is already selected returns nil from `on-change`, so it snaps back at once.

**The "Tempo isn't locked" warning** (:257-262) can no longer show, because an enabled show always has a locked tempo. It is replaced by `#timeline-tc-problem`, visible when `show-timecode-problem` is non-nil:
- "Lock the tempo to follow timecode."
- "Add clips or set a length to follow timecode."
- "Overlaps 'Y' (01:04:10:00–01:09:12:05)."

The `mtc-rate-warning` stays (:163-178).

### 4.12 `views/status_bar.clj` (existing, :90-111)

- `playing-list` takes `[cues shows]`. Shows are named `"♪ " title`, after the grid cues.
- `max-named-cues` counts both together, then adds "+n".
- `playing-status` also subscribes to `subs/playing-shows`.

## 5. State placement

| Key / path | Lives in | Written by | Undo/dirty? |
|---|---|---|---|
| `[:chains :shows id :timeline/chase]` | document | `:shows/set-timecode`; the middleware's turn-off (§4.2), inside the edit's own `:state` | yes/yes. A turn-off is part of the edit's step, e.g. "Move Clip" |
| `[:ui :notice]` | UI | `h/show-notice`, `h/merge-notice` | no/no: it is outside `document-roots` (`state/history.clj:65-68`) |
| `[:playback :chase-sources :cells [:show id]]`, `[:playback :active-cues [:show id]]` | session | `reconcile-chase-sources`; the `:shows/*` transport; `:show-clock/apply-chase` | no/no |
| `[:ui :shows :manual]` | UI, session | `:shows/play`, `next`, `previous`, `toggle-play` | no/no. Read by `subs/now-playing` |
| Positions | runtime, `show-time` snapshot `:positions` | the show clock thread, 60 Hz | n/a. `redraw-now-playing!` reads them on the FX thread |
| The seek press | node properties (`gesture/capture!`) | the FX thread | n/a |

## 6. Events and effects

| Event / effect key | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:dispatch-later` with a vector | extends `events/core.clj:268-274` | `h/merge-notice` | effect |
| `:shows/set-timecode` | NEW | the table's TC `applied-control` (§4.10); the row menu; the inspector's chase buttons (§4.11) | yes |
| `:shows/play`, `toggle-play`, `stop`, `seek`, `next`, `previous` | NEW | `#now-playing-*` (§4.8); table Enter and Space (§4.9); a row's double-click and menu | yes |
| `:timeline/seek` | exists (:420-428, routed at :607); gains `unless-chasing` | `timeline_editor.clj`, the ruler | yes |
| `:timeline/apply-op` `:set-chase` | exists (`model.clj:681-683`) | no view after §4.11. The middleware still enforces TS-6 if anything sends it | yes |
| `:ui/clear-notice` | exists (`ui.clj:88-95`) | `:dispatch-later` timers | yes |
| `:show-clock/cue-ended` for `[:show id]` | exists (`timing.clj:160-178`; uses `shows/cue-at` from S1) | `show_time.clj:316-319` | yes. `:manual` stays |

No new registered effect keys.

## 7. Data examples and invariants

### 7.1 Event traces

Shows A and B run at 120 BPM and 25 fps; `tc-start` is in seconds.

**Trace 1: a clip move makes A overlap B**

```clojure
;; before
{:chains {:shows {A {:timeline/chase :mtc :timeline/tempo-bpm 120.0 :timeline/tc-start 0.0 :items [… ends at 600.0]}
                  B {:timeline/chase :mtc :timeline/tempo-bpm 120.0 :timeline/tc-start 300.0 :items [… ends at 600.0]}}}
 :playback {:playing? true :active-cues {[:show A] {:chasing? true :timeline? true :chase-id [D [:show A] 3 t0 812]}}
            :chase-sources {:next 5 :cells {[:show A] 3 [:show B] 4}}}}
;; event
{:event/type :timeline/apply-op :show-id A :op {:op :move-clips :clip-ids #{c9} :delta 4.0}}
;; 1 handler: A ends at 604 beats → tc-range [0.0, 302.0)
;; 2 normalize: unchanged
;; 3 invalid-timecode-ids → [[A {:reason :overlap :with B :range {:start 300.0 :end 600.0 :fps 25}}]]
;;   A loses :timeline/chase
;; 4 reconcile: A changed, not following → unversioned:
;;   :cells {[:show B] 4}; deactivate-cue [:show A] → :active-cues {} :playing? false
;; 5 merge-notice → [:ui :notice] {:id n :text "Timecode off for 'A': it now overlaps 'B' (00:05:00:00–00:10:00:00)" :level :warning}
;;   :dispatch-later {:event {:event/type :ui/clear-notice :id n} :delay-ms 6000}
;; wrap-history: one step "Move Clip"; wrap-dirty: dirty? true
;; next tick, MTC [:running 150.0]: A's episode fails current-episode? (version nil) and is dropped;
;;   B is outside [300, 600); :chase {}; no reports; :positions has no [:show A]
```

**Trace 2: Undo**
1. `restores-history?` is true, so there is no normalize and no turn-off.
2. Reconcile sees A changed and following, so A gets version 5 (`:next 6`).
3. On the next tick at 150.0, A enters its window and starts a new episode, `[D [:show A] 5 t0 k]`.
4. The On report has `:expected-owner :none`, and `:show-clock/apply-chase` installs the marker.
5. The invariant holds: A is `[0, 300)` and B is `[300, 600)`.

**Trace 3: a refused tick**
- A is on over `[0,300)`. B is off, and would be `[299,600)`.
- `{:event/type :shows/set-timecode :show-ids #{B} :enabled? true}` returns `show-notice` with "'B' overlaps 'A' (00:00:00:00–00:05:00:00)", and the document is identical.
- The middleware takes its fast path: no undo step, and dirty is unchanged.
- After `settle-ms`, B's checkbox shows false again.

**Trace 4: a manual show ends**
- `:manual` is `u`, its intent is `{:run r :timeline? true :start-beat 0.0}`, and the show is 8 beats long with tempo 120 locked.
- The clock reports `:show-clock/cue-ended {:cell [:show u] :run r :position-ids {:restart nil :seek nil}}`.
- The intent goes and `:playing?` becomes false. `:manual` stays `u`.
- `now-playing` → `{:show-id u :source :manual :state :stopped …}`. The bar shows elapsed 0:00 and remaining -0:04.

**Trace 5: Stop while a manual show plays**
- `:transport/stop` writes `:restart`, and the run restarts at beat 0.
- `now-playing` has `:held? true`, and `#now-playing-held` is visible.
- Elapsed counts up again from 0:00 while nothing is drawn, because the clock still steps held runs (§1.5).
- `:transport/play` → `:held? false`.

### 7.2 Overlap examples (`tc-overlap?` on `tc-range`s)

| A | B | Overlap? | Why |
|---|---|---|---|
| `[0, 300)` | `[300, 600)` | no | the ends touch |
| `[0, 300)` | `[299.99, 600)` | yes | |
| tempo 120, 600.00000000001 beats → `[0, 300.000000000005)` | `[300.0, 600)` | no | inside the 1 µs tolerance |
| 122.7 BPM, 656 beats, start 3600 → end 3920.78; TC end shown as `01:05:20:20` (ceiling, 25 fps) | B at 25 fps, start typed `01:05:20:20` = 3920.8 | no | the shown end is a frame boundary at or after the real end |
| same A | B typed `01:05:20:19` (the floor) = 3920.76 | yes | a floored display would invite this |
| same A (25 fps) | B at 30 fps, start typed `01:05:20:20` = 3920.667 | yes | frames differ between rates; the notice shows A's range |
| A at 29.97 DF, `01:00:00;00` = 3600.0, 64 beats at 128 → `[3600, 3630)` | B at 30 fps, `01:00:30:00` = 3630.0 | no | drop-frame maps to real seconds |
| A `23:59:00:00` + 120 s → `[86340, 86460)` | B `[0, 60)` | no | linear seconds, no 24 h wrap; A stops when the sender wraps |
| A loop enabled, length 64 at 128 → `[0, 30)` | B `[30, 60)` | no | the chase ignores the loop |

### 7.3 `invalid-timecode-ids` examples

Shows are on unless marked off.

| Shows | changed | Result |
|---|---|---|
| A `[0,302)`, B `[300,600)` | `#{A}` | `[[A {:reason :overlap :with B …}]]` |
| A on, tempo removed | `#{A}` | `[[A {:reason :no-bpm}]]` |
| A `[0,300)`, B off `[100,400)` | `#{B}` | `[]` |
| A `[0,300)`, only its Artist edited | `#{A}` | `[]` |
| C `[0,100)` unchanged; A `[50,150)` and B `[120,220)` both changed, with `(str A) < (str B)` | `#{A B}` | `[[A {:with C}]]`. A is dropped from the kept set, so B is checked against C only, and passes |

### 7.4 `now-playing` examples

| `:manual` | intents | Result |
|---|---|---|
| nil | `{}` | nil |
| u | `{[:show u] {:run r}}` | `{:show-id u :source :manual :state :playing …}` |
| u | `{[:show u] {:run r :paused? true}}` | `:state :paused` |
| u (ended) | `{[:show v] {:chasing? true …}}` | `{:show-id v :source :timecode :state :playing}` |
| u (ended) | `{}` | `{:show-id u :source :manual :state :stopped}` |
| u | `{[:show u] {:run r}}`, `:playing? false` | `:held? true` |

### 7.5 Invariants

1. After every event, the enabled shows all have a locked tempo and a length above 0, and their ranges are pairwise disjoint (`invariant-violations` is empty).
2. A turn-off and its edit are one undo step, so undo restores both. The turn-off never runs on undo, redo, New or Open.
3. A turned-off show loses its chase version and its `:chasing?` marker in the same event, and has no position from the next tick on.
4. There is one notice, and one clear timer per notice id. `merge-notice` never drops a `:dispatch-later` that isn't a notice timer.
5. No position enters app state. The seek slider sends one event per release, never during a drag.

## 8. Tests (named test vars + fixtures)

### Unit tests (`clojure -M:test -n <ns>`)

**Timecode and the overlap rules**
- `laser-show.timeline.timecode-test/seconds->tc-ceil-test` (`T/timeline/timecode_test.clj`):
  - 30.0 s at 30 fps → `00:00:30:00`;
  - 3920.78 s at 25 → `01:05:20:20`;
  - 29.97 DF at 60.0 s;
  - 0.0 → zero.
- `laser-show.timeline.shows-test/overlap-examples-test`: every row of §7.2.
- `laser-show.timeline.shows-test/timecode-problem-examples-test`: the table in `01` §4.2.
- `laser-show.timeline.shows-test/invalid-timecode-ids-test`: §7.3.

**Notices and timers**
- `laser-show.events.helpers-test/merge-notice-examples-test` (a NEW file, `T/events/helpers_test.clj`): every row of the table in §4.1, with ids fixed by `with-redefs [random-uuid …]`.
- `laser-show.events.core-test/dispatch-later-takes-a-vector-test` (`T/events/core_test.clj`): two timers with a captured dispatch, both delivered.

**The turn-off**
- `laser-show.events.shows-handlers-test/edit-that-overlaps-turns-timecode-off-test` runs Trace 1 through `events/event-handler`, then `:edit/undo`. It asserts:
  - the state and `[:ui :notice :text]`;
  - one history entry, "Move Clip";
  - `dirty? true`;
  - after the undo, both timecode and the clip are back (Trace 2's state).
- `laser-show.events.shows-handlers-test/unlocking-tempo-turns-timecode-off-test`: `:set-tempo {:bpm nil}`.
- `laser-show.events.shows-handlers-test/turn-off-merges-with-handler-notice-test`: a handler stub that returns a fresh notice and a timer gives one combined notice and one timer.
- `laser-show.events.shows-handlers-test/open-never-turns-timecode-off-test`: a document-id change with overlapping enabled shows in the loaded state leaves them on, and `dirty? false`.

**Timecode, duplicate and invariant**
- `laser-show.events.shows-handlers-test/set-timecode-examples-test`: the rows of `01` §4.2, including the multi-id order and Trace 3.
- `laser-show.events.shows-handlers-test/duplicate-drops-timecode-test`.
- `laser-show.events.shows-handlers-test/invariant-holds-after-random-edits-test`:
  - No `test.check`; it uses seeds 0–199 on `java.util.Random`.
  - Each seed runs 30 events on 5 shows, drawn from `:shows/set-timecode`, `:shows/set-bpm`, `:shows/set-tc-start`, `:timeline/apply-op :move-clips`, `:set-length`, `:edit/undo` and `:edit/redo`.
  - After every event, `shows/invariant-violations` is empty.
  - A failure prints the seed and the event list.

**Playing by hand**
- `laser-show.events.shows-handlers-test/play-replaces-previous-manual-show-test`, `play-refused-while-following-timecode-test` and `next-previous-follow-visible-order-test`: the table in `01` §5.
- `laser-show.events.shows-handlers-test/play-resumes-held-transport-test`: `:transport/stop` with grid `[0 0]` held, then `:shows/play u` → `:playing? true`, with both intents present.
- `laser-show.events.timeline-handlers-test/seek-ignored-while-chasing-test`: a `{:chasing? true}` marker gets no `:seek`.

**The clock**
- `laser-show.animation.show-time-test/turned-off-show-stops-next-tick-test`:
  1. Fixtures: `init!` and `run-at` (`T/animation/show_time_test.clj:32, :50`). MTC comes from `mtc-test/feed` and `qf-bytes`.
  2. Step until the On report and deliver it, then apply Trace 1's edit and step again.
  3. There is no `[:show A]` in `:chase` or `positions`, and no reports.
- `laser-show.animation.show-time-test/looping-show-chases-without-looping-test`: a show with `:loop {:enabled? true :start 0.0 :duration 4.0}`, 16 beats at 120 BPM. MTC at 6.0 s puts it at beat 12.0, not 0.0; MTC at 8.0 s puts it outside its window.

**Subs and the status bar**
- `laser-show.subs-test/now-playing-examples-test`: §7.4.
- `laser-show.views.status-bar-test/playing-list-with-shows-test`, beside `playing-list-test` (`T/views/status_bar_test.clj:98`): `[[{:col 0 :row 0 :name "circle"}] [{:title "One More Time"}]]` gives `"A1 circle · ♪ One More Time"`.

### UI tests (`laser-show.ui-test.shows-tab-test`, with the `targeted-ui-testing` skill)

- `refused-tick-snaps-back-with-notice-test`: with two shows that would overlap, tick `#show-tc-<B>`. After `settle-ms` it is unticked again, and `#status-notice` contains "overlaps".
- `tc-problem-tooltip-test`: an unlocked show's ⚠ tooltip says "needs a BPM".
- `inspector-chase-refused-snaps-back-test`: in the editor, click `#timeline-chase-mtc` on an unlocked show. `#timeline-chase-off` is selected again, and `#timeline-tc-problem` is visible.
- `now-playing-follows-the-clock-test`, following `T/ui_test/timeline_editor_test.clj:292`: drive `show-clock/tick!` and watch `#now-playing-elapsed` change.
- `seek-release-moves-the-show-test`:
  - press on `#now-playing-seek` and drag; ticks during the drag don't move the thumb, and no `:seek` appears in the intent;
  - on release, the intent gets `:seek {:beat ≈ thumb}`;
  - a focus loss during the drag sends nothing.
- `space-in-table-toggles-show-not-transport-test`: focus the table and press Space. `[:show u]` gets `:paused?`, and `[:playback :playing?]` is unchanged.
- `enter-plays-selected-row-test`.
- `timecode-show-disables-transport-test`: with a chase marker for an enabled show, `#now-playing-tc-chip` is visible, and `#now-playing-seek`, `-prev`, `-next` and `-stop` are disabled.
- `held-chip-while-stopped-test`: play `u`, then `:transport/stop` → `#now-playing-held` is visible.

## 9. Out of scope / alternatives considered

### Corrections to `00`, `01` and `02` (this file wins)

- **`test.check`.** `02` S4 asks for a `test.check` property, but `test.check` isn't on the classpath. A seeded loop replaces it (§8).
- **`01` §4.3, merging the notice.** "Merged with the handler's effects" isn't enough, because `:dispatch-later` holds one value. §4.1 defines the merge.
- **`01` §4.3, New and Open.** It says nothing about them. The turn-off must skip a document replacement, or Open leaves the file unsaved (§4.2 step 3).
- **`01` §1, `invalid-timecode-ids`.** It returns `[id problem]` pairs, not bare ids.
- **`01` §3.1 and §4.2, TC end.** It is shown with `seconds->tc-ceil`, not `seconds->tc`.
- **`01` §4.1, the rule's edge cases.** It doesn't cover loops, float tolerance or the 24 h wrap. They are decided in §4.4 and §7.2.
- **`01` §5, `now-playing` after `:manual` has ended.** §4.7's order of choices settles it.
- **`01` §5, the held transport.** It doesn't say that `:shows/play` resumes a held transport, or that a held run restarts at beat 0 (§1.5, Trace 5).
- **`:timeline/seek`.** It lacks `unless-chasing` (`events/handlers/timeline.clj:607`). §4.6 adds it.
- **The timeline editor's UI tests.** `T/ui_test/timeline_editor_test.clj` has 28 deftests, not 7.
- **`:shows/duplicate`.** It must drop `:timeline/chase` (§4.5).

### Alternatives considered

- **Snap ranges to frames before comparing.** Rejected. Different frame rates would need a common base, and the chase compares raw seconds, so snapped checks would drift from what actually plays. The plan uses raw seconds, a 1 µs tolerance and a ceiling display instead.
- **Treat ranges modulo 24 h.** Rejected. It would refuse shows that the linear decoder never plays together. A chase that understands the wrap is separate decoder work.
- **Count a looping show's range as infinite, or refuse looping shows.** Rejected. The chase never loops (§1.4), so its range is what plays.
- **Add the notice through an inline `:dispatch` of a NEW `:ui/show-notice` event.** Rejected. That adds one more routed event for every turn-off, and it still clashes when the handler set its own notice in the same event.
- **Refuse the edit instead of turning timecode off.** That is `00` Q-1, already decided.
- **Use `gesture/slider` for the seek bar.** Rejected. It sends every value change as an undoable gesture edit, but seeking is one request on release, with no undo.
- **A rehearsal mode for enabled shows.** That is `00` Q-2: later.
