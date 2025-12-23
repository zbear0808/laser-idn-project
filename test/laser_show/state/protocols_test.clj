(ns laser-show.state.protocols-test
  (:require [clojure.test :refer [deftest testing is]]
            [laser-show.state.protocols :as p]))

;; ============================================================================
;; Grid State Tests
;; ============================================================================

(deftest mock-grid-state-test
  (testing "Mock grid state operations"
    (let [grid (p/create-mock-grid-state {:cells {[0 0] {:preset-id :circle}}})]
      
      (testing "get-cell returns existing cell"
        (is (= {:preset-id :circle} (p/get-cell grid 0 0))))
      
      (testing "get-cell returns nil for empty cell"
        (is (nil? (p/get-cell grid 1 1))))
      
      (testing "set-cell! adds a cell"
        (p/set-cell! grid 1 1 {:preset-id :square})
        (is (= {:preset-id :square} (p/get-cell grid 1 1))))
      
      (testing "clear-cell! removes a cell"
        (p/clear-cell! grid 1 1)
        (is (nil? (p/get-cell grid 1 1))))
      
      (testing "get-all-cells returns all cells"
        (is (= {[0 0] {:preset-id :circle}} (p/get-all-cells grid))))
      
      (testing "get-grid-size returns size"
        (is (= [8 4] (p/get-grid-size grid)))))))

(deftest mock-grid-selection-test
  (testing "Selection operations"
    (let [grid (p/create-mock-grid-state {})]
      
      (testing "Initially no selection"
        (is (nil? (p/get-selected-cell grid))))
      
      (testing "set-selected-cell! sets selection"
        (p/set-selected-cell! grid 2 3)
        (is (= [2 3] (p/get-selected-cell grid))))
      
      (testing "set-selected-cell! with nil clears selection"
        (p/set-selected-cell! grid nil nil)
        (is (nil? (p/get-selected-cell grid)))))))

;; ============================================================================
;; Playback State Tests
;; ============================================================================

(deftest mock-playback-state-test
  (testing "Mock playback state operations"
    (let [playback (p/create-mock-playback-state {})]
      
      (testing "Initially not playing"
        (is (false? (p/playing? playback))))
      
      (testing "Initially no active cell"
        (is (nil? (p/get-active-cell playback))))
      
      (testing "set-active-cell! sets active cell"
        (p/set-active-cell! playback 1 2)
        (is (= [1 2] (p/get-active-cell playback))))
      
      (testing "trigger! sets trigger time"
        (let [before (System/currentTimeMillis)]
          (p/trigger! playback)
          (let [trigger-time (p/get-trigger-time playback)]
            (is (>= trigger-time before)))))
      
      (testing "stop-playback! clears state"
        (p/stop-playback! playback)
        (is (false? (p/playing? playback)))
        (is (nil? (p/get-active-cell playback)))))))

;; ============================================================================
;; Atom-based Grid State Tests
;; ============================================================================

(deftest atom-grid-state-test
  (testing "Atom-based grid state"
    (let [!grid (atom {:cells {[0 0] {:preset-id :test}}
                       :selected-cell nil
                       :size [4 2]})
          grid (p/create-atom-grid-state !grid)]
      
      (testing "get-cell reads from atom"
        (is (= {:preset-id :test} (p/get-cell grid 0 0))))
      
      (testing "set-cell! updates atom"
        (p/set-cell! grid 1 0 {:preset-id :new})
        (is (= {:preset-id :new} (get-in @!grid [:cells [1 0]]))))
      
      (testing "clear-cell! removes from atom"
        (p/clear-cell! grid 1 0)
        (is (nil? (get-in @!grid [:cells [1 0]]))))
      
      (testing "get-grid-size returns size"
        (is (= [4 2] (p/get-grid-size grid)))))))

;; ============================================================================
;; Atom-based Playback State Tests
;; ============================================================================

