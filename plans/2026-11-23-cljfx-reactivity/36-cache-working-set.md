# 36 · Cache working set

**Status:** Implemented in `3b9f6921` on `reactivity/integration` (2026-11-24); see [implementation status](00-overview.md#implementation-status-2026-11-24).

No plan page was written before implementation; the design brief the implementer worked from and its hand-off report are recorded here. Paths are relative to `src/laser_show/` unless they say otherwise, and citations refer to the code at the time of writing.

## Design brief

Audit row: "Current LRU loses the starter Grid and Vibes working sets" (section "Subscription hot spots that were measured"). Overview 00-overview.md: decision to confirm — "36 — Production cache capacity. This is an implementation measurement, not a value to approve in advance. A comparison cache fixed call counts but says nothing by itself about acceptable retention. Ship the new value only after the cross-view measurements and retention checks." and "For the memory and CPU checks, record the workload, warm-up, LRU capacity, total view-body calls and changed results separately. Use the same workload before/after; keep the safety of data and target identity as acceptance gates even if a change improves the counters. A cold cache, a deliberately stale fixture, or a diagnostic that still prints today's bad outcome is not evidence that the repair has landed."

Baseline at 7b320b3 (plans/probes/cljfx_reactivity_2026_11_23.clj, lines ~73–103): starter Grid, five unrelated database updates → 512 entries: view calls `144,149,149,149,149`; 4,096 entries: `91,0,0,0,0`. Production LRU: `state/core.clj` `cache-factory` (512 threshold).

What has landed since (all on this branch): 04 (list callbacks read node `:user-data`, no render-time registry), 31 (renderer options; dev-mode validation — MEASURE WITHOUT `:dev`), 32 (snapshot reads no longer add whole-map entries to the cache), 33 (no `:fx/context` child props; reusable helpers `reachable-cache-atoms`, `function-components`, `given`, `with-context` in `test/laser_show/views/child_context_retention_test.clj`), 34 (chain editor subscriptions + the "04/33/34 list → 36 cache" integration test), 35 (grid fan-out), 37 (derived input costs), 12/28/29/30 selector changes.

### Required
1. Measure on the CURRENT branch with a mounted headless app (`app/start-test!` / test fixtures; no hardware, no `:dev`), for each capacity in {512, 1024, 2048, 4096, 8192} (stop early if counts are already 0 at a smaller one, but always include 512 and the chosen value):
   - (a) starter Grid tab warm (render once, then the same five unrelated DB updates the probe uses);
   - (b) Vibes tab warm, five unrelated updates;
   - (c) an open cue chain editor and an open effect chain editor (non-modal) over the Grid, five unrelated cell edits elsewhere;
   - (d) Perform tab warm (pads), five unrelated updates;
   - (e) the combination that is realistic during a show: Grid + one open editor, with show-clock-like updates (BPM/beat/notice) — whatever the app actually writes to the DB per beat (check `services/show_clock.clj`; frames are not in the DB).
   For each: warm-up procedure, capacity, total view-body calls per update (instrument like the probe does: wrap function components / count body calls), number of cache entries after warm-up (working-set size), and whether results changed (they must not for unrelated updates).
2. Retention: confirm the cache stays bounded and old contexts aren't retained (use `reachable-cache-atoms`-style checks: after N updates, reachable cache atoms / generations don't grow with N).
3. Choose the production capacity from the data: the smallest power of two that gives repeatable selective rendering (0 unrelated view-body calls after warm-up, or a small, explained constant) across ALL workloads, with headroom ≥ 2× the largest measured working set. Explain the choice in the `cache-factory` docstring (workloads, counts, working-set sizes) — short.
4. Regression test: a unit or headless UI test that warms the starter Grid (and an editor if feasible) with the production cache factory and asserts unrelated updates cause 0 (or the explained constant) view-body calls and that the working set stays below half the capacity. Keep it fast and deterministic.
5. Re-run the 04/33/34 integration test (`list-keys-work-after-the-editor-reopens-test` and 34's new one) with the PRODUCTION cache factory too (not only `basic-cache-factory`), if feasible.
6. Update `plans/probes/cljfx_reactivity_2026_11_23.clj`? NO — plans/ is read-only; put the measurement table in your report instead.

Tests to keep passing: subs, state core, renderer-wiring, child-context-retention, grid/perform/vibes/cue-chain-list/dialogs UI tests.

### Notes from 35 (grid fan-out, 780f41a)
- Starter Grid tab cache entries grew from 869 to 966 (three booleans per visible cell + one shared index). Both exceed 512 — at 512 the whole Grid tab (221 calls) re-renders on every edit, before and after 35.
- On a mounted tree, advance once after creating it before measuring: the grid's size listener changes its local state, so the first edit otherwise re-renders the grid at a new size (likely the probe's first-change count of 91).
- 35's mounted starter Grid tab counts at 4,096 for (unrelated, cue selection, Vibe key, Vibe learn, cue learn, cue key): 0, 4, 0, 0, 2, 2 (were 0, 4, 32, 32, 33, 33).
- `effects-grid-empty?`, `zone-patterns-in-use?`, `map-cycles?` still walk the whole effects grid on any effects-chain change (selector cost, not cell re-render).

