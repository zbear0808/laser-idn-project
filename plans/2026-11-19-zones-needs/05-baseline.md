# Baseline: what the code does today

This doc is a short reference. The full survey is in `plans/2026-11-18-zones-ux/00-overview.md` §2. The ✔ items were checked by hand against `67735d0`; the others come from the code survey.

## Model and routing
- ✔ Each projector has `:zone-groups [ids]`, and new projectors start with `[:all]` (`events/handlers/projector.clj:79-103`).
- There are six default groups, `:all :left :right :center :graphics :crowd`, each `{:id :name :description :color}` (`state/domains.clj:340-389`).
- ✔ `:all` is only a name. A projector gets a group's content only if it lists that group (`backend/multi_engine.clj:47-73`).
- Each item resolves to one group, in this order (`routing/zone_effects.clj:127-156`):
  1. the item's `:zone-group-id`;
  2. a Zone Selector effect;
  3. the track, then its folders;
  4. the parent group;
  5. the cue's `:destination-zone`;
  6. `:all`.
- Each cue renders once per tick to `{group → frame}`, and the effects grid then runs on every group's frame (`services/frame_service.clj:338-405`).
- ✔ A projector joins the frames of every group it lists, cue by cue, then applies its calibration chain (`backend/multi_engine.clj:119-150`).
- Virtual projectors have state, subscriptions and a listing, but no create handler, and no engine reads them (`backend/multi_engine.clj:212-227`, `backend/output_runtime.clj:41-50`).
- Persistence: `hardware.edn` (projectors, virtual projectors, groups) is inside the project zip, and New Project wipes it (`state/persistent.clj:48-61, 176-185`).

## Bugs and gaps to fix whatever design we pick

| # | Problem | Where |
|---|---|---|
| B-1 | ✔ No safety zones. The tab subtitle claims they exist | `views/tabs/projectors.clj:826` |
| B-2 | Calibration / test pattern outputs nothing | `events/handlers/projector.clj:369-375`, `services/frame_service.clj:25-28` |
| B-3 | Routing ignores keyframes and modulators on `:target-zone` (`timing-ctx` is passed but never used) | `routing/zone_effects.clj:127-156` |
| B-4 | A Zone Selector on the effects grid does nothing (global effects run after routing) | `services/frame_service.clj:397-405` |
| B-5 | Deleting a group: no confirmation, `:all` can be deleted, cue/track/preview references are left dangling | `events/handlers/zone_groups.clj:43-73` |
| B-6 | Zone Groups tab: clicking the row and clicking the checkbox both dispatch the toggle, so it may toggle twice (not checked) | `views/tabs/zones.clj:164-179` |
| B-7 | "Cues targeting" always reads 0 | `subs.clj:685` |
| B-8 | The Axis Transform parameters can't be edited (the handler is a stub) | `events/handlers/projector.clj:350-361` |
| B-9 | "All" is listed twice in the Zone Selector picker | `views/components/custom_param_renderers.clj:505-506` |
| B-10 | Projectors can't be deleted, and there's no tag editor (the vibe audience rules need `:crowd-scanning`) | `events/handlers/projector.clj:12`, `vibes/library.clj:59-94` |
| B-11 | Group IDs come from milliseconds and can collide | `events/handlers/zone_groups.clj:33`, review 02 |
| B-12 | A failing calibration effect lets the frame through (fails open) | `backend/multi_engine.clj:104-110` |

## Tests that exist

Precedence (`test/laser_show/routing/zone_effects_test.clj`), end-to-end zone routing (`test/laser_show/routing/zone_routing_integration_test.clj`), extracting and joining zones in the engine (`test/laser_show/backend/multi_engine_test.clj:194-307`), group CRUD, the projector handlers, hardware persistence, and UI tests for the setup dialogs and the preview zone picker.

**Gaps:** nothing tests keyframed zones, the Zone Groups tab's clicks, delete cleanup, virtual-projector output, or calibration output.
