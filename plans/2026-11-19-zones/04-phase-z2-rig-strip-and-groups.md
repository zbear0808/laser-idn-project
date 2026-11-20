# Phase Z2: the rig strip, stable identity, automatic groups, implicit All, the two tabs

**Written:** 2026-11-19   **Written for commit:** `4fa9eee` (`master`). First written at `90e35c7`. Re-checked at `4fa9eee`, after vibe Phases 3 (`38f92ad`), 4a and 4b (`6715fb4`, `f5a4f84`), 5 (`9f5d78a`, fixed in `0faa18c`) and 6 (`4a4a10a`), and UX Phase 3 (`b9b9d55`). Of the files cited here, vibe Phase 6 moved lines only in `state/domains.clj`, `subs.clj` and `events/core.clj`. Every line below is at `4fa9eee`. Vibe Phase 7 (in a worktree) and Z0 (in a worktree, `zones-z0`) are in progress: see §11. **Re-check at HEAD before starting.**
**Status:** **Done** (`5e39fd5`..`af2979d`, on branch `zones-z2`, built in a worktree off `989c315` alongside Z1 and vibe Phase 7; not merged yet). The as-built note is §13.   **Needs:** Z0 (`02-phase-z0-fixups.md` §4): the reference walker `laser-show.routing.refs` (`ref-places`, `cues-using`, `replace-refs`), the delete that asks first and re-targets (`:zone-groups/request-remove`, the confirm dialog `views/dialogs/confirm.clj`), the real cue count, `unique-group-id`, "All" listed once, and `:projectors/remove`. B-6 needed no fix: a click already toggles once. Independent of Z1: whichever lands first, Z1's SAFETY ZONE section, its TEST PATTERN row and Identify end up in Z2's inspector (§7). Z3 builds on it.
**Implements spec:** §1 (the refs `:all`, `:auto/*`, `:auto.laser/*`, custom groups; projector identity), §3 (automatic groups, as data with regions), §11 (the rows for `:all` membership, the built-in `:left` `:right` `:center`, and no `[:rig :order]`), §12 (the rig rows). Decisions D-7, D-13, D-15, D-17, D-20, D-21, D-22, D-23.   **Size:** ~5 d

After this phase you can:

- **Put your lasers in order.** A scan puts each output on a card, left to right by IP. Drag a card, or select it and press ◀ ▶ (Alt+← / Alt+→), until the cards hang the way the lasers do.
- **See the groups appear.** Under the cards: All, Left, Right, Center, Odd, Even and one group per laser, locked and coloured, folded from the order (spec §3). With one laser, Left and Right are its two halves. With 3 or 5, the middle laser is split by default.
- **Stop ticking All.** Every enabled laser plays All. "Exclude from All" keeps an audience scanner out.
- **Aim cues at the rig already.** Every zone picker lists Left, Right, Center, Odd, Even and each laser, and cues play there. Until Z3, each laser draws its share **whole** (no halves yet).
- **Keep a laser's setup when its IP changes.** A scan recognises the unit and output, moves it to the new address and says so.
- **Mark an audience scanner.** "Scans the audience" is a tick box. Vibes then treat every cue that can reach that laser as an audience cue.
- **Edit groups from the group's side.** The Zone Groups tab lists All, the automatic groups, each laser and your groups. Pick one and its lasers show in rig order: ticked for your groups, explained for the automatic ones. Both tabs change the same data with the same events (D-15).

## 1. Current behaviour (file:line)

