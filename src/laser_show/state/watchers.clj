(ns laser-show.state.watchers
  "State synchronization utilities.
   Provides watchers and helpers to keep different parts of the system in sync."
  (:require [clojure.data :as data]
            [laser-show.state.atoms :as state]
            [laser-show.state.persistent :as persist]))

;; ============================================================================
;; Watcher Management
;; ============================================================================

(defonce !active-watchers (atom {}))

(defn add-watcher!
  "Add a named watcher to an atom. Stores the watcher so it can be removed later."
  [atom-ref watcher-key watcher-fn]
  (add-watch atom-ref watcher-key watcher-fn)
  (swap! !active-watchers assoc [atom-ref watcher-key] watcher-fn))

(defn remove-watcher!
  "Remove a named watcher from an atom."
  [atom-ref watcher-key]
  (remove-watch atom-ref watcher-key)
  (swap! !active-watchers dissoc [atom-ref watcher-key]))

(defn remove-all-watchers!
  "Remove all registered watchers."
  []
  (doseq [[[atom-ref watcher-key] _] @!active-watchers]
    (remove-watch atom-ref watcher-key))
  (reset! !active-watchers {}))

;; ============================================================================
;; Timing Synchronization
;; ============================================================================

(defn watch-bpm-changes
  "Watch for BPM changes and invoke callback with new value"
  [callback]
  (add-watcher! state/!timing ::bpm-watcher
    (fn [_key _ref old-state new-state]
      (when (not= (:bpm old-state) (:bpm new-state))
        (callback (:bpm new-state))))))

(defn unwatch-bpm-changes
  "Stop watching BPM changes"
  []
  (remove-watcher! state/!timing ::bpm-watcher))

(defn watch-beat-position
  "Watch for beat position changes and invoke callback with new position"
  [callback]
  (add-watcher! state/!timing ::beat-position-watcher
    (fn [_key _ref old-state new-state]
      (when (not= (:beat-position old-state) (:beat-position new-state))
        (callback (:beat-position new-state))))))

(defn unwatch-beat-position
  "Stop watching beat position changes"
  []
  (remove-watcher! state/!timing ::beat-position-watcher))

;; ============================================================================
;; Playback Synchronization
;; ============================================================================

(defn watch-playback-state
  "Watch for playback state changes and invoke callback with playing status"
  [callback]
  (add-watcher! state/!playback ::playback-watcher
    (fn [_key _ref old-state new-state]
      (when (not= (:playing old-state) (:playing new-state))
        (callback (:playing new-state))))))

(defn unwatch-playback-state
  "Stop watching playback state changes"
  []
  (remove-watcher! state/!playback ::playback-watcher))

(defn watch-current-animation
  "Watch for current animation changes and invoke callback with new animation"
  [callback]
  (add-watcher! state/!playback ::animation-watcher
    (fn [_key _ref old-state new-state]
      (when (not= (:current-animation old-state) (:current-animation new-state))
        (callback (:current-animation new-state))))))

(defn unwatch-current-animation
  "Stop watching current animation changes"
  []
  (remove-watcher! state/!playback ::animation-watcher))

(defn watch-active-cell
  "Watch for active cell changes and invoke callback with [col row]"
  [callback]
  (add-watcher! state/!playback ::active-cell-watcher
    (fn [_key _ref old-state new-state]
      (when (not= (:active-cell old-state) (:active-cell new-state))
        (callback (:active-cell new-state))))))

(defn unwatch-active-cell
  "Stop watching active cell changes"
  []
  (remove-watcher! state/!playback ::active-cell-watcher))

;; ============================================================================
;; Streaming Synchronization
;; ============================================================================

(defn watch-streaming-state
  "Watch for streaming state changes and invoke callback with running status"
  [callback]
  (add-watcher! state/!streaming ::streaming-watcher
    (fn [_key _ref old-state new-state]
      (when (not= (:running? old-state) (:running? new-state))
        (callback (:running? new-state))))))

(defn unwatch-streaming-state
  "Stop watching streaming state changes"
  []
  (remove-watcher! state/!streaming ::streaming-watcher))

;; ============================================================================
;; Configuration Synchronization
;; ============================================================================

(defn watch-grid-config
  "Watch for grid configuration changes and invoke callback with new grid config"
  [callback]
  (add-watcher! persist/!config ::grid-config-watcher
    (fn [_key _ref old-state new-state]
      (when (not= (:grid old-state) (:grid new-state))
        (callback (:grid new-state))))))

(defn unwatch-grid-config
  "Stop watching grid configuration changes"
  []
  (remove-watcher! persist/!config ::grid-config-watcher))

(defn watch-projectors
  "Watch for projector changes and invoke callback with new projector map"
  [callback]
  (add-watcher! persist/!projectors ::projectors-watcher
    (fn [_key _ref old-state new-state]
      (when (not= old-state new-state)
        (callback new-state)))))

(defn unwatch-projectors
  "Stop watching projector changes"
  []
  (remove-watcher! persist/!projectors ::projectors-watcher))

;; ============================================================================
;; UI Synchronization
;; ============================================================================

(defn watch-selected-preset
  "Watch for selected preset changes and invoke callback with new preset-id"
  [callback]
  (add-watcher! state/!ui ::selected-preset-watcher
    (fn [_key _ref old-state new-state]
      (when (not= (:selected-preset old-state) (:selected-preset new-state))
        (callback (:selected-preset new-state))))))

(defn unwatch-selected-preset
  "Stop watching selected preset changes"
  []
  (remove-watcher! state/!ui ::selected-preset-watcher))

(defn watch-clipboard
  "Watch for clipboard changes and invoke callback with new clipboard content"
  [callback]
  (add-watcher! state/!ui ::clipboard-watcher
    (fn [_key _ref old-state new-state]
      (when (not= (:clipboard old-state) (:clipboard new-state))
        (callback (:clipboard new-state))))))

