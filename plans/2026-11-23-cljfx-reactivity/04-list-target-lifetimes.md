# 04 · List callbacks follow the list being shown

**Status:** Implemented in `517b96e` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit row "Delete can operate on the previously displayed effect chain".

Behaviour at 7b320b3:
- events/handlers/effects.clj `handle-effect-chain-open-editor` merges `{:open? true :col :row …}` into `[:ui :dialogs :effect-chain-editor]`; views/root.clj renders open dialogs keyed only by dialog id; views/dialogs/effect_chain_editor.clj gives its list-editor `:component-id [:effect-chain col row]` with no per-cell key → opening another cell retargets the SAME JavaFX list node.
- views/components/list.clj: `list-editor` installs `setup-keyboard-handlers! node component-id` in `:on-created`, capturing the first component-id; the key filter reads `(get-handler-props component-id)` from the FX-thread registry `!component-handler-props`, which `list-sidebar` writes during render and `:on-deleted` removes.
- Reproduced (headless, last section of plans/probes/cljfx_state_outcomes_2026_11_23.clj): A=[0 0] and B=[1 0] each hold one effect; open A, then B in the same window; focus B's list; Delete → item counts [A B] went `[1 1] → [0 1]`.
- Inverse failure: close and reopen the same cell → `:on-deleted` removed the registry entry, but cljfx can reuse the cached sidebar description without re-running `list-sidebar` (the only writer) → the key filter sees nil props and ignores keys.
- Cue Chain Editor's lower "item effects" list: after switching between two presets that have effects, the container's background drop handler still targets the first preset.

Required design:
- No render-time registry. The list node carries its current command address declaratively (`:user-data {:component-id … :items-path … :on-change-event … :on-change-params …}` on the list-editor root, like views/components/commit_field.clj), and listeners installed once read it at input time. Row/container callbacks installed in `:on-created` read current data at event time (list.clj row handlers, views/components/list_dnd.clj).
- Handlers resolve selection and items from their `:state` via `:items-path`; Ctrl+A and Shift-click ranges compute ordered ids in the handler instead of rendered `:ordered-ids`/`:all-ids`.
- Preserve keyboard phase-3 behaviour (`keys/list-key-target?`, `keys/claim-keys!`, clipboard type from the component id, `request-list-focus!`).

Tests (NEW): retarget A→B then Delete deletes only B's item (`[1 0]`); close/reopen the same cell then Delete still deletes; Ctrl+A/Shift-click range after retarget selects the displayed list's items; cue editor item-effects background drop after switching presets lands in the currently selected preset.

Boundaries: 31, 32, 33, 34 follow in the same lane (renderer wiring, read APIs, context retention, chain editor subscriptions).

## As implemented

**Changes**
- `views/components/list.clj`: props registry and `:on-deleted` removed. The list editor root carries `:user-data {::address {:component-id :items-path :on-change-event :on-change-params}}`. NEW `list-address` walks up from a node to that root at input time; key filter, `setup-click-handler!`, rows' drag source/target use it. Background drop set up by NEW `setup-background-drop!`, also run in `:on-advanced` (covers the node replacing the empty text when a list fills/empties).
- `views/components/list_dnd.clj`: the three setup fns take a no-arg fn returning the address, called per event. `handle-drop!` takes the address map. A drag ends in the list it started in.
- `events/handlers/list.clj`: Ctrl+A and Shift-click ranges get order only from the handler's `:state`; stale `:all-ids`/`:items` in an event ignored.
- `subs.clj`: `list-ordered-ids` deleted (registry was its only caller).
- Timeline track header (`visual_editors/timeline/header.clj`): column reads its cell from userData (had the same bug); regression test added.

**Tests**: `list-keys-work-after-the-editor-reopens-test` remounts with `cache/basic-cache-factory` (with the 512 LRU the close/reopen failure doesn't reproduce: Grid renders evict the editor's cached render) — reusable for the 04→36 check. Extra: `item-effect-rows-follow-their-preset-when-it-moves-test`.

**Left for others**: `get-ui-state` / `get-selected-ids` still read state at event time (now with the current component id). Range inputs `:selected-ids-override` / `:last-id-override` remain (no src sender). `:list/perform-drop` still carries `:dragging-ids`.

**Notes**
- 31: nothing in its area changed.
- 32: in list.clj / list_dnd.clj migrate the reads inside `get-ui-state` but keep the address lookup at event time; don't capture ids at install time. The row drop reads the drag ids twice (self-drop check, then `handle-drop!`) — the single-read change goes there.
- 33: the `:user-data` address holds no context. `list-sidebar` now passes its whole incoming `props` (incl. injected `:fx/context`) to rows, the group toolbar and button handlers; `sidebar-props` binding is gone — that's where the context-free props map belongs. Timeline header no longer threads `:items-path` through props. Don't bring back the registry or ordered-ids snapshot.
- 34: `list-ordered-ids` no longer exists. Rows need no address props (handlers walk up to the list root), so row props can be narrowed freely as long as the list editor root keeps its `:user-data`. Row handlers capture only item id and group-ness, so changing row keys is safe.
- 36/38: 04→36 check can reuse the reopen test. 38 will touch `header-row`'s `:on-created` near 04's edits; the bank drop itself is unchanged.