| What | Where | Behaviour |
|---|---|---|
| A projector's id | `events/handlers/projector.clj:66-73` (`make-projector-id`); hand-added `:231` | `:projector-10-0-0-5-1`, built from the host and the service id. Hand-added ones get `:projector-<ms>` |
| A new projector | `:79-103` (`make-projector-config`, default `:zone-groups [:all]` at `:90`); scan `:117-144` | stores `:unit-id` (`:97`, `:139`) and never reads it again. A discovered output starts disabled (`:140`). A scan creates a projector only if its id is new (`:128`) |
| What a scan reports | `events/core.clj:225-263` (device shape `:238-251`); `idn/hello.clj:359-384`, `:391-416` | per device: the address, a 20-byte host name, a **16-byte unit id** as text `"0A 1B …"` (`bytes-to-hex`, `idn/hello.clj:54-57`), status flags, and a service map (service id 1–255, unique per device, a name, a relay number) |
| Discovery and undo | `events/handlers/projector.clj:159-186` | new projectors are backfilled into undo and the saved baseline (`:182-186`): never undone, never unsaved |
| An IP change | the same | the id comes from the IP, so the laser returns as a **new, disabled** projector without its calibration, groups or place (needs/08 P-6). A different unit that later gets the old IP takes the old id and its setup (`:128`) |
| Enabling | `:201-210` (Enable All by IP), `:213-220` (one output, found again with `make-projector-id`), `:292-297` (toggle) | three paths. The discovery list's checkboxes use the second |
| What "enabled" means | `backend/multi_engine.clj:220`, `backend/output_runtime.clj:48`; `subs.clj:547-557` | the engines treat a missing `:enabled?` as on, `(:enabled? p true)`. The discovery sub (`enabled-projector-services`) and the configured list's box (`views/tabs/projectors.clj:277`) treat it as off |
| The Projectors tab | `views/tabs/projectors.clj:789-807` | three columns: Discover devices `:198-249` (device rows `:76-196`), Configured projectors `:317-345` (rows `:268-315`, sorted by service id only, `subs.clj:509-517`), and the config panel `:684-783`: the fields `:351-440`, the zone chips `:446-475`, the calibration chain and its editors `:478-639`, the calibration controls `:641-682`. The subtitle claims safety zones (`:799-801`, B-1). **Z0** gives the subtitle the id `#projectors-subtitle` and drops the claim, and puts Remove… (`#projector-remove-button`) at the bottom of the config panel. **Z1** adds a SAFETY ZONE section above the calibration controls, renames them TEST PATTERN with an Identify button (`#projector-identify-button`), and puts the claim back |
| The Zone Groups tab | `views/tabs/zones.clj:327-349` | a group list `:89-130`, every output with a checkbox `:136-235` (row ids `#zone-output-<id>`, `:160`; the row and the box both dispatch the toggle, `:166-181`, and still toggle once: B-6, checked in Z0), details `:241-321` with "Cues targeting: 0" (`subs.clj:716-726`, B-7, made real by Z0). The subtitle `#zones-subtitle` (`:338-341`) says a click adds a projector (UX Phase 3). Tags are shown read-only (`:41-57`); nothing edits them (B-10) |
| Routing | `backend/multi_engine.clj:47-59`, `:119-150` | a projector draws the frames of the groups it **lists**, joined in list order (`:133-137`). `:all` is only a name: a projector that doesn't list it never draws it |
| The groups | `state/domains.clj:345-394` | six stored defaults, `:all :left :right :center :graphics :crowd`. The preview's default cells name `:left :right :center` (`:254-257`) |
| The pickers | `subs.clj:674-679` (`zone-groups-list`), `:208-211` (`zone-groups-by-id`) | every zone picker lists the stored groups: the cue chain editor (`views/dialogs/cue_chain_editor.clj:476-477`, dropdown `:95-131`, labels and the Assign Zone menu `:145-231`, the item's picker `:233-272`), the timeline editor (`views/dialogs/timeline_cue_editor.clj:97, 122`), the preview (`views/components/preview.clj:230, 301`), the Zone Selector (`views/components/visual_editors/custom_param_renderers.clj:503-508`), keyframes (`views/components/visual_editors/keyframe_modulator_panel.clj:402, 578`). The Zone Selector and the keyframe value editor put their own All in front (`custom_param_renderers.clj:505-506`, `keyframe_modulator_panel.clj:420-423`; B-9, which Z0 fixes) |
| Saving | `state/persistent.clj:48-61` | `hardware.edn` holds projectors, virtual projectors and groups (`:53-56`); calibration chains are in `content.edn` (`:61`). Load `:179-191` (beside `model/normalize-timeline-cues`, `:189`); New `:193-203` |
| Undo | `state/history.clj:48-64`, `:153-159`, `:262-278` | document roots `[:config :timing :grid :projectors :virtual-projectors :zone-groups :chains]`. A projector edit is undoable and marks the show unsaved |
| The audience rule | `vibes/library.clj:72-87` (`scanners`, `audience-groups`) | `:crowd` plus every group a `:crowd-scanning` projector **lists**. A crowd laser listing `:all` makes every All cue an audience cue (`test/laser_show/vibes/library_test.clj:64-65`). Since vibe Phase 3 the Vibes tab reads it twice more: the picker's audience mark (`subs/vibe-audience-groups`, `subs.clj:1145-1150`, given only the projectors and virtual projectors; `vibe-cell-blocked`, `:1160-1165`), and the lint's "A6 reaches the audience through All (P3)" (`audience-text`, `vibes/library.clj:405-416`), which names the group from `[:zone-groups]` and finds the scanner by its stored list (`library_test.clj:278-284`) |
| Virtual projectors | `state/domains.clj:306-321`; `views/tabs/zones.clj:191-209` | listed with toggles. No create handler, no engine (`backend/multi_engine.clj:212-227`) |
| Drag and drop | `views/components/drag_drop_cell.clj:78-118` | `drag-content`, `start-drag!`, `has-payload?` and `read-payload` work for any drag type. Headless tests drop with `fire-drop!` (`test/laser_show/ui_test/testfx.clj:404-436`) |

## 2. Why / root cause

- The model has **no order**, so a projector has no place. Left, Right, Center, Odd and Even can only be built by hand, and All is a tick box like any other group.
- **Identity is the IP.** The unit id is read and stored but never used to match, so an address change orphans the whole setup.
- **Membership lives on each projector** (`:zone-groups`), but it is edited in two unrelated lists, and neither shows where the lasers are.
- The audience rule reads the stored lists, so it can only be as good as those lists.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `:zone-groups` on each projector | stays the one record of **custom** membership (spec §12). All and the automatic groups are never stored in it |
| `:projectors/toggle-zone-group` (`events/handlers/projector.clj:305-314`). It already toggles once per click (B-6, checked in Z0: no fix needed) | the one membership event, from the rows under the strip and from the Zone Groups tab (D-15) |
| `make-projector-id` (`:66-73`) | still makes the id of a *new* projector, made unique by NEW `unique-projector-id` |
| The scan's backfill (`:182-186`) | also carries host moves and identity upgrades, so they are never undone and never unsaved |
| `extract-frames-for-zones`, `combine-zone-frames` (`backend/multi_engine.clj:61-88`) | unchanged. Only the list they get changes (`get-projector-zone-groups`) |
| `projector-basic-settings` (`views/tabs/projectors.clj:351-440`, its fields on `views/components/commit_field.clj:7-40`), the calibration chain and its editors (`:478-639`), the calibration controls (`:641-682`) and the config panel (`:684-783`), as Z0 and Z1 leave them | become the inspector, keeping their node ids (`#projector-config-title`, `#projector-name-field`, `#projector-host-field`, `#projector-port-label`, `#projector-port-field`, `#projector-scan-rate-field`, `#projector-calibration-button`, `#calibration-brightness-slider`, `#calibration-brightness-label`, and Z1's `#projector-identify-button` and Z0's `#projector-remove-button`), so `setup-dialogs-test`, `streaming-status-test` and Z0's and Z1's UI tests keep working |
| Z1's SAFETY ZONE section, TEST PATTERN row and Identify (03 §4, §7) | in the inspector, the SAFETY ZONE above the TEST PATTERN row, as 03 §7 draws them. Identify is also on a card's menu, as 03 §6 expects |
| Z0's `:projectors/request-remove` and `:projectors/remove` (02 §4) | Remove… at the bottom of the inspector. Z2 makes the remove also clear the laser's place in the order and its pins (§4) |
| `status-indicator`, `output-status-text` (`views/tabs/projectors.clj:29-43`, `:255-266`), `subs/projector-output-status` (`subs.clj:395-399`) | the card's dot and status line, with the same ids `#projector-dot-<id>` and `#projector-status-<id>` (`test/laser_show/ui_test/streaming_status_test.clj:97-115`). The two functions move into `rig_strip.clj` (§4) |
| The drag-and-drop helpers (`views/components/drag_drop_cell.clj:78-118`) | a NEW drag type `:rig-card` with payload `{:projector-id id}` |
| The zone group editor (`views/dialogs/zone_group_editor.clj:90-169`) | still creates, renames and recolours custom groups |
| Z0's names (02 §4): `:zone-groups/request-remove`, the confirm dialog and `:ui/confirm`, `:zone-groups/remove {:group-id :retarget-to}`, `refs/cues-using`, `unique-group-id`, `refs/ref-places` and `refs/replace-refs` | the Zone Groups tab's Delete…, "Used by n cues", + Group, and the migration's re-pointing of references |
| `show-notice` (`events/helpers.clj:109-123`) | "Laser L moved to 10.0.0.57 (same unit)." |
| `audience-groups` and `audience-text` (`vibes/library.clj:82-87`, `:405-416`), `subs/vibe-audience-groups` (`subs.clj:1145-1150`) | kept, fed from the derived refs |

## 4. Changes per namespace

### NEW `laser-show.routing.rig` (pure)

The rig as data, and the groups that come from it. Z3 extends this namespace with the full resolver (spec §5), so the main output already has the spec's shape: `{ref [[projector-id region side] …]}`, with `region` one of `nil :L :R` and `side` one of `nil :left :right`. A new namespace because the engine, the subs, the migration and the vibe rule all need it, with nothing else loaded.

| Function | Behaviour |
|---|---|
| `enabled? [p]` | `(:enabled? p true)`, the engines' rule (§12 S-3). The new subs use it too |
| `stable-id [{:keys [unit-id service-id host]}]` | `[:unit "0A 1B …" s]` when the unit id is usable, else `[:host host s]`, where `s = (or service-id 0)`. Unusable: nil, blank, or every byte `00` (or every byte `FF`). The text is normalised: upper case, single spaces |
| `identity-label [p]` | "Unit 0A 1B 2C 3D … · output 1", or "IP 10.0.0.5 · output 1 (the device sends no unit id)" |
| `placement-key [id p]` | IPv4 hosts as four numbers first, other hosts by text after them; then name, service id, id |
| `effective-order [stored projectors]` | the stored order (ids that still exist, each once), then every other projector by `placement-key`. So an old show is ordered by IP, then name (§11), and a newly found laser lands after the placed ones |
| `rig-inputs [state]` | `{:order :enabled :excluded :middle :split-width :member-of}` from `[:projectors]` and `[:rig]`, with the defaults `:split` and 0.75 |
| `auto-groups [inputs]` | spec §3 exactly: `:auto/left :auto/right :auto/center :auto/odd :auto/even`, folded from E (the enabled projectors that aren't excluded from All, in order; Q-Z2-1 B, the spec as updated on 2026-11-19), with `:L`/`:R` for n = 1 and for the `:split` middle, and the side tag for `:both`. Plus `:auto.laser/<id>` → `[[id nil nil]]` for **every enabled** laser, excluded or not. With n = 0 every folded vector is empty |
| `ref-entries [inputs]` | `auto-groups`, plus `:all` (E, whole), plus each custom group (its enabled members, whole, in rig order). This is what Z3's resolver puts under `:refs` |
| `refs-for [state id]` | **the Z2 bridge to the engine.** The refs that reach `id`, in join order: `:all` unless excluded; its stored groups, in stored order; the automatic groups it is in (`:auto/left :auto/right :auto/center :auto/odd :auto/even`, then `:auto.laser/<id>`). Regions are ignored, so a half counts as the whole laser. nil for an unknown or disabled projector. Cached on the identity of `(:projectors state)` and `(:rig state)`: one entry in an atom, so an engine tick costs two `identical?` checks and a lookup; a race only recomputes |
| `refs-reaching [state pred]` | every ref that reaches a projector `pred` accepts. For an enabled one, its `refs-for`. For a disabled one: `:all` unless excluded, its stored groups and its laser ref. Today's rule counts disabled crowd lasers too, and this keeps that caution |
| `laser-ref [id]`, `laser-of [ref]`, `derived-ref? [ref]` | `:auto.laser/projector-10-0-0-5-1` and back. Derived: `:all` and every `auto` or `auto.laser` keyword |
| `group-defs` | display data for the derived refs: All `#808080`, Left `#4A90D9`, Right `#D94A4A`, Center `#4AD94A`, Odd `#F1C40F`, Even `#1ABC9C` (today's built-in colours, plus two of the editor's presets), each laser `#4A6FA5` |
| `match-discovered [projectors devices]` | the scan's matching, below |

`match-discovered` returns `{:moves {id {:host :port}} :upgrades {id stable-id} :new [[device service] …] :notices [text …]}`:

1. Each discovered output gets a stable id. If two devices in **one** scan report the same usable unit id, both use `[:host …]` for this scan, and a notice says "Two devices report the same unit id (10.0.0.5, 10.0.0.6): matched by IP."
2. A projector with that stable id: if its host differs, it **moves** (host and port change, nothing else).
3. Otherwise, a projector whose stable id is `[:host address s]`: it **upgrades** to the unit identity (a laser added by hand, then found by a scan). A hand-added projector with no service id matches a device that has exactly one output (or none).
4. Otherwise the output is **new**: disabled, with id `unique-projector-id` (`make-projector-id`, then `-2`, `-3`… while taken), its stable id, `:zone-groups []` and `:exclude-from-all? false`.
5. Two saved projectors with the same stable id (an old show made one per IP): the enabled one keeps it (otherwise the smaller id), and the other falls back to `[:host …]` and stays where it is.

### NEW `laser-show.state.migration` (pure)

Spec §11's Z2 rows. Z4 adds its rows here.

- `zones-z2 [state hardware]` runs when the loaded `hardware.edn` has no `:rig` key (every file Z2 saves has one, because `[:rig]` is saved whole). It is two functions, so ZR can run them apart: `rig-rows [hardware]` (the projector rows) and `show-rows [state hardware]` (the group rows). Each row is also safe to run twice (it looks at the data, not at a flag):
  - **All membership** (rig row). Each projector and virtual projector without `:exclude-from-all?` gets `(not (some #{:all} zone-groups))`, and `:all` leaves its list.
  - **The built-ins** (show row). For each of `:left :right :center` defined in `[:zone-groups]`: if no projector or virtual projector **in the loaded hardware** lists it (enabled or not), the definition goes and every reference becomes `:auto/left` and so on. If any does, it stays, and its name becomes "Left (manual)" while it is still "Left". The references are the places in Z0's `refs/ref-places`: items, groups, tracks, folders and clips; the cue's `:destination-zone`; Zone Selector params and keyframes, in cue and effect chains; the preview cells and filter. A built-in that was already deleted is left alone, and so are references to it (§12 S-12).
  - **All's definition** (show row). `[:zone-groups :all]` goes, because All is built in now (S-9). The id `:all` stays a valid ref.
  - **Identity** (rig row). Each projector without `:stable-id` gets one; duplicates are settled as in `match-discovered` step 5.
  - **No order is written.** `effective-order` reads IP, then name (S-11), so loading changes nothing it doesn't have to.
- `replace-refs`: **Z0's `laser-show.routing.refs/replace-refs [state {old new}]`**, called directly (02 §4). The migration doesn't define its own. It extends Z0's `ref-places` only if it finds a place Z0 missed.

### Existing namespaces

- `laser-show.state.domains`: NEW `(defstate rig …)` with `{:order {:default []} :middle {:default :split} :split-width {:default 0.75}}`. `zone-groups` (`:345-394`) loses `:all :left :right :center`, and its docstring (`:346-364`) says so; `:graphics` and `:crowd` stay. The preview's default cells (`:254-257`) become `nil :auto/left :auto/right :auto/center`. The projectors docstring (`:280-303`) gains `:stable-id` and `:exclude-from-all?`, and `:zone-groups` becomes "custom group ids; All and the automatic groups are never listed".
- `laser-show.state.history`: `:rig` joins `document-roots` (`:48-50`), `extract-document-state` (`:52-64`), the content list in `content-changed?` (`:157`) and `restore-document` (`:262-278`).
- `laser-show.state.persistent`: `:hardware` gains `{:path [:rig] :merge-defaults? true}` (`:53-56`). `apply-loaded-project` (`:179-191`) runs `(migration/zones-z2 (get zip-contents "hardware.edn"))` after `normalize-timeline-cues` (`:189`) and before `mark-clean`, so an old show isn't "unsaved" after loading.
- `laser-show.events.handlers.projector`:
  - `make-projector-config` (`:79-103`) defaults to `:zone-groups []` and `:exclude-from-all? false`, and adds `:stable-id`.
  - `handle-projectors-scan-complete` (`:159-186`) applies `rig/match-discovered`: it backfills new projectors, moves and upgrades, and shows one notice for the moves ("Laser L moved to 10.0.0.57 (same unit)." or "2 lasers have new addresses: Laser L, Laser R.").
  - `set-service-enabled` (`:213-220`) finds the projector by host and service, not by `make-projector-id`, so it still finds a laser that moved.
  - `update-settings` (`:271-289`) recomputes `:stable-id` when the host of a `[:host …]` identity changes.
  - `toggle-zone-group` (`:305-314`) refuses `:all` and derived refs (they are never stored), and so does `vp-toggle-zone-group` (`:322-331`).
  - NEW setters `:projectors/set-enabled`, `:projectors/set-exclude-from-all`, `:projectors/set-tag`, so a checkbox can't toggle twice. `:tags` is stored as a set (old files may have a vector).
  - Z0's `:projectors/remove` also drops the id from `[:rig :order]`, so a laser that a scan finds again lands after the placed ones (invariant 4). Z0's `:projectors/request-remove` also counts the cues pinned to the laser (`refs/cues-using` on its `:auto.laser/<id>`) and asks where they go, with the same "Send its cues to" choice as a group delete; the remove re-targets them with `refs/replace-refs` (S-13).
- NEW `laser-show.events.handlers.rig`, routed as `"rig"` in `laser-show.events.handlers`:
  - `:rig/move-projector {:projector-id :target :side}`: before or after the target card;
  - `:rig/nudge-projector {:projector-id :delta}`: ±1, nothing at the ends;
  - `:rig/set-middle {:middle}`: one of `:split :both :left :right :none`, anything else ignored;
  - `:rig/set-split-width {:split-width}`: clamped to 0.5–1.0, with `:history/coalesce-key [:rig :split-width]`.

  Moves write the whole `effective-order`, so the first move fixes every card's place.
- `laser-show.events.handlers.zone-groups`:
  - NEW `:zone-groups/show {:group-id}` selects the group and switches to the Zone Groups tab.
  - Z0's delete: the "Send its cues to" choice (`:zone-groups/request-remove`) lists `target-groups`, All first (Z0 lists All only while `[:zone-groups :all]` exists, and S-9 removes it). `:zone-groups/remove` counts a derived ref as a group that exists (Z0 sends a missing one to `:all`), and its notice names a derived ref as the pickers do ("3 cues now go to Left"). So a deleted group's cues can go to Left or to one laser.
- `laser-show.events.handlers.history` (`event-descriptions`, `:125-163`): "Move Projector", "Change Middle Laser", "Change Split Width", "Exclude From All", "Change Tags". `history-labels-name-real-events-test` calls each labelled event bare, so each handler returns `{:state state}` when there's nothing to do.
- `laser-show.backend.multi-engine`: `get-projector-zone-groups` (`:47-59`) returns `(rig/refs-for raw-state projector-id)`. Nothing else changes. Z3 replaces it with the snapshot's `:routing` (spec §5).
- `laser-show.vibes.library`:
  - `audience-groups` (`:82-87`) becomes `(into #{:crowd} (rig/refs-reaching state crowd?))`, with crowd virtual projectors counted through their parent, as today.
  - `audience-text` (`:405-416`), the lint's "reaches the audience through …", names a derived ref as the pickers do ("All", "Left", "Laser C", from `rig/group-defs`) and finds the scanner by the refs that reach it, as `refs-reaching` does, not by its stored list.
- `laser-show.subs` (NEW subs, all from `routing.rig`):
  - `rig-settings`;
  - `rig-cards`: one map per card, in order (id, position in E or nil, name, host, output label, enabled, excluded, tags, identity label, selected);
  - `rig-matrix`: `{:columns [id …] :rows [{:id :name :color :kind :cells {id mark}}]}`, with marks `:whole :left-half :right-half :excluded :off :member :not-member`;
  - `target-groups`: All; Left, Right, Center, Odd, Even; the lasers in order; then custom groups by name. Each is `{:id :name :color :kind}`;
  - `target-groups-by-id`, and `group-members [ref]` for the Zone Groups tab.

  `zone-group-usage` (`:716-726`) takes Z0's cue count, and also counts derived refs. `vibe-audience-groups` (`:1145-1150`) also passes `[:rig]`, because the automatic groups a scanner is in follow the order. `projectors-list` (`:509-517`) stays for anything else.
- The pickers (`views/dialogs/cue_chain_editor.clj:476-477`, `views/dialogs/timeline_cue_editor.clj:97, 122`, `views/components/preview.clj:230, 301`, `views/components/visual_editors/custom_param_renderers.clj:503-508`, `views/components/visual_editors/keyframe_modulator_panel.clj:402, 578`) read `target-groups` / `target-groups-by-id` instead of `zone-groups-list` / `zone-groups-by-id`. A derived entry shows a small lock in its list cell. The Zone Selector renderer (`custom_param_renderers.clj:505-506`) and the keyframe value editor (`keyframe_modulator_panel.clj:420-423`) stop adding their own All (Z0 made it conditional). The preview picker's option ids (`views/components/preview.clj:204-205`) take the namespace too, dots as dashes (`preview-zone-option-1-auto-left`), so `:auto/left` and a kept "Left (manual)" don't share one (05 §4 plans the same). If vibe Phase 8a lands first, its two parameter controls switch too, and its default list becomes `[:auto/left :auto/center :auto/right]` (00 §6).
- Views (§7):
  - `views/tabs/projectors.clj`: the strip and its rows on top, the selected card's inspector below. The device list and the configured list go (Q-Z2-4). The zone chips (`:446-475`) go: the rows replace them. The inspector keeps Z0's Remove… and Z1's SAFETY ZONE and TEST PATTERN sections, with their ids. The subtitle keeps Z0's id, `#projectors-subtitle`.
  - NEW `views/components/rig_strip.clj`: the cards, drag and drop, the keys, ◀ ▶, and a card's menu. A card is built from a vector of sections; Z2's thumbnail section returns nil and Z3 fills it. `status-indicator` and `output-status-text` (`views/tabs/projectors.clj:29-43`, `:255-266`, with Z0's and Z1's cases) move here, and `laser-show.views.tabs.projectors-test` follows them.
  - NEW `views/components/group_matrix.clj`: the rows.
  - `views/tabs/zones.clj`: rewritten group-first. It keeps `#zones-subtitle`, `#zone-groups-add-button`, and `#zone-output-<id>` on each laser in the middle.
  - `views/components/zone_chips.clj`: NEW `member-mark` (■ ◧ ◨ □ × –), used by both tabs.

## 5. State placement

| Path | Lives in | Written by | Undo? | Unsaved? |
|---|---|---|---|---|
| `[:rig :order]` `[id …]` | document, `hardware.edn` | `:rig/move-projector`, `:rig/nudge-projector`; Z0's `:projectors/remove` drops the id | yes | yes |
| `[:rig :middle]`, `[:rig :split-width]` | document, `hardware.edn` | `:rig/set-middle`, `:rig/set-split-width` (one step per drag) | yes | yes |
| `[:projectors id :stable-id]` | document | creation; the scan (backfill); the migration; a host edit of a `[:host …]` identity | by a scan: no; by a host edit: yes | by a scan: no; by a host edit: yes |
| `[:projectors id :host]` after a move | document | `:projectors/scan-complete` (backfill) | no | no |
| `[:projectors id :exclude-from-all?]` | document | `:projectors/set-exclude-from-all`; the migration | yes | yes |
| `[:projectors id :tags]` (a set) | document | `:projectors/set-tag` | yes | yes |
| `[:projectors id :zone-groups]` (custom ids only) | document | `:projectors/toggle-zone-group` (both tabs), `:zone-groups/remove` | yes | yes |
| `[:zone-groups]` without `:all :left :right :center` | document | the Zone Groups tab | yes | yes |
| automatic groups, All's members, cards, rows | derived (`routing.rig`, subs) | – | – | – |
| `[:projector-ui :active-projector]` | UI | a card click, ← → on the strip | no | no |

"Document" means `hardware.edn` in the show until ZR (spec §12). Loading an old show writes nothing it doesn't need: no order, and the migration runs before `mark-clean`.

## 6. Events and effects

| Event / effect | Exists? | Dispatched from | Pure? |
|---|---|---|---|
| `:projectors/scan-network`, effect `:projectors/scan` | yes (`events/handlers/projector.clj:109-114`, `events/core.clj:225-263`) | Scan Network; startup (`app.clj:176`) | yes / effect |
| `:projectors/scan-complete` | yes, **changed**: matches by stable id, moves, upgrades | the scan effect | yes (backfill) |
| `:projectors/add-manual` | yes (`:226-242`), **changed**: stable id, `:zone-groups []` | the Add Projector dialog | yes |
| `:projectors/select-projector` | yes (`:248-253`) | a card click; ← → on the strip | yes |
| `:projectors/set-enabled {:projector-id :enabled?}` | **NEW** | the card's On box | yes |
| `:projectors/set-exclude-from-all {:projector-id :exclude?}` | **NEW** | the inspector; a click in the All row; All in the Zone Groups tab | yes |
| `:projectors/set-tag {:projector-id :tag :on?}` | **NEW** | the inspector | yes |
| `:projectors/toggle-zone-group` | yes (`:305-314`), **changed**: refuses derived refs | the custom rows; the Zone Groups tab | yes |
| `:projectors/enable-all-by-ip` | yes (`:201-210`) | a multi-output card's menu, "Turn on every output of 10.0.0.5" | yes |
| `:projectors/set-service-enabled` | yes (`:213-220`), **changed**: finds by host and service | nothing in the new UI; kept for tests and scripts | yes |
| `:projectors/update-settings` | yes (`:271-289`), **changed** for host identities | the inspector's fields | yes |
| `:projectors/request-remove`, `:projectors/remove` | Z0 (02 §4), **changed**: the remove also drops the order entry and re-targets the laser's pins (S-13) | Remove… in the inspector; the confirm dialog | yes |
| Z1's safety, test-pattern and Identify events (`:projectors/set-safety`, `add-blocked-area`, `remove-blocked-area`, `copy-safety`, `identify`, `toggle-calibration`, `set-calibration-brightness`) | Z1 (03 §6) | the inspector's SAFETY ZONE and TEST PATTERN sections; Identify also from a card's menu | yes |
| `:rig/move-projector {:projector-id :target :side}` | **NEW** | a card dropped on a card | yes |
| `:rig/nudge-projector {:projector-id :delta}` | **NEW** | ◀ ▶; Alt+← / Alt+→ | yes |
| `:rig/set-middle {:middle}` | **NEW** | the Middle laser choice | yes |
| `:rig/set-split-width {:split-width}` | **NEW** | the Split width slider | yes, coalesced |
| `:zone-groups/select` | yes (`events/handlers/zone_groups.clj:7-14`) | the group list | yes |
| `:zone-groups/show {:group-id}` | **NEW** | a row's name under the strip | yes |
| `:zone-groups/add`, `create-new`, `edit`, `save-edit` | yes (`:17-41`, `:80-101`; `create-new`'s id from Z0's `unique-group-id`) | + Group (both tabs); Edit… | yes |
| `:zone-groups/request-remove`, `:zone-groups/remove {:group-id :retarget-to}` | Z0 (02 §4), **changed**: the choice lists `target-groups`, and a derived ref is a valid re-target | Delete…; the confirm dialog | yes |
| `:ui/set-active-tab` | yes | the "Projectors tab" link | yes |

No new effect. A reorder, an exclusion or a membership change needs no engine restart: `wanted` (`backend/output_runtime.clj:41-50`) doesn't change, and the frame provider reads the refs live, as it reads `:zone-groups` today (`events/handlers/output_sync.clj:1-10`).

## 7. UX

### The Projectors tab

```
Projectors
Put your lasers in order, left to right. The groups follow the order.
┌───────────────────────────────────────────────────────────────────────────────────────┐
│ [Scan Network] [Add Manual…]   4 outputs · 3 on          Middle laser: [Split ▾]      │
│                                                          Split width:  ───●──  75%    │
│           ◀ ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐ ▶            │
│             │ 1 Laser L  │ │ 2 Laser C  │ │ 3 Laser R  │ │ – 10.0.0.9 │              │
│             │ 10.0.0.5   │ │ 10.0.0.6   │ │ 10.0.0.7 #1│ │ 10.0.0.9   │  (dimmed)    │
│             │ ● Streaming│ │ ●          │ │ ●          │ │ ●          │              │
│             │ [✓] On     │ │ [✓] On     │ │ [✓] On     │ │ [ ] On     │              │
│             └────────────┘ └────────────┘ └────────────┘ └────────────┘              │
│ All            ■              ■              ■              –                         │
│ Left 🔒        ■              ◧              ·              –                         │
│ Right 🔒       ·              ◨              ■              –                         │
│ Center 🔒      ·              ■              ·              –                         │
│ Odd 🔒         ■              ·              ■              –                         │
│ Even 🔒        ·              ■              ·              –                         │
│ Each laser 🔒  ■              ■              ■              –                         │
│ Graphics       □              ■              □              □                         │
│ Crowd Scanning □              □              □              □                         │
│ [+ Group]                                                                             │
└───────────────────────────────────────────────────────────────────────────────────────┘
 CONFIGURE: Laser C                                  (the selected calibration effect's editor, as today)
 Name [Laser C]   Host [10.0.0.6]   Port: [7255]     SAFETY ZONE (Z1, 03 §7)
 Scan rate [25000] pts/s                             the canvas, the Limit and Blocked fields, [+ Add blocked area] …
 Identity: Unit 0A 1B 2C 3D … · output 1             TEST PATTERN  [Show test pattern] [Identify]  Brightness ━●━━ 10%
 [ ] Graphics      [ ] Scans the audience
 [ ] Exclude from All
 CALIBRATION EFFECTS  [+ Add Effect]   (the chain, as today)
 [Remove…]  (Z0)
```

- **Cards** are in `effective-order`, each `#rig-card-<id>` with its On box `#rig-card-enable-<id>`. The number is the laser's position in **E** (spec §3: the enabled lasers that aren't excluded from All, D-33), so Odd is 1, 3, 5… as printed. A disabled card is dimmed, shows "–" and keeps its place (Q-Z2-3). An excluded card isn't dimmed but shows "–" too, with the tooltip "Not in All or the automatic groups", since it has no place in Left/Right/Odd/Even. The dot and the status line are today's (`#projector-dot-<id>`, `#projector-status-<id>`), with Z0's "Corner Pin skipped" and Z1's "Safety error: output off". When not streaming, a laser the last scan didn't find says "Not found in the last scan" in amber (nothing before the session's first scan).
- **A card's right-click menu** has Identify (Z1's event, off while the laser isn't outputting, as Z1's button is) and, on a multi-output card, "Turn on every output of 10.0.0.5". ZR adds "Forget this laser…" on a disabled card (09 §6).
- **Reorder.** Drag a card onto another: dropping on its left half puts it before, on its right half after, and a blue bar shows where. Or click a card and press ◀ ▶ or Alt+← / Alt+→. ← and → alone move the selection. ◀ ▶ are off at the ends and with no card selected.
- **The rows** line up under the cards, in one horizontal scroll pane with them, so more than about 8 lasers scroll together (Q1 in 00 §9).
  - The automatic rows are locked: ■ whole laser, ◧ left half, ◨ right half, · not in it, – off.
  - The All row is clickable: a click excludes or includes that laser (× when excluded).
  - Custom rows: □ and ■, a click toggles (`:projectors/toggle-zone-group`). A disabled laser's box still works.
  - Clicking a row's name opens it in the Zone Groups tab. [+ Group] opens the New Zone Group dialog.