### Notes from 34 (chain editor subscriptions, e5e0096d) and 37 (derived costs, 8315b073)
- 34 counts (LRU 512) for the open editors: another cell's rename/add → 0 body calls (was 1 / 59); turning one item off → 5 (was 17 / 60); see 34's report. Each cue row adds two cache entries (its `item-target-menu` component + its small subscription). Row descriptions are much smaller (cheaper keys). Shared harness: `test/laser_show/views/harness.clj` (body counting per render, `reachable-cache-atoms`, `function-components`, `given`, `with-context`).
- 37 counts: starter show cue-label subs + lint + checklist + edited reactions + Playing list: 237 → 165 entries.
- Still to merge after you start (re-check at the end by merging reactivity/integration into your branch and re-running your measurement script): 17 (registry descriptors: reaction selectors depend on a published registry value), 24 (learn attempts: learn selectors), 27 (gamepad/fader sampling: no UI), 38 (drag payload: no subscriptions).

## As implemented

**Change**: `src/laser_show/state/core.clj` `cache-factory` keeps 4096 entries (docstring records the workloads, counts and working-set sizes). NEW `test/laser_show/ui_test/cache_working_set_test.clj`: opens the starter Grid with both chain editors using the production cache; five unrelated changes run no view body; one render uses less than half the cache; renaming another cell runs only that cell's views (fails at 512: 370 view runs per change). `cue_chain_list_test.clj`: `list-keys-work-after-the-editor-reopens-test` and `a-reopened-list-keeps-its-rows-and-deletes-in-its-cell-test` also run with the production cache (reopening runs 2 view bodies at 4096 vs 532 at 512). Wording fixes in `views/harness.clj` (`lru-512`) and the context-retention test.

**Measurements** (mounted app, no `:dev`; warm-up: mount, set up the screen, settle, one unrelated change; working set = cache entries one steady render looks up; counts = view bodies run per update):

| Workload | Working set (final / 7b320b3) | 512 | 1024 | 2048 | 4096 | 8192 |
|---|---|---|---|---|---|---|
| a. Grid, 5 unrelated changes | 817 / 632 | 226 each (7b320b3: 154) | 0 | 0 | 0 | 0 |
| b. Vibes, 5 unrelated | 602 / 681 | 181 | 0 | 0 | 0 | 0 |
| c. Grid + both editors, 5 unrelated | 977 / 740 | 370 | 0 | 0 | 0 | 0 |
| c. same, 5 renames of other cells | | 370 | 2 | 2 | 2 | 2 (7b320b3: 21) |
| d. Perform, 5 unrelated | 296 / 256 | 0 | 0 | 0 | 0 | 0 |
| e. Grid + cue editor, B1 playing; 3 clock status updates + BPM + notice | 926 / 701 | 319 | 3,2,2,2,2 | same | same | same |
| f. Effects tab | 525 / 327 | 142 | 0 | 0 | 0 | 0 |
| g. Full grid + both editors on longer chains | 1036 / 752 | 419 | 379 | 0 | 0 | 0 |
| Projectors / Zones / Settings tabs | 172 / 215 / 188 | 0 | 0 | 0 | 0 | 0 |

Below the working set, 39–90 re-run views per update returned new event handlers (swapped on controls each time). Small counts: a rename runs that cell's two views; a beat redraws the beat indicator and one or two beat squares; BPM redraws the BPM display and field; a notice redraws the notice and its icon.

**Retention** (300 clock-style updates; separately 3000 renames of one cell): on the final tree, at every size, the cache reaches no other cache and no earlier cache version survives GC; it holds stale view descriptions up to its size (~16 KB per grid cell): 48 MB after 3000 renames at 4096 vs 19 MB at 2048; the clock run left heap flat (~+2 MB). At 7b320b3 the same renames with ≥2048 entries kept 2999 of 3000 earlier caches alive (heap 1.5–1.7 GB) — raising the size was only safe after 33.

**Choice**: 4096 = smallest power of two ≥ 2× the largest working set (2 × 1036 = 2072) with zero unrelated view runs everywhere. 2048 also gives zero (1.98× headroom); 1024 fails workload g. If memory matters more than headroom, 2048 halves the worst case for stale descriptions.

**Notes**: the full UI suite is the real check now (views reading changing values outside their subscriptions used to be refreshed by eviction); one found: a view reads the clock to decide whether to show the date (harmless). ~1–2 MB more heap stays in use after each app start/stop in one JVM (not the cache; not investigated). The measurement script and raw logs were kept outside the repository; the regression test above covers the production claim.
