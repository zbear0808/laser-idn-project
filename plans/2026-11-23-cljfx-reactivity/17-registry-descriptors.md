# 17 · Registry descriptors

**Status:** Implemented in `3e2bd4a5` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit: "Other concrete places to apply those fixes" bullet on the effect bank; subscription auditor finding 5 (reaction selectors and the mutable modulator registry).

Observed (at a7e414e):
- views/components/effect_bank.clj `get-effects-by-category` caches category maps in a namespace atom keyed by the effect registry's COUNT (`(count @effects/!effect-registry)`). Re-registering an existing id with a new name/default keeps the count, so even an explicit re-render shows the old definition. The bank button's event template carries the definition (`:item` effect-def), and events/handlers/effects.clj (the "add from bank" handler, ~150–157) uses the carried definition's defaults. Probe: default changed 1 → 7, clicking the old bank entry still added 1.
- Reaction selectors in subs.clj (`vibe-reactions`, `vibe-edited-reactions`) call `vibes.reactions/reactions`, which reads categories/default sources from the mutable modulator registry (animation/modulator_registry.clj) outside the cljfx context: re-registering a modulator with a different default source left a warm context showing the old result; a fresh context showed the new one.
- Registries change at namespace load and on dev code reload (`laser-show.dev.reload/reload-app-code!` → `:dev/reloaded {:kind :app}`). Precedent for publishing runtime descriptors as data: output drivers (`:addons/loaded` puts driver descriptors in app state; `subs.clj` reads them).

Required:
- Remove the count-keyed render-time cache. Make registry-derived UI data depend on something in the context that changes whenever the registries change after start (e.g. publish immutable effect/modulator descriptors or a registry revision into app state at start and on `:dev/reloaded`, through events; subscriptions read it). Choose the smallest correct design and document it.
- The bank add event carries the effect id; the handler resolves the current definition/defaults at handling time (`effects/get-effect`), ignoring unknown ids.
- Reaction selectors invalidate when modulator descriptors change (same mechanism).
- No functions or Java objects in saved state; nothing new in the document/undo.

Tests (NEW, names adaptable): re-register an existing effect id with the same count but a different name/default → the bank's category data and an add both use the new definition (unit: subscription on a real context + handler; UI optional); a stale bank event for an unknown id adds nothing; a modulator re-registration with a new default source changes `vibe-reactions` after the publication event. Keep effect bank/tabbed bank/timeline browser UI tests and effects handler tests passing.

Boundaries: 38 (drag payload lifetime, another lane) changes tabbed_bank drag start/done; 11 (another lane) edits other handlers in events/handlers/effects.clj — keep your edits to the bank-add handler.

## As implemented

**Design**: publish registries as plain data. NEW `laser-show.animation.registries` builds a fn-free copy of every effect and modulator type; NEW `:registries/published` event stores it under `[:backend :registries]` (never saved/undone). `app/start!` and `app/start-test!` dispatch it via the app's dispatcher right after `:addons/loaded`; `dev.reload/reload-app-code!` dispatches it after reloading. Bank builds tabs from the published copy (NEW `subs/effect-bank-categories`). `vibe-reactions`, `lint-world`, `edited-reactions-world` include published modulator types (helper `with-modulators`) → rerun once a changed type is published (`vibes.reactions` still reads the live registry; the published copy mirrors it).

**Changes**: NEW `animation/registries.clj`, `events/handlers/registries.clj` (routed via "registries" in `events/handlers.clj`); `state/domains.clj` default `[:backend :registries] {:effects {} :modulators {}}`; `subs.clj` `effect-descriptors`, `modulator-descriptors`, `effect-bank-categories`, `with-modulators` + three reaction inputs; `effect_bank.clj` count-keyed cache, builder and unused default-params helper removed; bank-add handlers in `effects.clj` (`handle-effect-chain-add-effect-from-bank` + one require), `cue_chain.clj`, `timeline.clj` look up defaults by id with `effects/get-default-params` (unknown id → `{:state state}`, since `history-labels-name-real-events-test` treats `{}` as unrouted); `tabbed_bank.clj` three hunks (`:on-action` `{:item-id item-id}`; two docstring lines), none in drag start/done; comment lines in cue/effect chain editors.

**Tests**: NEW `registries-handlers-test`, `effect-bank-follows-published-effects-test`, `tap-reactions-follow-published-modulators-test`, bank-add tests (effects, cue-chain), UI `a-re-registered-effect-reaches-an-open-bank-test` (also checks start-test! published). Updated `add-effect-to-track-test`, `bank-click-event-unchanged-test`. Not run by the agent: `addons_settings_test/start-loads-addons-before-the-scan-test` (calls `app/start!`; integrator's full UI suite runs it).

**Notes**: bank clicks no longer carry `:item` (presets incl.; `handle-cue-chain-add-preset` docstring still says it "optionally accepts :item"). Still read the registry at render time (refresh on code reload only): `modulator_param_control.clj`, projectors tab calibration list, chain editors' and device chain's `effects/get-effect` calls — follow-up candidates. Registering effects/modulators after startup: dispatch `(registries/published-event)` afterwards.