- **Middle laser** is on with 3, 5, 7… enabled lasers. **Split width** is on with one laser, or with an odd count and the middle on Split or Both. The two settings are stored, and the rows show them now. Z2 draws whole, so the split width changes nothing on the lasers until Z3 (S-6).
- **The inspector** is the config panel as Z0 and Z1 leave it, minus the zone chips, plus Identity, the two tags and Exclude from All: the fields; Z1's SAFETY ZONE above its TEST PATTERN row ([Show test pattern] `#projector-calibration-button`, [Identify] `#projector-identify-button`, Brightness `#calibration-brightness-slider`); the calibration chain and its editor; Z0's [Remove…] (`#projector-remove-button`) at the bottom. Every id stays. If Z1 hasn't landed, its sections come later, in this place (03 §4).

| Where | Copy |
|---|---|
| Subtitle | "Put your lasers in order, left to right. The groups follow the order." (`#projectors-subtitle`, Z0's id. It replaces Z0's and Z1's text, and Z0's subtitle test takes it) |
| Header | "4 outputs · 3 on". With none on: "Tick On on a card to send it output." Add Manual… keeps `#projectors-add-manual-button` |
| Middle laser choices | "Split between Left and Right" · "In both, whole" · "Left only" · "Right only" · "In neither" |
| Middle laser, off | "Used with 3, 5, 7… lasers" |
| Split width | "How wide each half is when one laser draws Left and Right" |
| ◀ / ▶ tooltips | "Move left (Alt+←)" / "Move right (Alt+→)" |
| A half mark's tooltip | "Left half of Laser C. For now the whole laser draws it." (Z3 drops the second sentence) |
| The All row's tooltip | "Click to keep Laser C out of All" / "Excluded from All: click to include" |
| A locked row's tooltip | "Automatic: follows the order of the cards" |
| Identity | "Identity: Unit 0A 1B 2C 3D … · output 1" or "Identity: IP 10.0.0.5 · output 1 (the device sends no unit id, so a new address makes it a new laser)" |
| Graphics | "Graphics: a label for lasers that draw text and logos" |
| Scans the audience | "Vibes treat every cue that can reach this laser as an audience cue, and only play it when the vibe allows the audience." |
| Audience laser still in All | (amber) "This laser is in All, so that's every cue that plays on All." [Exclude from All] |
| Exclude from All | "Cues that go to All won't play here, and neither will Left, Right, Center, Odd or Even. It still plays its own group and your groups." |
| Move notice | "Laser L moved to 10.0.0.57 (same unit)." |

