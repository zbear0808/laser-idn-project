(ns laser-show.state.protocols
  "State access protocols for dependency injection.
   
   These protocols define the contract for state access, enabling:
   - Easier testing with mock implementations
   - Clear API boundaries
   - Potential for alternative storage backends
   
   Usage:
   ;; Production code uses the atom-based implementation
   (def state-provider (create-atom-state-provider))
   
   ;; Test code can use a mock implementation
   (def mock-provider (create-mock-state-provider {:cells {...}}))
   
   ;; Functions accept the provider as first argument
   (get-cell state-provider 0 0)")

;; ============================================================================
;; Grid State Protocol
;; ============================================================================

(defprotocol IGridState
  "Protocol for grid state access."
  (get-cell [this col row] "Get cell at position, returns cell data or nil")
  (set-cell! [this col row data] "Set cell data at position")
  (clear-cell! [this col row] "Clear cell at position")
  (get-all-cells [this] "Get all cells as map of [col row] -> data")
  (get-selected-cell [this] "Get selected cell [col row] or nil")
  (set-selected-cell! [this col row] "Set selected cell")
  (get-grid-size [this] "Get grid size as [cols rows]"))

;; ============================================================================
;; Playback State Protocol
;; ============================================================================

(defprotocol IPlaybackState
  "Protocol for playback state access."
  (playing? [this] "Check if currently playing")
  (get-active-cell [this] "Get active cell [col row] or nil")
  (set-active-cell! [this col row] "Set active cell")
  (get-trigger-time [this] "Get trigger timestamp")
  (trigger! [this] "Trigger playback, setting current time")
  (stop-playback! [this] "Stop playback"))

;; ============================================================================
;; Timing State Protocol
;; ============================================================================

(defprotocol ITimingState
  "Protocol for timing/BPM state access."
  (get-bpm [this] "Get current BPM")
  (set-bpm! [this bpm] "Set BPM")
  (get-beat-position [this] "Get beat position 0.0-1.0")
  (get-tap-times [this] "Get tap-tempo timestamps")
  (add-tap-time! [this timestamp] "Add tap-tempo timestamp")
  (clear-tap-times! [this] "Clear tap-tempo timestamps"))

;; ============================================================================
;; Effects State Protocol
;; ============================================================================

(defprotocol IEffectsState
  "Protocol for effects grid state access."
  (get-effect-at [this col row] "Get effect at position")
  (set-effect-at! [this col row effect] "Set effect at position")
  (clear-effect-at! [this col row] "Clear effect at position")
  (get-active-effects [this] "Get all active effects"))

;; ============================================================================
;; Combined State Protocol
;; ============================================================================

(defprotocol IAppState
  "Combined protocol for full application state.
   Implementations can delegate to specialized providers."
  (grid-state [this] "Get grid state provider")
  (playback-state [this] "Get playback state provider")
  (timing-state [this] "Get timing state provider")
  (effects-state [this] "Get effects state provider"))

;; ============================================================================
;; Atom-based Implementation
;; ============================================================================

(defrecord AtomGridState [!grid]
  IGridState
  (get-cell [_ col row]
    (get-in @!grid [:cells [col row]]))
  (set-cell! [_ col row data]
    (swap! !grid assoc-in [:cells [col row]] data))
  (clear-cell! [_ col row]
    (swap! !grid update :cells dissoc [col row]))
  (get-all-cells [_]
    (:cells @!grid))
  (get-selected-cell [_]
    (:selected-cell @!grid))
  (set-selected-cell! [_ col row]
    (swap! !grid assoc :selected-cell (when (and col row) [col row])))
  (get-grid-size [_]
    (:size @!grid)))

(defrecord AtomPlaybackState [!playback]
  IPlaybackState
  (playing? [_]
    (:playing? @!playback))
  (get-active-cell [_]
    (:active-cell @!playback))
  (set-active-cell! [_ col row]
    (swap! !playback assoc :active-cell (when (and col row) [col row])))
  (get-trigger-time [_]
    (:trigger-time @!playback))
  (trigger! [_]
    (swap! !playback assoc :trigger-time (System/currentTimeMillis)))
  (stop-playback! [_]
    (swap! !playback #(-> % (assoc :playing? false) (assoc :active-cell nil)))))

(defrecord AtomTimingState [!timing]
  ITimingState
  (get-bpm [_]
    (:bpm @!timing))
  (set-bpm! [_ bpm]
    (swap! !timing assoc :bpm (double bpm)))
  (get-beat-position [_]
    (:beat-position @!timing))
  (get-tap-times [_]
    (:tap-times @!timing))
  (add-tap-time! [_ timestamp]
    (swap! !timing update :tap-times conj timestamp))
  (clear-tap-times! [_]
    (swap! !timing assoc :tap-times [])))

(defrecord AtomEffectsState [!effects]
  IEffectsState
  (get-effect-at [_ col row]
    (get-in @!effects [:active-effects [col row]]))
  (set-effect-at! [_ col row effect]
    (swap! !effects assoc-in [:active-effects [col row]] effect))
  (clear-effect-at! [_ col row]
    (swap! !effects update :active-effects dissoc [col row]))
  (get-active-effects [_]
    (:active-effects @!effects)))

(defrecord AtomAppState [grid playback timing effects]
  IAppState
  (grid-state [_] grid)
  (playback-state [_] playback)
  (timing-state [_] timing)
  (effects-state [_] effects))

;; ============================================================================
;; Constructor Functions
;; ============================================================================

(defn create-atom-grid-state
  "Create an atom-based grid state provider.
   Parameters:
   - !grid: Grid atom"
  [!grid]
  (->AtomGridState !grid))

(defn create-atom-playback-state
  "Create an atom-based playback state provider.
   Parameters:
   - !playback: Playback atom"
  [!playback]
  (->AtomPlaybackState !playback))

(defn create-atom-timing-state
  "Create an atom-based timing state provider.
   Parameters:
   - !timing: Timing atom"
  [!timing]
  (->AtomTimingState !timing))

(defn create-atom-effects-state
  "Create an atom-based effects state provider.
   Parameters:
   - !effects: Effects atom"
  [!effects]
  (->AtomEffectsState !effects))

(defn create-atom-app-state
  "Create a complete atom-based app state provider from atoms.
   Parameters:
   - atoms: Map with :!grid, :!playback, :!timing, :!effects keys"
  [{:keys [!grid !playback !timing !effects]}]
  (->AtomAppState
   (create-atom-grid-state !grid)
   (create-atom-playback-state !playback)
   (create-atom-timing-state !timing)
   (create-atom-effects-state !effects)))

;; ============================================================================
;; Mock Implementation (for testing)
;; ============================================================================

(defrecord MockGridState [!state]
  IGridState
  (get-cell [_ col row]
    (get-in @!state [:cells [col row]]))
  (set-cell! [_ col row data]
    (swap! !state assoc-in [:cells [col row]] data))
  (clear-cell! [_ col row]
    (swap! !state update :cells dissoc [col row]))
  (get-all-cells [_]
    (:cells @!state))
  (get-selected-cell [_]
    (:selected-cell @!state))
  (set-selected-cell! [_ col row]
    (swap! !state assoc :selected-cell (when (and col row) [col row])))
  (get-grid-size [_]
    (:size @!state)))

(defrecord MockPlaybackState [!state]
  IPlaybackState
  (playing? [_]
    (:playing? @!state))
  (get-active-cell [_]
    (:active-cell @!state))
  (set-active-cell! [_ col row]
    (swap! !state assoc :active-cell (when (and col row) [col row])))
  (get-trigger-time [_]
    (:trigger-time @!state))
  (trigger! [_]
    (swap! !state assoc :trigger-time (System/currentTimeMillis)))
  (stop-playback! [_]
    (swap! !state #(-> % (assoc :playing? false) (assoc :active-cell nil)))))

(defn create-mock-grid-state
  "Create a mock grid state for testing.
   Parameters:
   - initial-state: Map with :cells, :selected-cell, :size keys"
  [initial-state]
  (->MockGridState (atom (merge {:cells {} :selected-cell nil :size [8 4]} 
                                initial-state))))

(defn create-mock-playback-state
  "Create a mock playback state for testing.
   Parameters:
   - initial-state: Map with :playing?, :active-cell, :trigger-time keys"
  [initial-state]
  (->MockPlaybackState (atom (merge {:playing? false :active-cell nil :trigger-time 0}
                                    initial-state))))

;; ============================================================================
;; Default State Provider (uses global atoms)
;; ============================================================================

(defn create-default-state-provider
  "Create a state provider using the global atoms from state.atoms.
   This is the default provider for production use."
  []
  (require '[laser-show.state.atoms :as atoms])
  (create-atom-app-state
   {:!grid (resolve 'laser-show.state.atoms/!grid)
    :!playback (resolve 'laser-show.state.atoms/!playback)
    :!timing (resolve 'laser-show.state.atoms/!timing)
    :!effects (resolve 'laser-show.state.atoms/!effects)}))
