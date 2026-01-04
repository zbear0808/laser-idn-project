# Bug Recap: 10-Second Freeze on Drag-and-Drop

## Symptom
When dragging and dropping an effect in the **Projector Configuration** tab (and Main Grid), the UI freezes for approximately **10 seconds** immediately after the drop interaction is completed.
*   **Affected**: Projector Tab, Main Grid.
*   **Unaffected**: Effect Chain Editor Dialog (uses legacy drag implementation).
*   **Observed Behavior**: The drag interaction is smooth. The freeze occurs only *after* dropping.

## Timeline of Debugging & Hypotheses

### 1. Hypothesis: Global State Invalidation
**Theory**: The `subs.clj` used `(fx/sub-val context identity)`, causing the entire application to re-subscribe and re-render on every state change (including the "Mark Dirty" flag triggered by dropping).
**Action**: Refactored `subs.clj` to use granular domain subscriptions (e.g., separate `:projectors` and `:grid` domains).
**Result**: **No Change**. The freeze persists, indicating the bottleneck is not the *quantity* of checked subscriptions.

### 2. Hypothesis: Slow Event Handler Logic
**Theory**: The logic responsible for reordering the list (filtering, inserting, rebuilding vector) is computationally expensive or buggy (e.g., O(N^2)).
**Action**: Added timing logs to `events/core.clj`.
**Result**: **Disproven**. The Drop event (`:projectors/move-effects`) is processed in **< 1ms**. The state update is applied in **< 0.1ms**. The logic is extremely fast.

### 3. Hypothesis: Slow View Data Preparation (Subscriptions)
**Theory**: The functions that prepare data for the view (transforming the effect list into UI-ready maps) are slow.
**Action**: Added timing Logs to `subs.clj`.
**Result**: **Disproven**. All subscriptions calculate in **< 0.1ms**.

### 4. Hypothesis: Component Thrashing (Unstable Props)
**Theory**: The "Dispatcher" pattern creates a new function identity on every render. This forces `cljfx` to destroy and re-create the Drag handlers and UI nodes on every frame, causing massive overhead.
**Action**: Added "Attaching drag source..." logs to the component lifecycle.
**Result**: **Inconclusive / Disproven**. The logs *never appeared* triggered by drag operations. This suggests the component is **Stable** (not being re-created), otherwise we would see the logs.

## Current State
*   **Logic**: Fast.
*   **State Updates**: Fast.
*   **Subs**: Fast.
*   **Component**: Appears stable.
*   **UI**: Still freezes for 10s.

## The Mystery
The application freezes *after* the logic returns but *before* the next frame is interactive. The 10-second duration is highly suspicious of a **Network Timeout** or **Thread Block**.

## Next Lead: Hidden Watchers / Side Effects
We need to investigate if the state change triggers a background service or watcher that mistakenly runs on the UI thread.
*   Does changing effects trigger a synchronous IDN packet dispatch?
*   Is there a `add-watch` anywhere that blocks?
