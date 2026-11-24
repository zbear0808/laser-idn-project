# 01 · Typed numeric commits

**Status:** Implemented in `225daa5` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit row: "Numeric Enter can make a show save successfully and reopen with no cues" (audit table "Where the UI or stored state can be wrong"). Reproduced at 7b320b3 by plans/probes/cljfx_state_outcomes_2026_11_23.clj, which printed `:numeric-round-trip {:stored "ActionEvent", :saved? true, :cue-count-before 1, :parsed-content? false, :cue-count-reopened 0, ...}`.

Behaviour at 7b320b3:
- views/components/modulator_param_control.clj, `modulator-param-control`: when the parameter holds an inactive modulator config (`has-inactive-modulator?`), the static-mode text field's `:on-action` is `{:event/type :modulator/update-static-value … :current-value current-value}`. cljfx supplies a JavaFX ActionEvent as `:fx/event`. events/handlers/modulator.clj `handle-update-static-value` put `(:fx/event event)` straight into the config's `:value`; the next render called `(double static-value)` → ClassCastException. The slider used the same handler with a numeric `:fx/event`, so it worked.
- views/components/effect_parameter_editor.clj: `on-text-event` is `:keyframe/update-param` when keyframes are enabled; events/handlers/keyframe.clj stored `(h/first-some (:value event) (:fx/event event))` → a text field's Enter put an ActionEvent into `[:keyframe-modulator :keyframes i :params k]`.
- The plain text path `:chain/update-param-from-text` → `handle-update-param-from-text` → events/helpers.clj `parse-and-clamp-from-text-event` already parsed/clamped text (reading the source node on the event thread).

Required outcome:
- A real Enter in the inactive-modulator static field and in a keyframed numeric field stores a finite number (clamped to the param's min/max; integer params per the existing rules). Invalid text ("abc", "", "NaN", "Infinity") changes nothing and the field shows the applied value again.
- Text is extracted on the FX thread and dispatched as plain data; handlers parse/validate. Defensive document boundary: `:modulator/update-static-value` and `:keyframe/update-param` never store a non-number for a numeric parameter, whatever the producer sends (an ActionEvent payload is ignored).
- Sliders still work; the Modulate-toggle undo regression (commit 7b320b3) keeps passing. One discrete text commit = one undo step.

Tests (NEW): UI tests in laser-show.ui-test.modulator-editor-test for Enter in the inactive-modulator field and in a keyframed field; handler tests for valid, out-of-range, invalid and ActionEvent payloads; a persistence round-trip (save to a temp ZIP, load, content readable, value numeric).

Boundaries: 06 (keyframe value/zone/interp listeners) and 07 (modulator subfield patches) follow in the same lane; 02 rejects unreadable show files; 13 restores refused native controls.

## As implemented

**Changes**
- `events/helpers.clj`: NEW `parse-number-text` (replaces `parse-and-clamp-from-text-event`): reads text like `Double/parseDouble`, rejects NaN/infinities/non-text, clamps to min/max, rounds `:int` params.
- `views/components/parameter_controls.clj`: NEW `number-field` built on `commit-field` (reads typed text on the FX thread, sends plain `:text`). Both `param-slider` and the static field in `modulator-param-control` use it (inactive-modulator, keyframe and plain routes). NEW ids: `param-<key>-field`, `param-<key>-slider`. `commit-field` gained an optional `:style` prop.
- Handlers: `:modulator/update-static-value` stores parsed `:text`, a finite slider number, or a keyword among the param's choices; anything else (incl. ActionEvent) → `{}`. `:keyframe/update-param` treats a param as numeric if registry type `:float`/`:int` (or no type); unknown params go by whether the keyframe currently holds a number; numeric params accept only finite numbers; never stores a JavaFX event; rejected → `{}`. `:chain/update-param-from-text` uses `:text`. NEW `param-spec` / `numeric-spec?` in `chain/params.clj`. The modulator subfield's `parse-text-value` uses the same parse rule.

**Behaviour changes**: all numeric param fields commit on Enter or focus loss; text that sets nothing is restored after ~300 ms; ⌘Z/⇧⌘Z in an unedited field is app Undo/Redo. Plain route: "NaN"/"Infinity" change nothing; `:int` "2.6" stores 3.

**Deferred**: zone/colour keyframe siblings untouched (colour handlers still read the ColorPicker off the FX thread). Modulator subfield, palette and text fields still send an ActionEvent the handler reads (→ 07).

**For 06**: a spinner using `:value :fx/event` passes as long as finite. Still open: nil `:keyframe-idx` throws in `handle-update-keyframe-param`; `:keyframe/set-zone-value` and `:keyframe/set-interpolation` untouched.
**For 07**: `static-value` in modulator.clj is the payload rule to keep. The static field's event also carries `:min :max :is-int? :text` and still `:current-value`; the slider's event is unchanged. The handler still answers with a `:dispatch` of `:chain/update-param`. Convert subfield text fields with `param-controls/number-field` reading `(:text event)`; `parse-text-value` already uses the shared rule.
**For 16**: sliders now have ids; transaction wiring untouched.
**Probe**: numeric step prints `{:probe-error "Key must be integer"}` (field no longer has `:on-action`), expected.