(deftest atom-playback-state-test
  (testing "Atom-based playback state"
    (let [!playback (atom {:playing? true
                           :active-cell [0 0]
                           :trigger-time 1000})
          playback (p/create-atom-playback-state !playback)]
      
      (testing "playing? reads from atom"
        (is (true? (p/playing? playback))))
      
      (testing "get-active-cell reads from atom"
        (is (= [0 0] (p/get-active-cell playback))))
      
      (testing "get-trigger-time reads from atom"
        (is (= 1000 (p/get-trigger-time playback))))
      
      (testing "stop-playback! updates atom"
        (p/stop-playback! playback)
        (is (false? (:playing? @!playback)))
        (is (nil? (:active-cell @!playback)))))))

;; ============================================================================
;; Atom-based Timing State Tests
;; ============================================================================

(deftest atom-timing-state-test
  (testing "Atom-based timing state"
    (let [!timing (atom {:bpm 120.0
                         :beat-position 0.5
                         :tap-times [1000 1500]})
          timing (p/create-atom-timing-state !timing)]
      
      (testing "get-bpm reads from atom"
        (is (= 120.0 (p/get-bpm timing))))
      
      (testing "set-bpm! updates atom"
        (p/set-bpm! timing 140)
        (is (= 140.0 (:bpm @!timing))))
      
      (testing "get-beat-position reads from atom"
        (is (= 0.5 (p/get-beat-position timing))))
      
      (testing "get-tap-times reads from atom"
        (is (= [1000 1500] (p/get-tap-times timing))))
      
      (testing "add-tap-time! appends to atom"
        (p/add-tap-time! timing 2000)
        (is (= [1000 1500 2000] (:tap-times @!timing))))
      
      (testing "clear-tap-times! clears in atom"
        (p/clear-tap-times! timing)
        (is (= [] (:tap-times @!timing)))))))

;; ============================================================================
;; Atom-based Effects State Tests
;; ============================================================================

(deftest atom-effects-state-test
  (testing "Atom-based effects state"
    (let [!effects (atom {:active-effects {[0 0] {:effect-id :scale :params {}}}})
          effects (p/create-atom-effects-state !effects)]
      
      (testing "get-effect-at reads from atom"
        (is (= {:effect-id :scale :params {}} (p/get-effect-at effects 0 0))))
      
      (testing "get-effect-at returns nil for empty"
        (is (nil? (p/get-effect-at effects 1 1))))
      
      (testing "set-effect-at! updates atom"
        (p/set-effect-at! effects 1 1 {:effect-id :rotate :params {}})
        (is (= {:effect-id :rotate :params {}} 
               (get-in @!effects [:active-effects [1 1]]))))
      
      (testing "clear-effect-at! removes from atom"
        (p/clear-effect-at! effects 1 1)
        (is (nil? (get-in @!effects [:active-effects [1 1]]))))
      
      (testing "get-active-effects returns all"
        (is (= {[0 0] {:effect-id :scale :params {}}} 
               (p/get-active-effects effects)))))))

;; ============================================================================
;; Combined App State Tests
;; ============================================================================

(deftest atom-app-state-test
  (testing "Combined app state"
    (let [!grid (atom {:cells {} :selected-cell nil :size [8 4]})
          !playback (atom {:playing? false :active-cell nil :trigger-time 0})
          !timing (atom {:bpm 120.0 :beat-position 0.0 :tap-times []})
          !effects (atom {:active-effects {}})
          app-state (p/create-atom-app-state {:!grid !grid
                                              :!playback !playback
                                              :!timing !timing
                                              :!effects !effects})]
      
      (testing "grid-state returns grid provider"
        (let [grid (p/grid-state app-state)]
          (is (satisfies? p/IGridState grid))))
      
      (testing "playback-state returns playback provider"
        (let [playback (p/playback-state app-state)]
          (is (satisfies? p/IPlaybackState playback))))
      
      (testing "timing-state returns timing provider"
        (let [timing (p/timing-state app-state)]
          (is (satisfies? p/ITimingState timing))))
      
      (testing "effects-state returns effects provider"
        (let [effects (p/effects-state app-state)]
          (is (satisfies? p/IEffectsState effects)))))))
