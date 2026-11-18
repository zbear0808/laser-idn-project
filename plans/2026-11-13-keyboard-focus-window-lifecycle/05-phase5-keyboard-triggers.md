# Phase 5 — Keyboard as a cue-trigger source

**Written:** 2026-11-13   **Written for commit:** `cb32414` (`master`)
**Status:** Proposed. Needs D10–D12 answered. Depends on Phases 2 and 3   **Last verified against:** `cb32414`
**Supersedes / Superseded by:** none (replaces the "keyboard trigger source" amendment in `plans/2026-10-07-keyboard-shortcuts-and-focus-consolidation.md`)   **Related findings:** K-13, K-15 (`00-overview.md` §3); review 05 F17, L3, F9 (learn also fires the old binding)

The README promises keyboard input (`README.md:3`, `:51`), the trigger editor already shows keyboard bindings (`trigger_bank.clj:62`), and every stage after the input router is source-agnostic. Only the producer is missing: nothing turns a JavaFX key press into a router event. About 1–1.5 days, mostly design.

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| Producer of keyboard events | grep `router/dispatch!`, `events/trigger`, `:keyboard` in `src/` | **Not found.** `router/dispatch!` is called only for the gamepad (`app.clj:167`) and from the MIDI/OSC connect handlers (`handlers/input.clj:61, 120, 146`); `events/trigger` is used only by gamepad and OSC (`input/gamepad.clj:62`, `input/osc.clj:104, 115`). |
| Event constructor | `input/events.clj:58-68` | `(trigger :keyboard :space :pressed)` → `{:type :trigger :source :keyboard :id :space :state :pressed :timestamp ms}`; `:released` gives `:type :trigger-release`. |
| Router | `input/router.clj:103-128` | `dispatch!` runs synchronously on the caller's thread: all global handlers, then pattern handlers. There is no way for a handler to consume an event. |
| Mapper | `events/handlers/input_mapper.clj:35-91`; started by `app.clj:163`, **not** by `start-test!` (`app.clj:201-218`) | Discrete events (`trigger`, `trigger-release`, note on/off) become `{:event/type :trigger/input :input-event e :time ts}` through `*dispatch-fn` (`:73-76`). There is no source filter. |
| Resolution | `events/handlers/triggers.clj:13-23` (`lookup-key`), `:59-89` (`action-event`), `:116-124` (`handle-input`) | Key is `{:source :keyboard :id :space}`. Press runs the bound action; `:behavior :momentary` runs `:grid/activate-cell` on press and `:grid/release-cell` on release (`:43-57`; `grid.clj:97-109`). Unmapped input only updates `[:trigger-runtime …]`. |
| Learn | `handlers/input.clj:223-246` (start), `:248-256` (`learn-lookup-key`), `:258-270` (captured) | A global router handler captures the next `trigger` or `note-on` of **any** source and binds it. Proven end-to-end with a synthetic keyboard event by `trigger_editor_test.clj:34-74`. |
| Learn also fires the old binding | the learn handler and the mapper are both global handlers (`handlers/input.clj:232-238`, `input_mapper.clj:85`) | The captured press is also dispatched as `:trigger/input` and resolved against the trigger map **before** the new binding is written (the binding arrives later through the learn effect's `future`, `core.clj:206-215`). Re-binding a key therefore fires its old action once. |
| Display | `trigger_bank.clj:57-63` | `:keyboard` renders as `"Key " + (:id key)`, e.g. `Key :space`. |
| Placeholder state | `domains.clj:425-426` | `[:backend :input :keyboard {:enabled? true :attached-components #{}}]`. Nothing reads it. |
| Settings | `views/tabs/settings.clj:16-34`, mounted `:89-90` | "Keyboard shortcuts and hotkey configuration coming soon…" placeholder. |
| Key repeat | JavaFX `KeyEvent` has getters `getCharacter getCode getEventType getText` and `isAltDown isControlDown isMetaDown isShiftDown isShortcutDown`; no repeat flag (checked on this classpath) | A held key delivers repeated `KEY_PRESSED` without a release in between. The producer has to remember which keys are down. |
| Key collisions | `title_bar.clj:143, 152, 157, 163` | Delete, Space, Escape and R are built-in accelerators. |

## 2. Root cause

Keyboard was designed as an input source (README, the `:keyboard` placeholder, the trigger-bank label) but only the receiving half was built. The producer needs a scene-level key hook, which did not exist until Phases 2–3 give every window one.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `input-events/trigger` (`events.clj:58-68`) | The only event constructor needed. |
| `router/dispatch!`, the mapper, `:trigger/input`, `triggers/handle-input`, the trigger map `[:config :input :trigger-map]` | Unchanged. This is the point of the phase. |
| Learn (`handlers/input.clj:223-270`) and the trigger editor (`cell_trigger_editor.clj`, `trigger_bank.clj`) | Unchanged, apart from the learn-suppression fix below. |
| Phase 2 dialog shell | Installs the filter in every dialog scene. |
| Phase 3 `laser-show.views.keys/typing-target?` | The one definition of "the user is typing". |
| `:input/gamepad-enable` / `-disable` (`handlers/input.clj:206-210, 351-352`) | Pattern for `:input/keyboard-enable` / `-disable`. |
| The Settings tab placeholder (`settings.clj:16-34`) | Becomes the keyboard section. |
| TestFX: `ui/press!`, `ui/focus!`, `ui/wait-for!`, `fixtures/with-app` | Tests; the mapper must be started explicitly (see §8). |

## 4. Changes per namespace

- `laser-show.input.keyboard` (NEW, pure, no JavaFX): the whole policy as data in, data out.
  - `key-id [key-code-name]` → keyword: `"SPACE"` → `:space`, `"DIGIT1"` → `:digit1`, `"F5"` → `:f5`.
  - `decide [ctx]` → `{:consume? bool :emit :pressed|:released|nil}`; `ctx` = `{:key-id :phase :text-target? :modifiers :held? :bound? :learning? :enabled? :reserved?}` (table in §7).
  - `label [key-id]` → `"Space"`, `"1"`, `"F5"`. `builtin-shortcut [key-id]` → `{:label "Play/Pause"}` or nil, for the override note (D10).
- `laser-show.views.key-triggers` (NEW): the JavaFX glue, a scene-level key **filter** (first pass, so it sees the key before any control does).
  - On `KEY_PRESSED` / `KEY_RELEASED` it builds `ctx` from the event, the window's held-key set and `state/get-raw-state` (enabled flag, trigger map, learning target), calls `decide`, consumes if told to, and calls `router/dispatch!` with `(events/trigger :keyboard id state)`.
  - A held-key set per window (view-local atom) swallows repeated presses. A window-focus-lost listener releases every held key, so a momentary cue cannot stick.
  - Installed through `fx/ext-on-instance-lifecycle` around the scene, from the Phase 2 shell and from `root.clj:170-172`.
- `laser-show.events.handlers.input-mapper` (`input_mapper.clj:35-78`): while `[:backend :input :learning-target]` is set, discrete events are not dispatched as `:trigger/input`. Fixes the "learn also fires the old binding" defect for MIDI, OSC and keyboard alike.
- `laser-show.events.handlers.input` (`input.clj:206-210, 351-352`): NEW `:input/keyboard-enable` / `-disable` writing `[:backend :input :keyboard :enabled?]`.
- `laser-show.views.components.trigger-bank` (`trigger_bank.clj:62`): label through `keyboard/label`; shows "Overrides built-in shortcut: Play/Pause" when `builtin-shortcut` is non-nil (D10 option A).
- `laser-show.views.tabs.settings` (`settings.clj:16-34, 89-90`): replace the placeholder with an enable checkbox and the list of reserved keys.
- `laser-show.state.domains` (`domains.clj:425-426`): drop the unused `:attached-components`.

Step 0 (a 15-minute spike before the rest): a TestFX test that a filter installed through `ext-on-instance-lifecycle` sees `KEY_PRESSED` first and that consuming it stops `Space` from reaching the `MenuItem` accelerator (`title_bar.clj:152`). Everything below assumes that.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| held keys per window | view-local atom, FX thread only | the filter | n/a. Transient input state, never read elsewhere |
| `[:backend :input :keyboard :enabled?]` (existing placeholder) | app state, session only (like the gamepad flag; `[:backend …]` is not saved, `persistent.clj:48-61`) | `:input/keyboard-enable` / `-disable` | No / no |
| `[:config :input :trigger-map {:source :keyboard :id …}]` (existing) | document (saved per project, like MIDI and OSC bindings) | learn (`:input/learn-captured`, undo label "Learn Trigger") | Yes / yes |
| `[:backend :input :learning-target]` (existing) | app state | learn handlers | No / no |

The filter reads app state with `get-raw-state`. It never writes app state and never subscribes.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| router event `(trigger :keyboard id :pressed|:released)` | constructor exists | the key filter, on the FX thread | n/a (router) |
| `:trigger/input` | yes | the mapper | yes |
| `:input/start-learn`, `:input/cancel-learn`, `:input/learn-captured` | yes | trigger editor; Escape while learning (filter) | yes |
| `:input/keyboard-enable`, `:input/keyboard-disable` | NEW | Settings checkbox | yes |

No new effect keys.

## 7. Data examples and invariants

Key ids: `SPACE` → `:space`, `A` → `:a`, `DIGIT1` → `:digit1`, `NUMPAD3` → `:numpad3`, `F5` → `:f5`, `LEFT` → `:left`. Trigger-map entry: `{:source :keyboard :id :space} → {:type :trigger-cue :target [0 0] :behavior :momentary}`.

Decision table for one key event (with D10 = A, D11 = A, D12 = A):

| Situation | Consume? | Router event |
|---|---|---|
| keyboard triggers disabled | no | none |
| target is a text input | no | none |
| Ctrl/Cmd/Alt/Meta held | no | none (reserved for commands) |
| `:escape` | no | none, unless learning, where it cancels learn |
| learning, any other key | yes | `:pressed` (learn captures; the mapper ignores it) |
| key already held (auto-repeat press) | yes if the key is bound, else no | none |
| bound key, press | yes | `:pressed` |
| bound key, release | yes | `:released` |
| unbound key | no | none |
| window loses focus with keys held | n/a | `:released` for each held key |

Invariants:
1. A key that is not bound is never consumed, so every control and menu accelerator behaves exactly as before.
2. Typing in a text field never triggers a cue.
3. A momentary cue always gets its release, including when the window loses focus.
4. Escape keeps its built-in meaning and cannot be bound.
5. Learning a key never fires that key's previous binding.
6. A bound key produces exactly one `:pressed` per physical press, whatever the OS auto-repeat does.

## 8. Tests (named test vars + fixtures)

Pure, `laser-show.input.keyboard-test` (NEW): `key-id-test`, `label-test`, `decide-table-test` (one assertion per row of §7), `builtin-shortcut-test`.

Handlers:
- `laser-show.events.input-mapper-test` (NEW; pattern of `input/osc_test.clj:40-60`): `mapper-ignores-discrete-events-while-learning-test`.
- `laser-show.events.handlers.input-test` (extend): `keyboard-enable-disable-test`.

UI, `laser-show.ui-test.keyboard-trigger-test` (NEW; `with-toolkit` once, `with-app` each, plus a fixture that calls `input-mapper/set-dispatch-fn!` with `events/dispatch!` and `start-mapper!`, and `stop-mapper!` afterwards, because `start-test!` does not start it):
- `bound-key-triggers-its-cue-test` (bind `:digit1` → cell `[0 0]`; press it with focus on `#main-layout`; wait for `[:playback :active-cues [0 0]]`)
- `unbound-key-passes-through-test`
- `text-field-focus-blocks-triggers-test` (`#bpm-input` focused)
- `momentary-binding-releases-on-key-up-test`
- `repeated-press-emits-one-trigger-test` (fires two `KEY_PRESSED` events without a release; the headless robot cannot auto-repeat)
- `learn-captures-a-key-without-firing-the-old-binding-test`, `escape-cancels-learn-test`
- `bound-space-overrides-play-pause-test` (D10 A) and `escape-always-stops-test`

Manual (unverified): focus-loss release on a real OS window, and a held key on macOS.

Run: `clj -M:mac-arm:test keyboard input-mapper input`, then `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.keyboard-trigger-test`, then both full suites.

## 9. Out of scope / alternatives considered

- **Key combinations and chords** as bindings. Not requested; D12 B is the hook.
- **Physical key positions** (a grid of keys that matches the grid of cells regardless of layout). JavaFX exposes the logical key code only, so bindings follow the keyboard layout. Documented, not solved.
- **Global hotkeys** while another application has focus. Needs a native hook; out of scope. Keyboard triggers work only while a Laser Show window has focus.
- **Per-binding `Accelerator`s** (`Scene.getAccelerators`). Rejected: they never see key releases (momentary needs them) and they are skipped when a control consumes the key.
- **User-configurable built-in shortcuts** (rebinding Play/Pause itself). The registry idea from the old plan; revisit separately.

---

## Decisions

> **DECISION D10 — When a keyboard binding collides with a built-in shortcut** *(open · needed before Phase 5 · recommended: A)*
>
> Space (play/pause), Delete (clear cell), R (retrigger) and Escape (stop) are built in (`title_bar.clj:143-163`).
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | The user's binding wins, except Escape, which is reserved and cannot be bound (a stop key that can't be hijacked). The learn panel notes "Overrides built-in shortcut: Play/Pause" | A learned key always does what the user taught it | A bound Space no longer plays/pauses |
> | B | Built-ins win; learning a colliding key is refused with a message | Nothing built-in ever changes | The most natural keys (Space, R) can't be used |
> | C | A "performance mode" toggle: on = bindings win and built-ins (except Escape) are off; off = built-ins win | Explicit and visible | A new mode to remember and to show in the UI |
>
> *Audit note (2026-11-17), for whichever option:* probe p2 shows a focused text field swallowing Space, R and Delete before the menu accelerators see them. Escape wasn't probed; if it behaves the same, Escape stops nothing while someone is typing, for example in `#bpm-input`. Add Escape to p2 before deciding. If Escape is the show's panic key, the Phase 5 filter, which sees every key first, could fire Stop for Escape whatever has focus; the cost is that Escape in a text field (cancel the edit) would then also stop the show. Stop also goes through the event agent (`:transport/stop`), so it waits behind a save until the agent's I/O moves off it (`plans/2026-11-07-improvement-review/00-overview.md`, "Next").
>
> **Your answer:** _pending_