(defn unwatch-clipboard
  "Stop watching clipboard changes"
  []
  (remove-watcher! state/!ui ::clipboard-watcher))

;; ============================================================================
;; Sync Grid Size Between Dynamic and Persistent
;; ============================================================================

(defn sync-grid-size-to-persistent!
  "Sync grid size from runtime state to persistent config"
  []
  (let [grid-size (state/get-grid-size)]
    (persist/update-config! [:grid] {:cols (first grid-size)
                                      :rows (second grid-size)})))

(defn sync-grid-size-to-runtime!
  "Sync grid size from persistent config to runtime state"
  []
  (let [{:keys [cols rows]} (persist/get-grid-config)]
    (state/set-grid-size! cols rows)))

(defn enable-bidirectional-grid-sync!
  "Enable bidirectional sync between runtime and persistent grid size"
  []
  ;; Watch runtime changes and sync to persistent
  (add-watcher! state/!ui ::grid-size-to-persistent
    (fn [_key _ref old-state new-state]
      (when (not= (get-in old-state [:grid :size])
                  (get-in new-state [:grid :size]))
        (sync-grid-size-to-persistent!))))
  
  ;; Watch persistent changes and sync to runtime
  (add-watcher! persist/!config ::grid-size-to-runtime
    (fn [_key _ref old-state new-state]
      (when (not= (:grid old-state) (:grid new-state))
        (sync-grid-size-to-runtime!)))))

(defn disable-bidirectional-grid-sync!
  "Disable bidirectional grid size sync"
  []
  (remove-watcher! state/!ui ::grid-size-to-persistent)
  (remove-watcher! persist/!config ::grid-size-to-runtime))

;; ============================================================================
;; Multi-state Watchers
;; ============================================================================

(defn watch-all-playback
  "Watch all playback-related state and invoke callback with full playback state"
  [callback]
  (add-watcher! state/!playback ::all-playback-watcher
    (fn [_key _ref _old-state new-state]
      (callback new-state))))

(defn unwatch-all-playback
  "Stop watching all playback state"
  []
  (remove-watcher! state/!playback ::all-playback-watcher))

(defn watch-all-timing
  "Watch all timing-related state and invoke callback with full timing state"
  [callback]
  (add-watcher! state/!timing ::all-timing-watcher
    (fn [_key _ref _old-state new-state]
      (callback new-state))))

(defn unwatch-all-timing
  "Stop watching all timing state"
  []
  (remove-watcher! state/!timing ::all-timing-watcher))

;; ============================================================================
;; Debugging and Monitoring
;; ============================================================================

(defn log-state-changes
  "Add watchers to log all state changes (for debugging)"
  []
  (add-watcher! state/!timing ::debug-timing
    (fn [_key _ref old new]
      (when (not= old new)
        (println "TIMING CHANGE:" (keys (data/diff old new))))))
  
  (add-watcher! state/!playback ::debug-playback
    (fn [_key _ref old new]
      (when (not= old new)
        (println "PLAYBACK CHANGE:" (keys (data/diff old new))))))
  
  (add-watcher! state/!streaming ::debug-streaming
    (fn [_key _ref old new]
      (when (not= old new)
        (println "STREAMING CHANGE:" (keys (data/diff old new))))))
  
  (add-watcher! state/!input ::debug-input
    (fn [_key _ref old new]
      (when (not= old new)
        (println "INPUT CHANGE:" (keys (data/diff old new))))))
  
  (add-watcher! state/!ui ::debug-ui
    (fn [_key _ref old new]
      (when (not= old new)
        (println "UI CHANGE:" (keys (data/diff old new)))))))

(defn stop-logging-state-changes
  "Remove debug watchers"
  []
  (remove-watcher! state/!timing ::debug-timing)
  (remove-watcher! state/!playback ::debug-playback)
  (remove-watcher! state/!streaming ::debug-streaming)
  (remove-watcher! state/!input ::debug-input)
  (remove-watcher! state/!ui ::debug-ui))

;; ============================================================================
;; State Snapshot and Restore (for testing/undo)
;; ============================================================================

(defn snapshot-runtime-state
  "Create a snapshot of all runtime state"
  []
  {:timing @state/!timing
   :playback @state/!playback
   :streaming @state/!streaming
   :input @state/!input
   :ui @state/!ui})

(defn restore-runtime-state!
  "Restore runtime state from a snapshot"
  [snapshot]
  (reset! state/!timing (:timing snapshot))
  (reset! state/!playback (:playback snapshot))
  (reset! state/!streaming (:streaming snapshot))
  (reset! state/!input (:input snapshot))
  (reset! state/!ui (:ui snapshot)))

(defn snapshot-persistent-state
  "Create a snapshot of all persistent state"
  []
  {:config @persist/!config
   :grid-assignments @persist/!grid-assignments
   :projectors @persist/!projectors
   :zones @persist/!zones
   :zone-groups @persist/!zone-groups
   :cues @persist/!cues
   :cue-lists @persist/!cue-lists
   :effect-registry @persist/!effect-registry})

(defn restore-persistent-state!
  "Restore persistent state from a snapshot"
  [snapshot]
  (reset! persist/!config (:config snapshot))
  (reset! persist/!grid-assignments (:grid-assignments snapshot))
  (reset! persist/!projectors (:projectors snapshot))
  (reset! persist/!zones (:zones snapshot))
  (reset! persist/!zone-groups (:zone-groups snapshot))
  (reset! persist/!cues (:cues snapshot))
  (reset! persist/!cue-lists (:cue-lists snapshot))
  (reset! persist/!effect-registry (:effect-registry snapshot)))