### The Zone Groups tab

```
Zone Groups
Groups are where cues draw. All and the automatic groups follow the order on the Projectors tab.
┌ GROUPS ─────────── [+] ┐ ┌ LASERS IN LEFT ────────────────────────────────────┐ ┌ DETAILS ──────────┐
│ ● All            🔒 3   │ │  1 Laser L    2 Laser C    3 Laser R    – 10.0.0.9 │ │ ● Left · automatic│
│ ● Left           🔒 2   │ │     ■             ◧            ·            –      │ │ Used by 6 cues    │
│ ● Right          🔒 2   │ │                                                    │ │ Can't be renamed  │
│ ● Center         🔒 1   │ │ Left is the left half of the rig. The middle      │ │ or deleted.       │
│ ● Odd            🔒 2   │ │ laser, Laser C, is split: its left half is in     │ │                   │
│ ● Even           🔒 1   │ │ Left. [Projectors tab] to change the order or     │ │                   │
│ LASERS                  │ │ the middle laser.                                  │ │                   │
│ ● Laser L        🔒 1   │ └────────────────────────────────────────────────────┘ └───────────────────┘
│ YOUR GROUPS             │
│ ● Graphics          1   │
│ ● Crowd Scanning    0   │
└─────────────────────────┘
```

- **The list**: All, the five automatic groups, LASERS (one per laser, in order), YOUR GROUPS (by name). The number is how many lasers the group reaches. `#zone-groups-add-button` stays. The subtitle above is `#zones-subtitle` (UX Phase 3's id), with the text in the sketch.
- **The middle** shows the lasers in rig order with the same marks as the rows. Each laser is `#zone-output-<id>`, as the rows are today.
  - For your groups the boxes toggle: "Click a laser to add it to Graphics or take it out. The boxes under the cards on the Projectors tab are the same ones."
  - For All a click excludes or includes.
  - The automatic groups are read only, with one sentence that says why (table below).
  - Virtual projectors stay listed under the strip with their toggles, headed "Virtual projectors (not sent to any laser yet)".
- **Details**: the colour, name and description, "Used by n cues" (Z0), and for your groups [Edit…] (the existing dialog) and [Delete…] (Z0's confirm, whose "Send its cues to" now lists every target, §4). For derived groups: "Automatic: can't be renamed or deleted." That line replaces Z0's "All can't be deleted." for All.

| Group | Sentence (n is the enabled count) |
|---|---|
| All | "Every laser that's on, except those set to Exclude from All." |
| Left / Right | "The left half of the rig." n = 1: "With one laser, Left is its left 75%." Split middle: "The middle laser, Laser C, is split: its left half is in Left." Both: "The middle laser, Laser C, is in both, whole." |
| Center | "The middle laser, or the middle two." |
| Odd / Even | "Every other laser from the left: 1st, 3rd, 5th…" / "2nd, 4th…". n = 1: "With one laser, Odd is its left half and Even its right half." |
| A laser | "Just Laser C." |

## 8. Data examples and invariants

**An old show loads** (its `hardware.edn` has no `:rig`):

```clojure
;; before (hardware.edn)
{:projectors {:projector-10-0-0-5-1  {:name "Laser L" :host "10.0.0.5" :service-id 1 :enabled? true
                                      :unit-id "0A 1B 2C 3D 4E 5F 60 71 82 93 A4 B5 C6 D7 E8 F9"
                                      :zone-groups [:all :graphics]}
              :projector-10-0-0-12-1 {:name "Aud" :host "10.0.0.12" :service-id 1 :enabled? true
                                      :unit-id "00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"
                                      :zone-groups [:crowd] :tags #{:crowd-scanning}}
              :projector-10-0-0-7-1  {:name "Laser R" :host "10.0.0.7" :service-id 1 :enabled? true
                                      :unit-id "1F 2E 3D 4C 5B 6A 79 88 97 A6 B5 C4 D3 E2 F1 00"
                                      :zone-groups [:all :right]}}
 :zone-groups {:all {…} :left {…} :right {:name "Right" …} :center {…} :graphics {…} :crowd {…}}}
;; a cue item says :zone-group-id :left, the preview cells say :left :right :center

;; after zones-z2
:projector-10-0-0-5-1  {… :zone-groups [:graphics] :exclude-from-all? false
                          :stable-id [:unit "0A 1B 2C 3D 4E 5F 60 71 82 93 A4 B5 C6 D7 E8 F9" 1]}
:projector-10-0-0-12-1 {… :zone-groups [:crowd] :exclude-from-all? true :stable-id [:host "10.0.0.12" 1]}
:projector-10-0-0-7-1  {… :zone-groups [:right] :exclude-from-all? false :stable-id [:unit "1F 2E …" 1]}
[:zone-groups] => {:right {:name "Right (manual)" …} :graphics {…} :crowd {…}}
;; :left and :center were empty: gone; the item and the preview cells now say :auto/left, :auto/center
;; order (derived, nothing written): 10.0.0.5, 10.0.0.7, 10.0.0.12 (as text, .12 would come first)
```

**The groups of that rig** (all three enabled, middle `:split`; Aud is excluded from All, so under the updated spec §3 it isn't folded into the automatic groups either, which is Q-Z2-1 B):

```clojure
(rig/auto-groups (rig/rig-inputs state))
;; E = Laser L (10.0.0.5), Laser R (10.0.0.7): n = 2, so no middle laser
;; => {:auto/left   [[:projector-10-0-0-5-1 nil nil]]
;;     :auto/right  [[:projector-10-0-0-7-1 nil nil]]
;;     :auto/center [[:projector-10-0-0-5-1 nil nil] [:projector-10-0-0-7-1 nil nil]]
;;     :auto/odd    [[:projector-10-0-0-5-1 nil nil]]
;;     :auto/even   [[:projector-10-0-0-7-1 nil nil]]
;;     :auto.laser/projector-10-0-0-5-1  [[:projector-10-0-0-5-1 nil nil]]
;;     :auto.laser/projector-10-0-0-12-1 [[:projector-10-0-0-12-1 nil nil]] …}   ; every enabled laser has one
(rig/refs-for state :projector-10-0-0-7-1)
;; => (:all :right :auto/right :auto/center :auto/even :auto.laser/projector-10-0-0-7-1)
(rig/refs-for state :projector-10-0-0-12-1)
;; => (:crowd :auto.laser/projector-10-0-0-12-1)   ; no :all, no folded groups
```

The scanner keeps its place on the strip, but it doesn't count in Left, Right, Odd or Even, so it can't turn Laser R into a "middle" laser. If the answer to Q-Z2-1 is A, the scanner would count again: Laser R would become the split middle, and the scanner would be in Right and Odd.

**One laser in Z2.** A cue with item 1 → `:auto/left` (a 33-point circle) and item 2 → `:auto/right` (a 50-point wave). The one laser's refs include both, so it draws circle, blanking jump, wave, overlaid and whole. Z3 fits them into ◧ and ◨ instead.

**A laser moves:**

```clojure
(rig/match-discovered {:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :service-id 1
                                              :stable-id [:unit "0A 1B …" 1] …}}
                      [{:address "10.0.0.57" :port 7255 :unit-id "0A 1B …" :services [{:service-id 1}]}])
;; => {:moves {:projector-10-0-0-5-1 {:host "10.0.0.57" :port 7255}} :upgrades {} :new []
;;     :notices ["Laser L moved to 10.0.0.57 (same unit)."]}
;; A different unit that later answers at 10.0.0.5 becomes :projector-10-0-0-5-1-2, never the moved laser
```

**Invariants**

1. Automatic groups and All are derived and never saved: no `:auto/*`, `:auto.laser/*` or `:all` in `[:zone-groups]` or in any projector's `:zone-groups` (the handlers refuse them).
2. Every enabled projector without `:exclude-from-all?` draws the frames keyed `:all`, with nothing ticked (D-20).
3. `auto-groups` is spec §3 for every n ≥ 0 and every middle policy. It reads only the order and the enabled set, never the streaming status, so a laser that goes offline keeps its place (D-21).
4. Only the user moves a placed card (drag, ◀ ▶, Alt+←/→). Discovery, enabling and loading never do. A new laser lands after the placed ones, by IP.
5. A laser with a usable unit id keeps its id, setup and place when its IP changes. A new unit never takes an existing id.
6. After loading an old show, every projector draws the same set of frames as before for every key the show uses. The one exception is content aimed at an empty built-in Left, Right or Center, which now reaches the automatic group (S-2).
7. Loading an old show doesn't make it unsaved, and running the conversion twice gives the same state.
8. Both tabs show one membership (each projector's `:zone-groups`) and change it with the same events.
9. A reorder, an exclusion or a membership change re-routes on the next tick, with no engine restart.
10. The refs that make a laser draw a cue are the refs that make that cue an audience cue when the laser scans the audience. The engines, the vibe engine, the Vibes tab's picker and its lint all agree.
11. `routing.rig` is pure. With the same projectors and `[:rig]` it gives the same groups, and `refs-for` returns the identical value until either changes.
12. Removing a laser (Z0's remove, and ZR's Forget, which is the same) leaves its id nowhere: not in `[:rig :order]`, and no cue pinned to its `:auto.laser/*` (S-13).

## 9. Tests

Fixtures to reuse:
- `test/laser_show/events/projector_handlers_test.clj:42-64` (`state-with-projector`) and `:334-335` (`through-pipeline`);
- `test/laser_show/events/output_sync_test.clj:17-34` (`streaming-state`, `run`);
- `test/laser_show/backend/multi_engine_test.clj:20-35` (`setup-test-state`, `snapshot`);
- `test/laser_show/state/persistent_test.clj:41-72` (`init-test-state!`), and `:252-258` for an old file (`update-in` over `persistent/project-files`);
- `test/laser_show/vibes/library_test.clj:48-50` (`with-projector`);
- `test/laser_show/state/history_test.clj:8-34` (`sample-state`);
- UI: `test/laser_show/ui_test/setup_dialogs_test.clj:23-36` (`with-projector!`, `device`), `fire-drop!` with `drag-content`.

Unit tests:

- `laser-show.routing.rig-test` (NEW):
  - `auto-groups-table-test`: spec §3's table, n = 0 to 6, `:split`;
  - `middle-policy-test`: n = 3 and n = 5 under each of the five policies;
  - `one-laser-splits-left-right-and-odd-even-test`;
  - `disabled-projectors-are-skipped-test`, `offline-laser-keeps-its-place-test`, `excluded-laser-leaves-all-only-test`;
  - `effective-order-test`: stored order, unknown ids dropped, the rest by IP as numbers then name, host names after IPs;
  - `refs-for-order-test`, `refs-for-is-cached-by-identity-test`, `refs-for-disabled-is-nil-test`;
  - `stable-id-test`: a unit id, zeros, blank, a hand-added projector;
  - `match-discovered-test`: a move, an upgrade, a new laser with a free id, two devices with one unit id, two saved projectors with one unit id;
  - `fold-properties-test` (generated rigs, n = 0 to 12, every policy): Odd and Even partition E by position; Left and Right share at most one laser (the middle one, or the only one); halves appear only for n = 1 and the split middle; Center is never empty when n ≥ 1.
- `laser-show.state.migration-test` (NEW), one fixture per §11 row:
  - `all-membership-becomes-exclude-test`, `empty-built-ins-become-auto-test` (an item, a track, a clip, the cue's destination, a Zone Selector and its keyframes, the preview cells), `built-ins-with-members-become-manual-test`, `custom-groups-are-unchanged-test`;
  - `order-comes-from-ip-then-name-test`, `stable-ids-are-added-test`, `duplicate-unit-keeps-one-identity-test`;
  - `files-with-a-rig-are-left-alone-test`, `conversion-is-idempotent-test`;
  - `old-show-routes-as-before-test`: for each fixture and key, the frames `extract-frames-for-zones` picks with the old list equal those picked with `refs-for`, except for the converted empty built-ins.
- `laser-show.events.projector-handlers-test` (existing):
  - `scan-moves-a-known-unit-to-its-new-ip-test` (name, calibration, groups and place kept), `scan-move-is-not-undone-and-not-unsaved-test`, `scan-gives-a-new-unit-a-free-id-test`, `scan-shows-a-notice-for-moves-test`;
  - `set-service-enabled-finds-a-moved-laser-test`, `new-projectors-are-in-all-without-listing-it-test`, `set-exclude-from-all-test`, `set-tag-keeps-a-set-test` (from a vector too), `toggle-refuses-derived-groups-test` (the virtual projector's toggle too), `host-edit-moves-a-host-identity-test`;
  - `remove-takes-the-laser-out-of-the-order-test`, `remove-retargets-the-lasers-pins-test`, beside Z0's remove tests;
  - `handle-projectors-toggle-zone-group-test` (`:68-91`) no longer expects `:all` in the list.
- `laser-show.events.zone-groups-handlers-test` (existing, with Z0's tests): `remove-can-retarget-to-an-automatic-group-test`.
- `laser-show.events.rig-handlers-test` (NEW): `move-before-and-after-a-card-test`, `first-move-writes-the-whole-order-test`, `nudge-at-the-ends-does-nothing-test`, `set-middle-ignores-unknown-values-test`, `split-width-is-clamped-test`, `split-width-drag-is-one-undo-step-test`, `reorder-is-undoable-and-unsaved-test`, `reorder-requests-no-engine-refresh-test`.
- `laser-show.events.history-handlers-test/history-labels-name-real-events-test` (existing) covers the new labels.
- `laser-show.events.output-sync-test` (existing): `service-enabled-uses-the-deterministic-id-test` (`:61-67`) passes unchanged, since its projector has that host and service.
- `laser-show.backend.multi-engine-test` (existing): `get-projector-zone-groups-test` (`:254-270`) expects the derived lists; NEW `projector-draws-all-without-listing-it-test`, `excluded-projector-skips-all-test`, `one-laser-draws-left-and-right-whole-test` (pins Z2's behaviour, so Z3 changes it on purpose), `reorder-reroutes-left-test`.
- `laser-show.vibes.library-test` (existing):
  - `audience-cell-test` (`:52-87`): its "not in All" projectors gain `:exclude-from-all? true` (`:56`, `:62-63`, `:86-87`), and the `audience-groups` set at `:86-87` adds that laser's derived refs;
  - the lint's audience rows (`:278-284`) still read "through Crowd Scanning" and "through All (P3)";
  - NEW `crowd-laser-in-all-makes-all-audience-test`, `excluded-crowd-laser-leaves-all-alone-test`, `crowd-laser-at-the-left-end-makes-left-audience-test`, `lint-names-the-automatic-group-and-its-scanner-test` ("through Left (Aud)", with Aud's `:zone-groups` empty).
- `laser-show.subs-test` (existing): `rig-cards-test`, `rig-matrix-marks-test` (◧ ◨ for one laser and for the split middle; × excluded; – off), `target-groups-order-test`, `zone-group-usage-counts-derived-refs-test`, `vibe-audience-groups-follow-the-order-test` (a reorder moves the picker's audience mark from one cue to another).
- `laser-show.state.persistent-test` (existing): `init-test-state!` (`:67-72`) gets `:zone-groups []` and `:exclude-from-all? false`, so `test-load-project` (`:149-187`) and `test-round-trip` (`:189-209`) stay identities; NEW `rig-is-saved-with-the-hardware-test`, `old-show-loads-clean-test`.
- `laser-show.state.history-test` (existing): `rig-is-undoable-content-test`, `restore-document-restores-the-rig-test`.
- `laser-show.views.tabs.projectors-test` (existing): `output-status-text-test`, with Z0's and Z1's cases, follows `output-status-text` into `rig_strip.clj`.

UI tests:

- `laser-show.ui-test.rig-strip-test` (NEW; `with-toolkit` once, `with-app` each):
  - `scan-shows-a-card-per-output-in-ip-order-test`, `card-on-box-enables-the-output-test`, `no-output-device-gets-a-card-test`;
  - `drop-a-card-before-another-test` (`fire-drop!` on the left half), `alt-arrow-moves-the-selected-card-test`, `move-buttons-follow-the-ends-test`;
  - `rows-follow-the-order-test` (3 lasers, split: ◧ ◨ on the middle), `middle-laser-choice-changes-the-rows-test`, `custom-row-toggles-membership-test`, `all-row-click-excludes-test`;
  - `audience-tag-offers-exclude-from-all-test`.
- `laser-show.ui-test.zone-groups-tab-test` (NEW): `list-has-all-automatic-lasers-and-yours-test`, `automatic-group-is-read-only-and-explained-test`, `custom-group-boxes-toggle-in-rig-order-test`, `both-tabs-show-the-same-membership-test`, `row-name-opens-the-group-test`.
- `laser-show.ui-test.setup-dialogs-test` (existing):
  - `untick-a-discovered-output-disables-it-test` (`:156-171`) and `single-output-device-renders-test` (`:185-199`) keep their names and tick `#rig-card-enable-<id>` instead of `#discovered-output-…`, which is gone (and `#discovered-device-…` with it).
  - `click-a-projector-to-add-it-to-the-group-test` (`:106-132`, Z0's guard for B-6) picks a custom group instead of `:left`, which is no longer stored, and expects the new `#zones-subtitle` text. It still clicks `#zone-output-a`'s name, then its box.
  - Z0's tests there: `subtitle-promises-no-safety-zones-test` takes Z2's subtitle; `all-has-no-delete-test` reads "Automatic: can't be renamed or deleted."; a test that picks a projector by its row in the configured list clicks its card instead (`#rig-card-<id>`).
  - `rename-projector-test`, `projector-port-label-shows-whole-test`, `calibration-brightness-slider-test`, the two Add Projector tests and Z0's `remove-projector-asks-first-test` pass unchanged (the inspector and the header keep the ids).
- `laser-show.ui-test.streaming-status-test` (all of it: the dot and the status line on the card, the host, port and scan-rate fields in the inspector), Z1's `laser-show.ui-test.safety-editor-test`, `laser-show.ui-test.main-window-test/top-level-tabs-update-content-and-preview`, `zone-group-editor-uses-theme-classes-test`, and `laser-show.ui-test.preview-test` (its 2x2 cells now show `:auto/left`, `:auto/right` and `:auto/center`, which nothing plays to either) pass unchanged.

Run, targeted first (`targeted-ui-testing`):
1. `clj -M:mac-arm:test -n laser-show.routing.rig-test -n laser-show.state.migration-test -n laser-show.events.rig-handlers-test -n laser-show.events.projector-handlers-test -n laser-show.backend.multi-engine-test -n laser-show.vibes.library-test -n laser-show.subs-test -n laser-show.state.persistent-test -n laser-show.state.history-test -n laser-show.events.output-sync-test -n laser-show.events.zone-groups-handlers-test -n laser-show.events.history-handlers-test -n laser-show.views.tabs.projectors-test`
2. `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.rig-strip-test`, then `-n laser-show.ui-test.zone-groups-tab-test`, then `-n laser-show.ui-test.setup-dialogs-test -n laser-show.ui-test.streaming-status-test -n laser-show.ui-test.main-window-test -n laser-show.ui-test.preview-test`, and `-n laser-show.ui-test.safety-editor-test` once Z1 is in
3. The full suites: `clj -M:mac-arm:test`, then `clj -M:test-ui:mac-arm:test-ui-headless`.

## 10. Commit plan

Each step is one commit: the named tests first (run them and record them red), then the code, then the step's tests and the touched namespaces.

1. `feat(routing): a pure rig namespace: projectors in left-to-right order (the stored order, then the rest by IP and name), the automatic groups All, Left, Right, Center, Odd, Even and one per laser folded from it as spec §3 says (halves for one laser and the split middle), and a stable id from the unit id and the output`. Tests: `rig-test`. Nothing is wired yet.
2. `feat(rig): the rig order, the middle laser and the split width are saved with the hardware and undoable, and a removed laser leaves the order`. The domain, history, persistence, Z0's remove. Tests: `rig-is-saved-with-the-hardware-test`, `rig-is-undoable-content-test`, `restore-document-restores-the-rig-test`, `remove-takes-the-laser-out-of-the-order-test`.
3. `feat(projectors): a laser that comes back on a new IP keeps its name, calibration, groups and place: a scan matches it by its unit id and output, moves it and says so, and a new unit never takes a moved laser's id`. Tests: the scan tests and `set-service-enabled-finds-a-moved-laser-test`.
4. `feat(routing): every enabled projector plays All without listing it (Exclude from All opts out), the engines also draw Left, Right, Center, Odd, Even and one group per laser (whole lasers for now), and old shows load converted: All ticks become Exclude from All, empty built-in Left, Right and Center become the automatic ones, filled ones stay as "Left (manual)"`. The migration and the routing switch land **together**: either alone would move content in old shows. The vibes' audience rule, its lint line and the Vibes picker's mark switch in the same commit (invariant 10). Tests: `migration-test`, the engine tests, the vibe audience tests (`library-test`, `vibe-audience-groups-follow-the-order-test`), `old-show-loads-clean-test`.
5. `feat(ui): every zone picker lists All, the automatic groups and each laser before your groups, with a lock on the automatic ones; deleting a group can send its cues to any of them, and removing a laser asks where the cues pinned to it go`. Tests: `target-groups-order-test`, `remove-can-retarget-to-an-automatic-group-test`, `remove-retargets-the-lasers-pins-test`; the cue chain and preview UI tests unchanged.
6. `feat(rig): the Projectors tab shows the lasers as cards in rig order: drag a card onto another, or select it and press ◀ ▶ or Alt+←/→; each card turns its output on, shows its status and has Identify in its menu, and the selected card's settings, safety zone, test pattern and calibration are below`. Tests: `rig-handlers-test`, `rig-strip-test` (cards, reorder), the moved setup tests, `projectors-test`, and the setup, streaming-status and safety-editor UI tests unchanged.
7. `feat(rig): group rows under the cards: the automatic groups locked and coloured, with half marks for split lasers, All clickable to exclude a laser, your groups with a box per laser, and the middle-laser and split-width settings beside them`. Tests: `rig-matrix-marks-test`, the rows tests in `rig-strip-test`.
8. `feat(projectors): the selected card's identity, its Graphics and Scans the audience tags (which vibes read) and Exclude from All, with an offer to exclude an audience laser that's still in All`. Tests: `audience-tag-offers-exclude-from-all-test`, `set-tag-keeps-a-set-test`.
9. `feat(zones): the Zone Groups tab starts from the group: All, the automatic groups, each laser and yours, with their lasers in rig order, ticked for your groups and explained for the automatic ones`. Tests: `zone-groups-tab-test`, `click-a-projector-to-add-it-to-the-group-test` as changed.
10. `plans: Z2 done (<sha>)`: as built, deviations, tests, red-first results and verification, written back into this doc.

## 11. Out of scope / alternatives considered

- **Drawing halves, thumbnails, the load meter and low-fps badges**: Z3. Z2 stores the middle policy and the split width, shows the marks, and leaves an empty thumbnail section in each card.
- **Safety zones, the test pattern and Identify**: Z1 (03). Z2 only places Z1's sections in the inspector, with their ids, and puts Identify on a card's menu (03 §6). Z0's Remove… goes at the bottom of the inspector.
- **From `08-rig-setup-ux-future.md`**, with the hooks Z2 leaves:
  - rows and the 2D map (R-6, R-9): order lives in one place, `effective-order`;
  - per-tag groups (R-7): `routing.rig/ref-entries` is where `:auto/tag-…` would go;
  - a bracket for a multi-head device (R-5): the cards carry the unit id;
  - ghost cards and a tray (R-19): the cards carry "not found in the last scan";
  - ignore (R-18; delete is Z0's Remove…), the lock (R-25), multi-select (R-16), guessing the order from names (R-3, `placement-key`).
- **Changing the projector ids to the stable id**: rejected. Every chain, selection, engine registry entry, pin and test refers to the id, and ids are opaque anyway. Keeping them and adding `:stable-id` costs nothing (S-7).
- **Storing the automatic groups**: rejected (spec invariant 7). They would go stale the moment a card moves.
- **One row per laser in the rows**: a diagonal of ■. One "Each laser" row says the same thing.
- **A separate Rig tab replacing both tabs**: D-15 keeps both.
- **The UX plan** (finished, deleted in `2d1f813`, in git history). Its Phase 3 (`b9b9d55`) gave the Zone Groups subtitle the id `#zones-subtitle` (`views/tabs/zones.clj:338-341`) and each row the id `#zone-output-<id>` (`:160`), and moved `commit-field` to `views/components/commit_field.clj`. Z2 keeps both ids, replaces the subtitle's text, and changes the test that reads it (§9). Phase 2's Port: fix (`views/tabs/projectors.clj:394-398`) moves into the inspector unchanged.
- **Vibe Phases 3–5** (landed). The Vibes tab reads the audience rule in its picker and its lint, so both switch to the derived refs with the engine (§4, invariant 10). The Master at `[0 3]` and the faders act inside each cue's render, before routing, so they reach every laser alike and need nothing from Z2 (Z1 keeps the Master off the test pattern, 03 S-Z1-8). Learned controls (`[:config :input :trigger-map]` and `:macro-map`) are show data, untouched here (ZR says why it leaves them, 09 S-ZR-3).
- **Vibe Phase 6** (`4a4a10a`, landed: auto-advance and one tap tempo) needs nothing from Z2 and changes nothing Z2 uses; it only moved lines.
- **Merge risk: vibe Phase 7** (safety and hardening, in progress in a worktree). It edits `state/domains.clj` (the `ui` domain and `[:config :vibes :settings]`, apart from the parts Z2 changes) and `subs.clj`, which Z2 edits too, and `events/core.clj`, which Z2 doesn't touch. Its show lock blocks the events that re-route the engines (its §7 lists `:projectors/toggle-zone-group` and the enable events), so whichever lands second adds Z2's (`:rig/move-projector`, `:rig/nudge-projector`, `:rig/set-middle`, `:rig/set-split-width`, `:projectors/set-enabled`, `:projectors/set-exclude-from-all`, `:projectors/set-tag`) to its `blocked-events`.
- **Z0 is being built** in a worktree (`zones-z0`). Its names so far match 02: `laser-show.routing.refs` (`ref-places`, `cue-refs`, `cues-using`, `replace-refs`), `views/dialogs/confirm.clj` and `:ui/confirm`, `:zone-groups/request-remove`, and a public `unique-group-id` in `events/handlers/zone_groups.clj`. Its delete offers All only while `[:zone-groups :all]` exists and sends a target missing from `[:zone-groups]` to `:all`, which is why §4 changes both for Z2.
- **Noticed, not done**: `parse-scan-response` trims the host name with `str/trim` (`idn/hello.clj:384`), which keeps NUL padding. Read, not seen on hardware. The cards show the host name only as a fallback name.

## 12. Spec questions and open questions

### Spec questions (the code and 01 disagree, or 01 is unclear; what this doc chose)

- **S-1 · The join order before Z3.** Spec §6 sorts a bucket's keys by `(juxt namespace name)`. Z2 still joins by list. **Chosen:** `:all` first, then the stored groups in stored order, then the automatic ones. That's today's order for every list that starts with `:all` (the default). Z3's sort replaces it. The set of frames is the contract (invariant 6), not their order.
- **S-2 · §11 row 2 changes routing.** Content aimed at an empty built-in Left reached no laser. After the conversion it reaches the automatic Left. That breaks D-22's "route exactly as before" for that one case. **Chosen:** follow §11, because it is what the author meant and no one could see that content. The fixture test checks the new routing for that row and identical routing for every other row.
- **S-3 · Which "enabled".** §3 says enabled. The engines read a missing `:enabled?` as on (`backend/multi_engine.clj:220`); the discovery sub reads it as off (`subs.clj:547-557`). **Chosen:** the engines' rule, in one helper, `rig/enabled?`.
- **S-4 · Excluded lasers in the automatic groups.** §3 used to fold E as every enabled laser, so an excluded laser still counted in Left, Odd and so on. **Updated 2026-11-19:** the spec now follows Q-Z2-1 B. E leaves out the excluded lasers, and each of them keeps only its own `:auto.laser/*` group and its custom groups. §4's `auto-groups` and §8's example follow this.
- **S-5 · `:auto.laser/<id>` of a disabled laser** resolves to no entries (E only). The pickers still list it, marked "(off)".
- **S-6 · Marks before drawing.** The rows show ◧ ◨ (what §3 defines), while Z2's engine draws whole. **Chosen:** show the marks, with a tooltip that says the whole laser draws it for now.
- **S-7 · Ids.** §1 says a projector "is matched by its stable id". **Chosen:** the `:projectors` keys stay as they are and gain `:stable-id`. A new projector's id stays host-based but is made unique.
- **S-8 · Virtual projectors** aren't in the spec. **Chosen:** they get the All conversion and stay listed in the Zone Groups tab. They are not in the rig order (no engine draws them).
- **S-9 · All's definition.** §1 keeps the id `:all` "for old files". **Chosen:** the id stays a ref, and its stored definition goes. All is built in, like the automatic groups.
- **S-10 · "No members"** (§11) counts projectors and virtual projectors, enabled or not.
- **S-11 · "No `[:rig :order]`"** (§11). **Chosen:** the order is derived when read (`effective-order`) and never written by the conversion. The same function places new lasers.
- **S-12 · A deleted built-in still named by cues** (B-5's dangling references): left as is. It routes nowhere, as before. Z0's delete re-targets from then on, so only shows saved before Z0 have them.
- **S-13 · Removing a laser that cues are pinned to.** Not in the spec. Z0's `:projectors/remove` (02 §4) came before pins existed, so it leaves a cue on `:auto.laser/<id>` naming a laser that's gone: it would play nowhere, without a word (B-5 again, for a laser). **Chosen:** the remove's confirm says how many cues are pinned to the laser and asks where they go, with the delete's "Send its cues to" choice (All by default), and the remove re-targets them with `refs/replace-refs`. With no pins, Z0's dialog is unchanged. ZR's Forget is the same event (09 §6).

### Open questions for the user (★ = recommendation)

**Q-Z2-1 · Does Exclude from All also take a laser out of the automatic groups?** **Answered B on 2026-11-19 (D-33).** The spec (§3) and this doc (§4, §8) follow it. An audience scanner or a graphics-only laser placed on the strip used to still count in Left, Right, Odd and Even (S-4, and needs/08 P-2).
- **A** No, as the spec first said. Exclude only means "not in All".
- **B ★ (chosen)** Yes. An excluded laser is reached only by its own group and your groups, and the others fold without it.
- **C** A second switch, "Not in the automatic groups".

**Q-Z2-2 · Ticking "Scans the audience" on a laser that is in All** makes every All cue an audience cue for vibes (as today, when it lists `:all`). needs/08 Q-R5.
- **A ★** Offer it: an amber line and an [Exclude from All] button. Nothing happens by itself.
- **B** Exclude it from All automatically, with a note.
- **C** Neither.

**Q-Z2-3 · Disabled lasers on the strip** (needs/08 P-11: a scan adds every device on the network).
- **A ★** In their place, dimmed, numbered "–". Turning one on moves no card (the groups refold, since n changes).
- **B** In a tray after the strip ("Not in the rig"), so other people's lasers stay out of the way. That is close to R-19.
- **C** Hidden behind a "Show the lasers that are off (3)" toggle.

**Q-Z2-4 · The device list.** Today the left column lists devices, with a checkbox per output and Enable All.
- **A ★** It goes. Each output is a card with its own On box; a multi-output card's menu has "Turn on every output of 10.0.0.5"; the header counts what the scan found.
- **B** It stays, as a collapsible "Devices found" panel above the strip.

**Q1 (00 §9, still open)** sizes the strip: Z2 scrolls past about 8 cards. A count over 12 would call for the compact cards in needs/08 R-14.

## 13. As built (2026-11-19, `5e39fd5`..`af2979d`)

Built in a worktree (`zones-z2`) off `989c315` (Z0 in), while Z1 and vibe Phase 7 ran in theirs. Vibe Phase 7 reached `master` meanwhile (`8b4b7d7`). Not merged: the orchestrator merges it ("For the merges" says what the trial merges show). Q1 is still open, so the strip is one horizontal scroll pane that scrolls past about 8 cards, the rows with it.

### Where it landed

| # | Commit | Step (§10) |
|---|---|---|
| 1 | `5e39fd5` | NEW `routing/rig.clj`: `enabled?`, `stable-id`, `identity-label`, `placement-key`, `effective-order`, `rig-inputs`, `auto-groups`, `ref-entries`, `refs-for` (cached), `reaching`, `refs-reaching`, `laser-ref`/`laser-of`/`derived-ref?`, `group-defs`, `ref-name`, `projector-id`/`unique-projector-id`, `settled-ids`, `match-discovered` |
| 2 | `cfd7380` | `(defstate rig …)`, `:rig` a document root (history), `[:rig]` in `hardware.edn` with `:merge-defaults?`, Z0's remove drops the laser from `[:rig :order]` |
| 3 | `b7213d2` | the scan matches by stable id (moves, upgrades, new outputs with free ids, one notice), `make-projector-config` adds `:stable-id`, `set-service-enabled` by host and output, a host edit moves a `[:host …]` identity |
| 4 | `e1f1eef` | NEW `state/migration.clj` (`rig-rows`, `show-rows`, `zones-z2`) run by `apply-loaded-project`; `get-projector-zone-groups` → `rig/refs-for`; the built-in groups leave the defaults and the preview's cells become `:auto/*`; new projectors list no group and play All; the toggles refuse derived refs; the vibes' `audience-groups`, `audience-text`, `vibe-audience-groups` (and `reaction-inputs`, `lint-world`) read the rig |
| 5 | `fb94d35` | `rig/target-groups`, `rig/known-ref?`, subs `target-groups`/`target-groups-by-id`; the five pickers switch to them, with a lock; the delete's choice lists every target and a derived ref is a valid re-target; `:projectors/request-remove` counts the laser's pins and asks where they go, `:projectors/remove` re-targets them (S-13) |
| 6 | `005f299` | NEW `events/handlers/rig.clj` (routed as `"rig"`), NEW `views/components/rig_strip.clj` (cards, drag and drop, keys, ◀ ▶, the card menu; `status-indicator` and `output-status-text` moved here), `(defstate rig-ui …)`, `:projectors/set-enabled`, sub `rig-cards`; the Projectors tab is the header, the strip and the inspector; the device list, the configured list and the zone chips are gone |
| 7 | `a4a6bd3` | NEW `views/components/group_matrix.clj` (the rows, the middle-laser and split-width settings), `rig/ref-marks` and `rig/matrix`, subs `rig-matrix` and `rig-settings`, `zone-chips/member-mark`, `:projectors/set-exclude-from-all`, `:zone-groups/show` |
| 8 | `b164e37` | the inspector's identity, tags and Exclude from All with the audience offer (Q-Z2-2 A), `:projectors/set-tag` |
| 9 | `3adcab6` | `views/tabs/zones.clj` rewritten group first; subs `ref-reach`, `zone-groups-tab-list`, `selected-target-group`, `group-members`; `zone-group-usage` counts the lasers a derived ref reaches |
| + | `af2979d` | fix: the zone group editor neither opens for nor saves All or an automatic group (invariant 1); a test of a card's menu |

### Deviations from the plan

- **`match-discovered`'s `:new` entries are maps,** `{:id :device :service :stable-id}`, not `[device service]` pairs: it picks the free id itself (`rig/unique-projector-id`; `rig/projector-id` replaced the handler's private `make-projector-id`) and keeps the identity it matched with, which is a host one when two devices of the scan share a unit id. Its `:upgrades` also carries every saved projector whose settled identity isn't stored yet (one from before `:stable-id`, or the one of two with one unit id that falls back to its address), so the scan writes those too. Its fifth rule (two saved projectors with one unit id) is the public `rig/settled-ids`, which the migration's identity row shares.
- **An upgrade writes the unit id and leaves `:service-id` as it was,** so a laser added by hand keeps sending to the service it sent to. The duplicate-unit notice and the move notice share the one notice slot, joined.
- **The step-3/step-4 split:** step 3 gave new projectors `:stable-id` only; `:zone-groups []` and `:exclude-from-all? false` came in step 4 with the routing, so no commit had new projectors that drew nothing.
- **The built-ins left the defaults in step 4,** with the migration. To keep step 4 whole, `subs/zone-groups-list` put the built-in All first and `subs/zone-group` resolved All and the automatic groups until steps 5 and 9 replaced their users, and Z0's delete offered All from step 4 on (S-9).
- **`refs-for` never takes All or an automatic ref from a stored list, and takes each group once,** so old or hand-edited data can't draw All twice.
- **An excluded laser is × in the automatic rows too,** not only in All (D-33 takes it out of them), and §4's seven marks gain `:not-in`, the · that §7 draws. A laser that is off is – in All, the automatic rows and Each laser; your rows still say whether it's in them.
- **The pure parts live in `routing.rig`:** `target-groups`, `known-ref?` (the delete's choice is made in a handler), `ref-marks` and `matrix` (the Zone Groups tab's `group-members` reads the same marks as the rows). The subs wrap them.
- **New UI state and events not in §5/§6:** `[:rig-ui :drop]` (the bar while a card is over another) and `[:rig-ui :scanned?]` (no "Not found in the last scan" before the session's first scan), with `:rig/drag-over` and `:rig/drag-exit`. Never saved, never undone.
- **Layout:** the On box sits beside the address and the strip takes the height its rows need (up to 400 px, then it scrolls), so the inspector keeps its room in the default 1200 × 800 window. The inspector has three columns: the settings with Identity, the tags and Exclude from All; the calibration chain with Remove…; and the effect editor and calibration controls, whose code is unchanged from `master` (for Z1's merge). The tab's title is "Projectors", the subtitle beside it.
- **Removing a laser that cues are pinned to shows a notice,** "Removed Laser L · 2 cues now go to Left", as a group delete does.
- **History labels** also name `:projectors/set-enabled` ("Turn Projector On or Off"); `:rig/nudge-projector` shares "Move Projector".
- **Identify on a card's menu dispatches Z1's `:projectors/identify {:projector-id}`,** disabled unless streaming is on and the laser is on with a host. Until Z1 merges, the event is unknown and does nothing.
- **The Zone Groups tab's lasers run left to right** (the sketch) and each has its mark, and a box for your groups and All. The sentences for a middle laser on Left, Right or neither are this build's wording ("is in Left only", "is in neither"). The details show "Used by n cues" and the kind, not Z0's projector counts (the middle shows the members).
- **The Zone Selector and keyframe pickers still call `zone-chips/zone-options`,** a no-op now that `target-groups` starts with All.
- **Test names:** `excluded-laser-leaves-all-only-test` is `excluded-laser-leaves-all-and-the-folded-groups-test` (D-33); `row-name-opens-the-group-test` is in `rig-strip-test`, where the names are clicked.
- **`zone-chips/zone-group-chip` is removed** (the chips were its only user).

### Tests added

- `laser-show.routing.rig-test` (NEW): 17 tests (the §9 list, plus `ref-entries-test`, `refs-reaching-test`, `refs-and-names-test`, `unique-projector-id-test`), 2,294 assertions with `fold-properties-test`'s 260 seeded rigs.
- `laser-show.state.migration-test` (NEW): the ten §9 tests.
- `laser-show.events.rig-handlers-test` (NEW): the eight §9 tests and `drop-marker-test`.
- `laser-show.events.projector-handlers-test`: `remove-takes-the-laser-out-of-the-order-test`, `scan-moves-a-known-unit-to-its-new-ip-test`, `scan-move-is-not-undone-and-not-unsaved-test`, `scan-gives-a-new-unit-a-free-id-test`, `scan-shows-a-notice-for-moves-test`, `set-service-enabled-finds-a-moved-laser-test`, `host-edit-moves-a-host-identity-test`, `new-projectors-have-a-stable-id-test`, `toggle-refuses-derived-groups-test`, `new-projectors-are-in-all-without-listing-it-test`, `remove-retargets-the-lasers-pins-test`, `set-enabled-turns-an-output-on-or-off-test`, `set-exclude-from-all-test`, `set-tag-keeps-a-set-test`; `handle-projectors-toggle-zone-group-test` no longer expects `:all`.
- `laser-show.events.zone-groups-handlers-test`: `remove-can-retarget-to-an-automatic-group-test`, `show-opens-the-group-in-its-tab-test`, `built-in-groups-are-never-stored-test`; Z0's tests that used the stored Left use Graphics, and the delete's options are every target.
- `laser-show.backend.multi-engine-test`: `projector-draws-all-without-listing-it-test`, `excluded-projector-skips-all-test`, `one-laser-draws-left-and-right-whole-test`, `reorder-reroutes-left-test`; `get-projector-zone-groups-test` expects the derived lists, and `direct-frame` reads `refs-for`.
- `laser-show.vibes.library-test`: `crowd-laser-in-all-makes-all-audience-test`, `excluded-crowd-laser-leaves-all-alone-test`, `crowd-laser-at-the-left-end-makes-left-audience-test`, `lint-names-the-automatic-group-and-its-scanner-test`; `audience-cell-test` as §9 says.
- `laser-show.subs-test`: `target-groups-order-test`, `rig-cards-test`, `rig-matrix-marks-test`, `zone-group-usage-counts-derived-refs-test` (with the tab's list and `group-members`), `vibe-audience-groups-follow-the-order-test`.
- `laser-show.state.persistent-test`: `rig-is-saved-with-the-hardware-test`, `old-show-loads-clean-test`; `init-test-state!` is Z2-shaped.
- `laser-show.state.history-test`: `rig-is-undoable-content-test`, `restore-document-restores-the-rig-test`.
- `laser-show.views.tabs.projectors-test`: `output-status-text-test` reads `rig-strip/output-status-text`; NEW `rig-header-text-test`.
- `laser-show.views.tabs.zones-test`: `group-explanation-test`. `laser-show.views.components.zone-chips-test`: `member-mark-test`, `built-in-groups-show-a-lock-test`.
- UI, `laser-show.ui-test.rig-strip-test` (NEW, 13 tests): the §9 list, `row-name-opens-the-group-test` and `card-menu-turns-on-every-output-test`.
- UI, `laser-show.ui-test.zone-groups-tab-test` (NEW): `list-has-all-automatic-lasers-and-yours-test`, `automatic-group-is-read-only-and-explained-test`, `custom-group-boxes-toggle-in-rig-order-test`, `both-tabs-show-the-same-membership-test`.
- UI, `laser-show.ui-test.setup-dialogs-test`: the two discovery tests tick `#rig-card-enable-<id>`; `click-a-projector-to-add-it-to-the-group-test` picks Graphics and reads the new subtitle; `subtitle-promises-no-safety-zones-test` takes Z2's subtitle; `all-has-no-delete-test` reads "Automatic: can't be renamed or deleted."; `delete-group-asks-and-retargets-test` sends the cue to Left (`:auto/left`); `with-projector!` lists no group.

### Red first

| Step | Before the change |
|---|---|
| 1 | `rig-test` couldn't load: no namespace |
| 2 | the four tests: 18 failures |
| 3 | the seven scan and identity tests: 17 failures |
| 4 | `migration-test` couldn't load; of the 20 other tests run, 14 failed: 34 failures (the other 6 are the fixtures below) |
| 5 | `target-groups-order-test` couldn't compile (no `subs/target-groups`); the handler namespaces: 24 failures |
| 6 | `rig-handlers-test` and `set-enabled-turns-an-output-on-or-off-test`: 51 failures; `rig-cards-test` couldn't compile, `projectors-test` couldn't load (no `rig_strip.clj`) |
| 7 | the handler tests: 8 failures; `rig-matrix-marks-test` and `member-mark-test` couldn't compile; the five rows UI tests: 5 errors |
| 8 | `set-tag-keeps-a-set-test`: 4 failures; `audience-tag-offers-exclude-from-all-test`: 1 error |
| 9 | `zone-group-usage-counts-derived-refs-test` and `group-explanation-test` couldn't compile; the four tab tests and Z0's two changed ones: 6 errors |
| + | `built-in-groups-are-never-stored-test`: 6 failures |

Green on their first run, **not run red**: the six card and reorder UI tests of step 6 (`scan-shows-a-card-per-output-in-ip-order-test`, `card-on-box-enables-the-output-test`, `no-output-device-gets-a-card-test`, `drop-a-card-before-another-test`, `alt-arrow-moves-the-selected-card-test`, `move-buttons-follow-the-ends-test`: written with the strip and first run against it, where they failed only on the strip's own bugs), `reorder-requests-no-engine-refresh-test` (passed vacuously before the `"rig"` route existed), `built-in-groups-show-a-lock-test` and `card-menu-turns-on-every-output-test` (written after what they test), and the changed fixtures that kept passing (step 4's `engine-frame-equals-direct-render-test`, `lint-test`, `test-save-project`, `test-load-project`, `test-round-trip` and `handle-projectors-toggle-zone-group-test`, and the setup-dialog tests moved to the cards).

### Verification

| Where | Unit suite (`clj -M:mac-arm:test`) | UI suite (`clj -M:test-ui:mac-arm:test-ui-headless`) |
|---|---|---|
| `989c315` (the base) | 1,153 tests, 34,605 assertions, 0 failures | 143 tests, 709 assertions, 0 failures |
| step 4, `e1f1eef` | 1,203 tests, 37,035 assertions, 0 failures | 143 tests, 709 assertions, 0 failures |
| step 9, `3adcab6` | 1,226 tests, 37,215 assertions, 0 failures | 159 tests, 764 assertions, 0 failures |
| **`af2979d` (final)** | **1,227 tests, 37,222 assertions, 0 failures** | **160 tests, 766 assertions, 0 failures** |

Each step also ran its targeted namespaces (§9's run list), and the unit suite after steps 2, 5, 6, 7 and 8. After step 7 one full unit run failed `streaming-engine-test/new-tick-goes-out-without-the-minimum-gap-test` (a wall-clock test: 0 of 5 gaps under 8 ms while the machine was loaded); alone it passed, and every later full run passed.

### For the merges

Trial merges of `zones-z2` with `git merge-tree`, which writes no branch and no worktree:

- **Into `master` at `8b4b7d7`** (vibe Phase 7 is in):
  - One conflict: `test/laser_show/subs_test.clj`. Both sides add tests after `zone-group-usage-counts-cues-test`. Keep both.
  - `state/domains.clj`, `state/persistent.clj`, `subs.clj`, `vibes/library.clj` and `persistent_test.clj` merge cleanly.
  - Then add Z2's seven events that re-route the engines, or change which cues reach the audience, to `blocked-events` in `events/show_lock.clj`, as vibe 07 §10 expects: `:rig/move-projector`, `:rig/nudge-projector`, `:rig/set-middle`, `:rig/set-split-width`, `:projectors/set-enabled`, `:projectors/set-exclude-from-all` and `:projectors/set-tag`. `blocked-events-name-real-events-test` checks that they are routed.
  - Z2 leaves vibe Phase 7's other files alone: `events/core.clj`, the input handlers, the menu, the toolbar, the Perform tab and the show clock.
- **With `zones-z1` at `1cd79a3`**, in either order, five small conflicts:
  - `events/handlers/history.clj`: both add labels after `:projectors/remove`. Keep both.
  - `events/handlers/projector.clj`:
    - The end of the `:require` list: keep both.
    - `handle-projectors-remove`: take Z2's, and add Z1's `(update-in [:projector-ui :identify] dissoc projector-id)`.
  - `views/tabs/projectors.clj`:
    - The device list and the configured list: Z2 removes them and Z1 changed them. Take Z2's side, and port Z1's two changes into `views/components/rig_strip.clj`. `output-status-text` takes `kind` and returns `(if (= :safety kind) error (str "Error: " error))` for an error. `card-status` gives `#projector-status-<id>` the `(:detail status)` tooltip.
    - `projectors-tab`: take Z2's. Its subtitle replaces Z1's (§7).
  - `test/laser_show/events/projector_handlers_test.clj`: the requires. Keep both.
  - `test/laser_show/ui_test/setup_dialogs_test.clj`: Z1's `subtitle-mentions-safety-zones-test` expects Z1's subtitle. It follows whichever subtitle the merge keeps. Z1 renamed it from `subtitle-promises-no-safety-zones-test`, and with Z2's text the old name fits again.
- **What merges cleanly with Z1 but fails until the port above:** `projectors_test/output-status-text-test` gets Z1's safety case, and it now reads `rig-strip/output-status-text`.
- **What merges cleanly with Z1, as intended:**
  - Z1's SAFETY ZONE (`safety-editor/safety-section`) lands in the inspector's right column, above `calibration-controls`. Z2 left that code as `master` has it.
  - `backend/multi_engine.clj`: Z2 changes only `get-projector-zone-groups` and one `:require`.
  - `events/handlers/projector.clj`, apart from the two conflicts:
    - Z2's setters are one block after `handle-projectors-toggle-enabled`, and their `case` rows sit together after the calibration rows.
    - Z2 also rewrote the scan, `make-projector-config`, `set-service-enabled`, `update-settings` and the two toggles.
  - `state/domains.clj`: Z2's `rig` and `rig-ui` defstates follow `zone-group-ui`.
  - `state/persistent.clj`: the `[:rig]` path, and the migration in `apply-loaded-project`.
  - Identify on a card's menu sends `{:event/type :projectors/identify :projector-id id}`, the event Z1's button sends.

### Found on the way

- **Dead after Z2, left in the shared files for the merges:**
  - The subs `zone-groups-list`, `zone-groups-by-id`, `projector-zone-groups`, `enabled-projector-services`, `expanded-discovered-devices`, `selected-zone-group`, and `zone-group` (only `selected-zone-group` used it).
  - The `:projectors/toggle-device-expand` handler and `[:projector-ui :expanded-devices]`.
  - `:projectors/set-service-enabled` (as §6 expects) and `:projectors/toggle-enabled` have no button now. The show lock already blocks both.
- **QUICKSTART** step 3, which Z1 edits too, still says to tick the outputs to use. That's still true with the cards' On boxes, but it doesn't say that the cards' order makes Left, Right and the rest. Worth a line once both are in.
- **cljfx:** a grid-pane or v-box constraint is applied by the parent, so it goes on the description the parent gets, a function component's too (on the description the function returns it is "No such prop"); a nil `:graphic` breaks a menu item and a nil `:style` a node. `ui/shown-text` finds a Labeled graphic's `.text` before the cell's own, so the lock is a plain Text node.
- **The vibe rule's cue walk** (`vibes.library/zone-group-ids`) still skips keyframes, as Z0 noted, so a keyframed Zone Selector's zones don't make a cue an audience cue. Unchanged.
- **A preview cell shows the frames of exactly its ref,** so a cell on Left doesn't show what the left laser draws through All. Z3's per-projector preview is the fix.
