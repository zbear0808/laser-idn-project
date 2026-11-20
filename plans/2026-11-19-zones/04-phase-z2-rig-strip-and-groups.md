# Phase Z2: the rig strip, stable identity, automatic groups, implicit All, the two tabs

**Written:** 2026-11-19   **Written for commit:** `90e35c7` (`master`). The spec was written at `f9fc352`; since then UX Phase 2 (`e3d6d1b`) moved lines in `views/tabs/projectors.clj` (from `:420`), `events/handlers/projector.clj` (from `:226`), `state/domains.clj` (from `:246`), `subs.clj` (from `:128`) and `views/root.clj` (from `:95`). Every line below is at `90e35c7`. **Re-check at HEAD before starting.**
**Status:** Proposed. Not to be built yet (D-8).   **Needs:** Z0 (`02-phase-z0-fixups.md`: delete asks first and re-targets, one toggle per click, a real cue count, group ids that can't collide, "All" listed once). Independent of Z1. Z3 builds on it.
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
| A projector's id | `events/handlers/projector.clj:66-73` (`make-projector-id`); hand-added `:229` | `:projector-10-0-0-5-1`, built from the host and the service id. Hand-added ones get `:projector-<ms>` |
| A new projector | `:79-103` (`make-projector-config`, default `:zone-groups [:all]` at `:90`); scan `:117-144` | stores `:unit-id` (`:97`, `:139`) and never reads it again. A discovered output starts disabled (`:140`). A scan creates a projector only if its id is new (`:128`) |
| What a scan reports | `events/core.clj:241-279` (device shape `:254-267`); `idn/hello.clj:359-384`, `:391-416` | per device: the address, a 20-byte host name, a **16-byte unit id** as text `"0A 1B …"` (`bytes-to-hex`, `idn/hello.clj:54-57`), status flags, and a service map (service id 1–255, unique per device, a name, a relay number) |
| Discovery and undo | `events/handlers/projector.clj:159-186` | new projectors are backfilled into undo and the saved baseline (`:182-186`): never undone, never unsaved |
| An IP change | the same | the id comes from the IP, so the laser returns as a **new, disabled** projector without its calibration, groups or place (needs/08 P-6). A different unit that later gets the old IP takes the old id and its setup (`:128`) |
| Enabling | `:199-208` (Enable All by IP), `:211-218` (one output, found again with `make-projector-id`), `:290-295` (toggle) | three paths. The discovery list's checkboxes use the second |
| What "enabled" means | `backend/multi_engine.clj:220`, `backend/output_runtime.clj:48`; `subs.clj:534-544` | the engines treat a missing `:enabled?` as on, `(:enabled? p true)`. The discovery sub treats it as off |
| The Projectors tab | `views/tabs/projectors.clj:817-835` | three columns: Discover devices `:226-277` (device rows `:129-224`), Configured projectors `:345-373` (sorted by service id only, `subs.clj:496-504`), and the config panel `:712-811` with zone chips `:474-503`. The subtitle claims safety zones (`:828`, B-1) |
| The Zone Groups tab | `views/tabs/zones.clj:325-346` | a group list `:89-129`, every output with a checkbox `:135-207` (the row and the box both toggle, `:164-179`, B-6), details `:239-302` with "Cues targeting: 0" (`subs.clj:711`, B-7). Tags are shown read-only (`:41-57`); nothing edits them (B-10) |
| Routing | `backend/multi_engine.clj:47-59`, `:119-150` | a projector draws the frames of the groups it **lists**, joined in list order (`:133-137`). `:all` is only a name: a projector that doesn't list it never draws it |
| The groups | `state/domains.clj:340-389` | six stored defaults, `:all :left :right :center :graphics :crowd`. The preview's default cells name `:left :right :center` (`:250-254`) |
| The pickers | `subs.clj:661-666` (`zone-groups-list`) | every zone picker lists the stored groups: the cue chain editor (`views/dialogs/cue_chain_editor.clj:470-471`, dropdown `:95-129`, labels `:146-199`), the timeline editor (`views/dialogs/timeline_cue_editor.clj:97, 122`), the preview (`views/components/preview.clj:230, 301`), the Zone Selector (`views/components/visual_editors/custom_param_renderers.clj:503-508`), keyframes (`views/components/visual_editors/keyframe_modulator_panel.clj:383, 557`) |
| Saving | `state/persistent.clj:48-61` | `hardware.edn` holds projectors, virtual projectors and groups (`:53-56`); calibration chains are in `content.edn` (`:61`). Load `:162-174` (beside `model/normalize-timeline-cues`, `:172`); New `:176-185` |
| Undo | `state/history.clj:48-64`, `:153-159`, `:262-278` | document roots `[:config :timing :grid :projectors :virtual-projectors :zone-groups :chains]`. A projector edit is undoable and marks the show unsaved |
| The audience rule | `vibes/library.clj:64-75` | `:crowd` plus every group a `:crowd-scanning` projector **lists**. A crowd laser listing `:all` makes every All cue an audience cue (`test/laser_show/vibes/library_test.clj:63-64`) |
| Virtual projectors | `state/domains.clj:301-316`; `views/tabs/zones.clj:189-207` | listed with toggles. No create handler, no engine (`backend/multi_engine.clj:212-227`) |
| Drag and drop | `views/components/drag_drop_cell.clj:78-118` | `drag-content`, `start-drag!`, `has-payload?` and `read-payload` work for any drag type. Headless tests drop with `fire-drop!` (`test/laser_show/ui_test/testfx.clj:403-435`) |

## 2. Why / root cause

- The model has **no order**, so a projector has no place. Left, Right, Center, Odd and Even can only be built by hand, and All is a tick box like any other group.
- **Identity is the IP.** The unit id is read and stored but never used to match, so an address change orphans the whole setup.
- **Membership lives on each projector** (`:zone-groups`), but it is edited in two unrelated lists, and neither shows where the lasers are.
- The audience rule reads the stored lists, so it can only be as good as those lists.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `:zone-groups` on each projector | stays the one record of **custom** membership (spec §12). All and the automatic groups are never stored in it |
| `:projectors/toggle-zone-group` (`events/handlers/projector.clj:303-312`), with Z0's one-toggle fix | the one membership event, from the rows under the strip and from the Zone Groups tab (D-15) |
| `make-projector-id` (`:66-73`) | still makes the id of a *new* projector, made unique by NEW `unique-projector-id` |
| The scan's backfill (`:182-186`) | also carries host moves and identity upgrades, so they are never undone and never unsaved |
| `extract-frames-for-zones`, `combine-zone-frames` (`backend/multi_engine.clj:61-88`) | unchanged. Only the list they get changes (`get-projector-zone-groups`) |
| `projector-basic-settings`, `commit-field`, the calibration sidebar and controls (`views/tabs/projectors.clj:49-75`, `:379-472`, `:621-811`) | become the inspector, keeping their node ids (`#projector-name-field`, `#projector-host-field`, `#projector-port-label`, `#projector-config-title`, `#projector-calibration-button`…), so `setup-dialogs-test` and `streaming-status-test` keep working |
| `status-indicator`, `output-status-text` (`:29-43`, `:283-294`), `subs/projector-output-status` (`subs.clj:382-386`) | the card's dot and status line, with the same ids `#projector-dot-<id>` and `#projector-status-<id>` (`test/laser_show/ui_test/streaming_status_test.clj:97-115`) |
| The drag-and-drop helpers (`views/components/drag_drop_cell.clj:78-118`) | a NEW drag type `:rig-card` with payload `{:projector-id id}` |
| The zone group editor (`views/dialogs/zone_group_editor.clj:90-173`) | still creates, renames and recolours custom groups |
| Z0: delete with confirm and re-target, the cue count, collision-free ids, and the reference walker its re-target uses | the Zone Groups tab's Delete…, "Used by n cues", + Group, and the migration's re-pointing of references. **Check Z0's names at HEAD** |
| `show-notice` (`events/helpers.clj:105-123`) | "Laser L moved to 10.0.0.57 (same unit)." |
| `audience-groups` (`vibes/library.clj:64-75`) | kept, fed from the derived refs |

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
  - **The built-ins** (show row). For each of `:left :right :center` defined in `[:zone-groups]`: if no projector or virtual projector **in the loaded hardware** lists it (enabled or not), the definition goes and every reference becomes `:auto/left` and so on. If any does, it stays, and its name becomes "Left (manual)" while it is still "Left". The references are found by Z0's walker: items, groups, tracks, folders and clips; the cue's `:destination-zone`; Zone Selector params and keyframes, in cue and effect chains; the preview cells and filter. A built-in that was already deleted is left alone, and so are references to it (§12 S-12).
  - **All's definition** (show row). `[:zone-groups :all]` goes, because All is built in now (S-9). The id `:all` stays a valid ref.
  - **Identity** (rig row). Each projector without `:stable-id` gets one; duplicates are settled as in `match-discovered` step 5.
  - **No order is written.** `effective-order` reads IP, then name (S-11), so loading changes nothing it doesn't have to.
- `replace-refs [state {old new}]`: Z0's walker if Z0 has one; otherwise it is written here, over the places listed above.

### Existing namespaces

- `laser-show.state.domains`: NEW `(defstate rig …)` with `{:order {:default []} :middle {:default :split} :split-width {:default 0.75}}`. `zone-groups` (`:340-389`) loses `:all :left :right :center`; `:graphics` and `:crowd` stay. The preview's default cells (`:250-254`) become `nil :auto/left :auto/right :auto/center`. The projectors docstring (`:274-299`) gains `:stable-id` and `:exclude-from-all?`, and `:zone-groups` becomes "custom group ids; All and the automatic groups are never listed".
- `laser-show.state.history`: `:rig` joins `document-roots` (`:48-50`), `extract-document-state` (`:52-64`), the content list in `content-changed?` (`:157`) and `restore-document` (`:262-278`).
- `laser-show.state.persistent`: `:hardware` gains `{:path [:rig] :merge-defaults? true}` (`:53-56`). `apply-loaded-project` (`:162-174`) runs `(migration/zones-z2 (get zip-contents "hardware.edn"))` after `normalize-timeline-cues` and before `mark-clean`, so an old show isn't "unsaved" after loading.
- `laser-show.events.handlers.projector`:
  - `make-projector-config` (`:79-103`) defaults to `:zone-groups []` and `:exclude-from-all? false`, and adds `:stable-id`.
  - `handle-projectors-scan-complete` (`:159-186`) applies `rig/match-discovered`: it backfills new projectors, moves and upgrades, and shows one notice for the moves ("Laser L moved to 10.0.0.57 (same unit)." or "2 lasers have new addresses: Laser L, Laser R.").
  - `set-service-enabled` (`:211-218`) finds the projector by host and service, not by `make-projector-id`, so it still finds a laser that moved.
  - `update-settings` (`:269-287`) recomputes `:stable-id` when the host of a `[:host …]` identity changes.
  - `toggle-zone-group` (`:303-312`) refuses `:all` and derived refs (they are never stored).
  - NEW setters `:projectors/set-enabled`, `:projectors/set-exclude-from-all`, `:projectors/set-tag`, so a checkbox can't toggle twice. `:tags` is stored as a set (old files may have a vector).
- NEW `laser-show.events.handlers.rig`, routed as `"rig"` in `laser-show.events.handlers`:
  - `:rig/move-projector {:projector-id :target :side}`: before or after the target card;
  - `:rig/nudge-projector {:projector-id :delta}`: ±1, nothing at the ends;
  - `:rig/set-middle {:middle}`: one of `:split :both :left :right :none`, anything else ignored;
  - `:rig/set-split-width {:split-width}`: clamped to 0.5–1.0, with `:history/coalesce-key [:rig :split-width]`.

  Moves write the whole `effective-order`, so the first move fixes every card's place.
- `laser-show.events.handlers.zone-groups`: NEW `:zone-groups/show {:group-id}` selects the group and switches to the Zone Groups tab.
- `laser-show.events.handlers.history` (`event-descriptions`, `:125-148`): "Move Projector", "Change Middle Laser", "Change Split Width", "Exclude From All", "Change Tags".
- `laser-show.backend.multi-engine`: `get-projector-zone-groups` (`:47-59`) returns `(rig/refs-for raw-state projector-id)`. Nothing else changes. Z3 replaces it with the snapshot's `:routing` (spec §5).
- `laser-show.vibes.library`: `audience-groups` (`:64-75`) becomes `(into #{:crowd} (rig/refs-reaching state crowd?))`, with crowd virtual projectors counted through their parent, as today.
- `laser-show.subs` (NEW subs, all from `routing.rig`):
  - `rig-settings`;
  - `rig-cards`: one map per card, in order (id, position in E or nil, name, host, output label, enabled, excluded, tags, identity label, selected);
  - `rig-matrix`: `{:columns [id …] :rows [{:id :name :color :kind :cells {id mark}}]}`, with marks `:whole :left-half :right-half :excluded :off :member :not-member`;
  - `target-groups`: All; Left, Right, Center, Odd, Even; the lasers in order; then custom groups by name. Each is `{:id :name :color :kind}`;
  - `target-groups-by-id`, and `group-members [ref]` for the Zone Groups tab.

  `zone-group-usage` (`:703-713`) takes Z0's cue count, and also counts derived refs. `projectors-list` stays for anything else.
- The pickers (`views/dialogs/cue_chain_editor.clj:470-471`, `views/dialogs/timeline_cue_editor.clj:97, 122`, `views/components/preview.clj:230, 301`, `views/components/visual_editors/custom_param_renderers.clj:503-508`, `views/components/visual_editors/keyframe_modulator_panel.clj:383, 557`) read `target-groups` / `target-groups-by-id` instead of the stored list. A derived entry shows a small lock in its list cell. The Zone Selector renderer stops prepending its own All (`:505-506`). If vibe Phase 8a lands first, its two parameter controls switch too, and its default list becomes `[:auto/left :auto/center :auto/right]` (00 §6).
- Views (§7):
  - `views/tabs/projectors.clj`: the strip and its rows on top, the selected card's inspector below. The device list and the configured list go (Q-Z2-4). The zone chips (`:474-503`) go: the rows replace them.
  - NEW `views/components/rig_strip.clj`: the cards, drag and drop, the keys, ◀ ▶. A card is built from a vector of sections; Z2's thumbnail section returns nil and Z3 fills it.
  - NEW `views/components/group_matrix.clj`: the rows.
  - `views/tabs/zones.clj`: rewritten group-first.
  - `views/components/zone_chips.clj`: NEW `member-mark` (■ ◧ ◨ □ × –), used by both tabs.

## 5. State placement

| Path | Lives in | Written by | Undo? | Unsaved? |
|---|---|---|---|---|
| `[:rig :order]` `[id …]` | document, `hardware.edn` | `:rig/move-projector`, `:rig/nudge-projector` | yes | yes |
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
| `:projectors/scan-network`, effect `:projectors/scan` | yes (`events/handlers/projector.clj:109-114`, `events/core.clj:241-279`) | Scan Network; startup (`app.clj:175`) | yes / effect |
| `:projectors/scan-complete` | yes, **changed**: matches by stable id, moves, upgrades | the scan effect | yes (backfill) |
| `:projectors/add-manual` | yes (`:224-240`), **changed**: stable id, `:zone-groups []` | the Add Projector dialog | yes |
| `:projectors/select-projector` | yes (`:246-251`) | a card click; ← → on the strip | yes |
| `:projectors/set-enabled {:projector-id :enabled?}` | **NEW** | the card's On box | yes |
| `:projectors/set-exclude-from-all {:projector-id :exclude?}` | **NEW** | the inspector; a click in the All row; All in the Zone Groups tab | yes |
| `:projectors/set-tag {:projector-id :tag :on?}` | **NEW** | the inspector | yes |
| `:projectors/toggle-zone-group` | yes (`:303-312`), **changed**: refuses derived refs | the custom rows; the Zone Groups tab | yes |
| `:projectors/enable-all-by-ip` | yes (`:199-208`) | a multi-output card's menu, "Turn on every output of 10.0.0.5" | yes |
| `:projectors/set-service-enabled` | yes (`:211-218`), **changed**: finds by host and service | nothing in the new UI; kept for tests and scripts | yes |
| `:projectors/update-settings` | yes (`:269-287`), **changed** for host identities | the inspector's fields | yes |
| `:rig/move-projector {:projector-id :target :side}` | **NEW** | a card dropped on a card | yes |
| `:rig/nudge-projector {:projector-id :delta}` | **NEW** | ◀ ▶; Alt+← / Alt+→ | yes |
| `:rig/set-middle {:middle}` | **NEW** | the Middle laser choice | yes |
| `:rig/set-split-width {:split-width}` | **NEW** | the Split width slider | yes, coalesced |
| `:zone-groups/select` | yes (`events/handlers/zone_groups.clj:7-14`) | the group list | yes |
| `:zone-groups/show {:group-id}` | **NEW** | a row's name under the strip | yes |
| `:zone-groups/add`, `create-new`, `edit`, `save-edit` | yes (`:17-41`, `:80-101`; ids from Z0) | + Group (both tabs); Edit… | yes |
| `:zone-groups/remove` | yes, with Z0's confirm and re-target | Delete… | yes |
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
 CONFIGURE: Laser C                                     (calibration chain and editor on the right, as today)
 Name [Laser C]   Host [10.0.0.6]   Port: [7255]   Scan rate [25000] pts/s
 Identity: Unit 0A 1B 2C 3D … · output 1
 [ ] Graphics      [ ] Scans the audience
 [ ] Exclude from All
```

- **Cards** are in `effective-order`. The number is the laser's position among the **enabled** lasers, so Odd is 1, 3, 5… as printed. A disabled card is dimmed, shows "–" and keeps its place (Q-Z2-3). The dot and the status line are today's (`#projector-dot-<id>`, `#projector-status-<id>`). When not streaming, a laser the last scan didn't find says "Not found in the last scan" in amber.
- **Reorder.** Drag a card onto another: dropping on its left half puts it before, on its right half after, and a blue bar shows where. Or click a card and press ◀ ▶ or Alt+← / Alt+→. ← and → alone move the selection. ◀ ▶ are off at the ends and with no card selected.
- **The rows** line up under the cards, in one horizontal scroll pane with them, so more than about 8 lasers scroll together (Q1 in 00 §9).
  - The automatic rows are locked: ■ whole laser, ◧ left half, ◨ right half, · not in it, – off.
  - The All row is clickable: a click excludes or includes that laser (× when excluded).
  - Custom rows: □ and ■, a click toggles (`:projectors/toggle-zone-group`). A disabled laser's box still works.
  - Clicking a row's name opens it in the Zone Groups tab. [+ Group] opens the New Zone Group dialog.
- **Middle laser** is on with 3, 5, 7… enabled lasers. **Split width** is on with one laser, or with an odd count and the middle on Split or Both. The two settings are stored, and the rows show them now. Z2 draws whole, so the split width changes nothing on the lasers until Z3 (S-6).
- **The inspector** is today's config panel minus the zone chips, plus Identity, the two tags and Exclude from All. A multi-output card's right-click menu has "Turn on every output of 10.0.0.5".

| Where | Copy |
|---|---|
| Subtitle | "Put your lasers in order, left to right. The groups follow the order." |
| Header | "4 outputs · 3 on". With none on: "Tick On on a card to send it output." |
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

- **The list**: All, the five automatic groups, LASERS (one per laser, in order), YOUR GROUPS (by name). The number is how many lasers the group reaches. `#zone-groups-add-button` stays.
- **The middle** shows the lasers in rig order with the same marks as the rows.
  - For your groups the boxes toggle: "Click a laser to add it to Graphics or take it out. The boxes under the cards on the Projectors tab are the same ones."
  - For All a click excludes or includes.
  - The automatic groups are read only, with one sentence that says why (table below).
  - Virtual projectors stay listed under the strip with their toggles, headed "Virtual projectors (not sent to any laser yet)".
- **Details**: the colour, name and description, "Used by n cues" (Z0), and for your groups [Edit…] (the existing dialog) and [Delete…] (Z0's confirm). For derived groups: "Automatic: can't be renamed or deleted."

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
10. The refs that make a laser draw a cue are the refs that make that cue an audience cue when the laser scans the audience.
11. `routing.rig` is pure. With the same projectors and `[:rig]` it gives the same groups, and `refs-for` returns the identical value until either changes.

## 9. Tests

Fixtures to reuse:
- `test/laser_show/events/projector_handlers_test.clj:42-64` (`state-with-projector`) and `:317-319` (`through-pipeline`);
- `test/laser_show/events/output_sync_test.clj:17-34` (`streaming-state`, `run`);
- `test/laser_show/backend/multi_engine_test.clj:20-35` (`setup-test-state`, `snapshot`);
- `test/laser_show/state/persistent_test.clj:40-71` (`init-test-state!`), and `:251-257` for an old file (`update-in` over `persistent/project-files`);
- `test/laser_show/vibes/library_test.clj:47-49` (`with-projector`);
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
  - `set-service-enabled-finds-a-moved-laser-test`, `new-projectors-are-in-all-without-listing-it-test`, `set-exclude-from-all-test`, `set-tag-keeps-a-set-test` (from a vector too), `toggle-refuses-derived-groups-test`, `host-edit-moves-a-host-identity-test`;
  - `handle-projectors-toggle-zone-group-test` (`:68-94`) no longer expects `:all` in the list.
- `laser-show.events.rig-handlers-test` (NEW): `move-before-and-after-a-card-test`, `first-move-writes-the-whole-order-test`, `nudge-at-the-ends-does-nothing-test`, `set-middle-ignores-unknown-values-test`, `split-width-is-clamped-test`, `split-width-drag-is-one-undo-step-test`, `reorder-is-undoable-and-unsaved-test`, `reorder-requests-no-engine-refresh-test`.
- `laser-show.events.history-handlers-test/history-labels-name-real-events-test` (existing) covers the new labels.
- `laser-show.backend.multi-engine-test` (existing): `get-projector-zone-groups-test` (`:254-270`) expects the derived lists; NEW `projector-draws-all-without-listing-it-test`, `excluded-projector-skips-all-test`, `one-laser-draws-left-and-right-whole-test` (pins Z2's behaviour, so Z3 changes it on purpose), `reorder-reroutes-left-test`.
- `laser-show.vibes.library-test` (existing):
  - `audience-cell-test` (`:51-86`): its "not in All" projectors gain `:exclude-from-all? true` (`:55`, `:61-62`, `:85-86`), and the `audience-groups` set at `:85-86` adds that laser's derived refs;
  - NEW `crowd-laser-in-all-makes-all-audience-test`, `excluded-crowd-laser-leaves-all-alone-test`, `crowd-laser-at-the-left-end-makes-left-audience-test`.
- `laser-show.subs-test` (existing): `rig-cards-test`, `rig-matrix-marks-test` (◧ ◨ for one laser and for the split middle; × excluded; – off), `target-groups-order-test`, `zone-group-usage-counts-derived-refs-test`.
- `laser-show.state.persistent-test` (existing): `init-test-state!` (`:66-71`) gets `:zone-groups []` and `:exclude-from-all? false`, so `test-load-project` (`:148-186`) and `test-round-trip` (`:188-208`) stay identities; NEW `rig-is-saved-with-the-hardware-test`, `old-show-loads-clean-test`.
- `laser-show.state.history-test` (existing): `rig-is-undoable-content-test`, `restore-document-restores-the-rig-test`.

UI tests:

- `laser-show.ui-test.rig-strip-test` (NEW; `with-toolkit` once, `with-app` each):
  - `scan-shows-a-card-per-output-in-ip-order-test`, `card-on-box-enables-the-output-test`, `no-output-device-gets-a-card-test`;
  - `drop-a-card-before-another-test` (`fire-drop!` on the left half), `alt-arrow-moves-the-selected-card-test`, `move-buttons-follow-the-ends-test`;
  - `rows-follow-the-order-test` (3 lasers, split: ◧ ◨ on the middle), `middle-laser-choice-changes-the-rows-test`, `custom-row-toggles-membership-test`, `all-row-click-excludes-test`;
  - `audience-tag-offers-exclude-from-all-test`.
- `laser-show.ui-test.zone-groups-tab-test` (NEW): `list-has-all-automatic-lasers-and-yours-test`, `automatic-group-is-read-only-and-explained-test`, `custom-group-boxes-toggle-in-rig-order-test`, `both-tabs-show-the-same-membership-test`, `row-name-opens-the-group-test`.
- `laser-show.ui-test.setup-dialogs-test` (existing): `untick-a-discovered-output-disables-it-test` (`:128-143`) and `single-output-device-renders-test` (`:157-171`) keep their names and tick `#rig-card-enable-<id>` instead of `#discovered-output-…`, which is gone. `rename-projector-test`, `projector-port-label-shows-whole-test` and `calibration-brightness-slider-test` pass unchanged (the inspector keeps the ids).
- `laser-show.ui-test.streaming-status-test/projector-dot-shows-status-test`, `laser-show.ui-test.main-window-test/top-level-tabs-update-content-and-preview` and `zone-group-editor-uses-theme-classes-test` pass unchanged.

Run, targeted first (`targeted-ui-testing`):
1. `clj -M:mac-arm:test -n laser-show.routing.rig-test -n laser-show.state.migration-test -n laser-show.events.rig-handlers-test -n laser-show.events.projector-handlers-test -n laser-show.backend.multi-engine-test -n laser-show.vibes.library-test -n laser-show.subs-test -n laser-show.state.persistent-test -n laser-show.state.history-test -n laser-show.events.output-sync-test -n laser-show.events.zone-groups-handlers-test -n laser-show.events.history-handlers-test`
2. `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.rig-strip-test`, then `-n laser-show.ui-test.zone-groups-tab-test`, then `-n laser-show.ui-test.setup-dialogs-test -n laser-show.ui-test.streaming-status-test -n laser-show.ui-test.main-window-test -n laser-show.ui-test.preview-test`
3. The full suites: `clj -M:mac-arm:test`, then `clj -M:test-ui:mac-arm:test-ui-headless`.

## 10. Commit plan

Each step is one commit: the named tests first (run them and record them red), then the code, then the step's tests and the touched namespaces.

1. `feat(routing): a pure rig namespace: projectors in left-to-right order (the stored order, then the rest by IP and name), the automatic groups All, Left, Right, Center, Odd, Even and one per laser folded from it as spec §3 says (halves for one laser and the split middle), and a stable id from the unit id and the output`. Tests: `rig-test`. Nothing is wired yet.
2. `feat(rig): the rig order, the middle laser and the split width are saved with the hardware and undoable`. The domain, history, persistence. Tests: `rig-is-saved-with-the-hardware-test`, `rig-is-undoable-content-test`, `restore-document-restores-the-rig-test`.
3. `feat(projectors): a laser that comes back on a new IP keeps its name, calibration, groups and place: a scan matches it by its unit id and output, moves it and says so, and a new unit never takes a moved laser's id`. Tests: the scan tests and `set-service-enabled-finds-a-moved-laser-test`.
4. `feat(routing): every enabled projector plays All without listing it (Exclude from All opts out), the engines also draw Left, Right, Center, Odd, Even and one group per laser (whole lasers for now), and old shows load converted: All ticks become Exclude from All, empty built-in Left, Right and Center become the automatic ones, filled ones stay as "Left (manual)"`. The migration and the routing switch land **together**: either alone would move content in old shows. Tests: `migration-test`, the engine tests, the vibe audience tests, `old-show-loads-clean-test`.
5. `feat(ui): every zone picker lists All, the automatic groups and each laser before your groups, with a lock on the automatic ones`. Tests: `target-groups-order-test`; the cue chain and preview UI tests unchanged.
6. `feat(rig): the Projectors tab shows the lasers as cards in rig order: drag a card onto another, or select it and press ◀ ▶ or Alt+←/→; each card turns its output on and shows its status`. Tests: `rig-handlers-test`, `rig-strip-test` (cards, reorder), the two moved setup tests.
7. `feat(rig): group rows under the cards: the automatic groups locked and coloured, with half marks for split lasers, All clickable to exclude a laser, your groups with a box per laser, and the middle-laser and split-width settings beside them`. Tests: `rig-matrix-marks-test`, the rows tests in `rig-strip-test`.
8. `feat(projectors): the selected card's identity, its Graphics and Scans the audience tags (which vibes read) and Exclude from All, with an offer to exclude an audience laser that's still in All`. Tests: `audience-tag-offers-exclude-from-all-test`, `set-tag-keeps-a-set-test`.
9. `feat(zones): the Zone Groups tab starts from the group: All, the automatic groups, each laser and yours, with their lasers in rig order, ticked for your groups and explained for the automatic ones`. Tests: `zone-groups-tab-test`.
10. `plans: Z2 done (<sha>)`: as built, deviations, tests, red-first results and verification, written back into this doc.

## 11. Out of scope / alternatives considered

- **Drawing halves, thumbnails, the load meter and low-fps badges**: Z3. Z2 stores the middle policy and the split width, shows the marks, and leaves an empty thumbnail section in each card.
- **Identify and the test pattern**: Z1. The inspector keeps today's calibration controls.
- **From `08-rig-setup-ux-future.md`**, with the hooks Z2 leaves:
  - rows and the 2D map (R-6, R-9): order lives in one place, `effective-order`;
  - per-tag groups (R-7): `routing.rig/ref-entries` is where `:auto/tag-…` would go;
  - a bracket for a multi-head device (R-5): the cards carry the unit id;
  - ghost cards and a tray (R-19): the cards carry "not found in the last scan";
  - delete and ignore (R-18), the lock (R-25), multi-select (R-16), guessing the order from names (R-3, `placement-key`).
- **Changing the projector ids to the stable id**: rejected. Every chain, selection, engine registry entry, pin and test refers to the id, and ids are opaque anyway. Keeping them and adding `:stable-id` costs nothing (S-7).
- **Storing the automatic groups**: rejected (spec invariant 7). They would go stale the moment a card moves.
- **One row per laser in the rows**: a diagonal of ■. One "Each laser" row says the same thing.
- **A separate Rig tab replacing both tabs**: D-15 keeps both.
- **The UX plan.** Its Phase 3 (not built at `90e35c7`) rewords the Zone Groups subtitle (`views/tabs/zones.clj:336-337`); Z2's text replaces that line, so whichever lands second adapts. Phase 2's Port: fix (`views/tabs/projectors.clj:422-425`) moves into the inspector unchanged.
- **Noticed, not done**: `parse-scan-response` trims the host name with `str/trim` (`idn/hello.clj:384`), which keeps NUL padding. Read, not seen on hardware. The cards show the host name only as a fallback name.

## 12. Spec questions and open questions

### Spec questions (the code and 01 disagree, or 01 is unclear; what this doc chose)

- **S-1 · The join order before Z3.** Spec §6 sorts a bucket's keys by `(juxt namespace name)`. Z2 still joins by list. **Chosen:** `:all` first, then the stored groups in stored order, then the automatic ones. That's today's order for every list that starts with `:all` (the default). Z3's sort replaces it. The set of frames is the contract (invariant 6), not their order.
- **S-2 · §11 row 2 changes routing.** Content aimed at an empty built-in Left reached no laser. After the conversion it reaches the automatic Left. That breaks D-22's "route exactly as before" for that one case. **Chosen:** follow §11, because it is what the author meant and no one could see that content. The fixture test checks the new routing for that row and identical routing for every other row.
- **S-3 · Which "enabled".** §3 says enabled. The engines read a missing `:enabled?` as on (`backend/multi_engine.clj:220`); the discovery sub reads it as off (`subs.clj:534-544`). **Chosen:** the engines' rule, in one helper, `rig/enabled?`.
- **S-4 · Excluded lasers in the automatic groups.** §3 used to fold E as every enabled laser, so an excluded laser still counted in Left, Odd and so on. **Updated 2026-11-19:** the spec now follows Q-Z2-1 B. E leaves out the excluded lasers, and each of them keeps only its own `:auto.laser/*` group and its custom groups. §4's `auto-groups` and §8's example follow this.
- **S-5 · `:auto.laser/<id>` of a disabled laser** resolves to no entries (E only). The pickers still list it, marked "(off)".
- **S-6 · Marks before drawing.** The rows show ◧ ◨ (what §3 defines), while Z2's engine draws whole. **Chosen:** show the marks, with a tooltip that says the whole laser draws it for now.
- **S-7 · Ids.** §1 says a projector "is matched by its stable id". **Chosen:** the `:projectors` keys stay as they are and gain `:stable-id`. A new projector's id stays host-based but is made unique.
- **S-8 · Virtual projectors** aren't in the spec. **Chosen:** they get the All conversion and stay listed in the Zone Groups tab. They are not in the rig order (no engine draws them).
- **S-9 · All's definition.** §1 keeps the id `:all` "for old files". **Chosen:** the id stays a ref, and its stored definition goes. All is built in, like the automatic groups.
- **S-10 · "No members"** (§11) counts projectors and virtual projectors, enabled or not.
- **S-11 · "No `[:rig :order]`"** (§11). **Chosen:** the order is derived when read (`effective-order`) and never written by the conversion. The same function places new lasers.
- **S-12 · A deleted built-in still named by cues** (B-5's dangling references): left as is. It routes nowhere, as before. Z0 owns dangling references.

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