> **DECISION D11 — Where do keyboard triggers work, and is there a switch?** *(open · needed before Phase 5 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | Main window and every editor dialog, never inside a text input, never in the unsaved-changes confirm; a master switch in Settings > Keyboard (default on; with no bindings nothing is intercepted anyway) | You can fire cues while editing in a dialog; matches how MIDI works | A key bound to Delete would fire in a dialog while a list also wants Delete (the binding wins, per D10) |
> | B | Main window only | Simplest; no overlap with dialogs' own keys | Can't trigger from the timeline editor while it is focused |
> | C | A, but dialogs are opt-in per dialog | Fine control | More configuration surface |
>
> **Your answer:** _pending_

> **DECISION D12 — What is a key's identity?** *(open · needed before Phase 5 · recommended: A)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | The `KeyCode` name only (`:space`, `:a`, `:digit1`, `:f5`). Ctrl/Cmd/Alt/Meta held means "not a trigger" (reserved for commands). Shift is ignored. Follows the logical keyboard layout | Simple labels and learn; no collision with command shortcuts | No chords; layout-dependent |
> | B | Include modifiers in the identity (`:shift+a`, `:ctrl+1`) | Many more bindings | Collides with command shortcuts; more labelling and learn work |
> | C | Use the typed character instead of the key code | Layout-independent for printable keys | Unstable for non-printable keys; case and dead-key problems |
>
> **Your answer:** _pending_
