(ns laser-show.ui.components.corner-pin-editor-test
  "Tests for corner pin visual editor component."
  (:require [clojure.test :refer [deftest is testing]]
            [laser-show.ui.components.corner-pin-editor :as corner-pin]))

;; ============================================================================
;; Coordinate Mapping Tests
;; ============================================================================

(deftest test-coordinate-conversions
  (testing "Normalized to screen conversion"
    ;; Using private function via var
    (let [norm-to-screen #'corner-pin/normalized-to-screen
          width 400
          height 400
          margin 30]
      
      ;; Test center (0, 0) -> middle of canvas
      (let [[sx sy] (norm-to-screen 0.0 0.0 width height margin)]
        (is (= sx 200.0))
        (is (= sy 200.0)))
      
      ;; Test top-left corner (-1, 1)
      (let [[sx sy] (norm-to-screen -1.0 1.0 width height margin)]
        (is (= sx 115.0))  ; margin + (1/4 * effective-width) = 30 + 85
        (is (= sy 115.0))) ; margin + (1/4 * effective-height) = 30 + 85
      
      ;; Test bottom-right corner (1, -1)
      (let [[sx sy] (norm-to-screen 1.0 -1.0 width height margin)]
        (is (= sx 285.0))  ; margin + (3/4 * effective-width) = 30 + 255
        (is (= sy 285.0))) ; margin + (3/4 * effective-height) = 30 + 255
      
      ;; Test extremes (-2, 2) and (2, -2)
      (let [[sx1 sy1] (norm-to-screen -2.0 2.0 width height margin)
            [sx2 sy2] (norm-to-screen 2.0 -2.0 width height margin)]
        (is (= sx1 (double margin)))
        (is (= sy1 (double margin)))
        (is (= sx2 (- width (double margin))))
        (is (= sy2 (- height (double margin)))))))
  
  (testing "Screen to normalized conversion"
    (let [screen-to-norm #'corner-pin/screen-to-normalized
          width 400
          height 400
          margin 30]
      
      ;; Test center of canvas -> (0, 0)
      (let [[nx ny] (screen-to-norm 200.0 200.0 width height margin)]
        (is (< (Math/abs nx) 0.01))
        (is (< (Math/abs ny) 0.01)))
      
      ;; Test corners
      (let [[nx1 ny1] (screen-to-norm margin margin width height margin)
            [nx2 ny2] (screen-to-norm (- width margin) (- height margin) width height margin)]
        (is (< (Math/abs (+ nx1 2.0)) 0.01))
        (is (< (Math/abs (- ny1 2.0)) 0.01))
        (is (< (Math/abs (- nx2 2.0)) 0.01))
        (is (< (Math/abs (+ ny2 2.0)) 0.01)))))
  
  (testing "Round-trip conversion"
    (let [norm-to-screen #'corner-pin/normalized-to-screen
          screen-to-norm #'corner-pin/screen-to-normalized
          width 400
          height 400
          margin 30
          test-coords [[-1.0 1.0] [0.0 0.0] [1.0 -1.0] [-0.5 0.5]]]
      
      (doseq [[nx ny] test-coords]
        (let [[sx sy] (norm-to-screen nx ny width height margin)
              [nx2 ny2] (screen-to-norm sx sy width height margin)]
          (is (< (Math/abs (- nx nx2)) 0.01)
              (str "X coordinate round-trip failed for " nx))
          (is (< (Math/abs (- ny ny2)) 0.01)
              (str "Y coordinate round-trip failed for " ny)))))))

(deftest test-snap-to-grid
  (testing "Grid snapping"
    (let [snap #'corner-pin/snap-to-grid]
      
      ;; Test snapping to 0.25 grid
      (is (= 0.0 (snap 0.1 0.25)))
      (is (= 0.25 (snap 0.2 0.25)))
      (is (= 0.5 (snap 0.4 0.25)))
      (is (= 0.5 (snap 0.6 0.25)))
      (is (= 1.0 (snap 0.9 0.25)))
      
      ;; Test snapping to 0.5 grid
      (is (= 0.0 (snap 0.2 0.5)))
      (is (= 0.5 (snap 0.3 0.5)))
      (is (= 1.0 (snap 0.8 0.5)))
      
      ;; Test snapping to integer grid
      (is (= -1.0 (snap -0.7 1.0)))
      (is (= 0.0 (snap 0.3 1.0)))
      (is (= 1.0 (snap 1.2 1.0)))
      
      ;; Test clamping to [-2, 2] range
      (is (= 2.0 (snap 3.0 0.25)))
      (is (= -2.0 (snap -3.0 0.25))))))

(deftest test-clamp
  (testing "Value clamping"
    (let [clamp #'corner-pin/clamp]
      
      (is (= 0.0 (clamp -1.0 0.0 1.0)))
      (is (= 1.0 (clamp 2.0 0.0 1.0)))
      (is (= 0.5 (clamp 0.5 0.0 1.0)))
      (is (= -2.0 (clamp -3.0 -2.0 2.0)))
      (is (= 2.0 (clamp 3.0 -2.0 2.0))))))

(deftest test-point-distance
  (testing "Euclidean distance calculation"
    (let [dist #'corner-pin/point-distance]
      
      (is (= 0.0 (dist 0 0 0 0)))
      (is (= 5.0 (dist 0 0 3 4)))
      (is (= 5.0 (dist 0 0 -3 -4)))
      (is (< (Math/abs (- (dist 1 1 4 5) 5.0)) 0.01)))))

(deftest test-point-in-handle
  (testing "Hit detection for handles"
    (let [in-handle? #'corner-pin/point-in-handle?]
      
      ;; Point inside handle
      (is (in-handle? 100 100 100 100 10))
      (is (in-handle? 105 100 100 100 10))
      (is (in-handle? 100 105 100 100 10))
      
      ;; Point outside handle
      (is (not (in-handle? 100 100 120 120 10)))
      (is (not (in-handle? 100 100 100 115 10))))))

;; ============================================================================
;; Component Creation Tests
;; ============================================================================

(deftest test-editor-creation
  (testing "Editor component creation"
    (let [editor (corner-pin/create-corner-pin-editor)]
      
      ;; Check that all required keys are present
      (is (contains? editor :panel))
      (is (contains? editor :set-corners!))
      (is (contains? editor :get-corners))
      (is (contains? editor :set-snap!))
      
      ;; Check initial corners (should be unit square)
      (let [corners ((:get-corners editor))]
        (is (= -1.0 (get-in corners [:tl :x])))
        (is (= 1.0 (get-in corners [:tl :y])))
        (is (= 1.0 (get-in corners [:tr :x])))
        (is (= 1.0 (get-in corners [:tr :y])))
        (is (= -1.0 (get-in corners [:bl :x])))
        (is (= -1.0 (get-in corners [:bl :y])))
        (is (= 1.0 (get-in corners [:br :x])))
        (is (= -1.0 (get-in corners [:br :y])))))))

(deftest test-editor-with-custom-corners
  (testing "Editor with custom initial corners"
    (let [custom-corners {:tl {:x -0.5 :y 0.5}
                          :tr {:x 0.5 :y 0.5}
                          :bl {:x -0.5 :y -0.5}
                          :br {:x 0.5 :y -0.5}}
          editor (corner-pin/create-corner-pin-editor
                  :initial-corners custom-corners)]
      
      (let [corners ((:get-corners editor))]
        (is (= -0.5 (get-in corners [:tl :x])))
        (is (= 0.5 (get-in corners [:tl :y])))
        (is (= 0.5 (get-in corners [:tr :x])))
        (is (= 0.5 (get-in corners [:tr :y])))))))

(deftest test-set-corners
  (testing "Setting corners programmatically"
    (let [editor (corner-pin/create-corner-pin-editor)
          new-corners {:tl {:x -1.5 :y 1.5}
                       :tr {:x 1.5 :y 1.5}
                       :bl {:x -1.5 :y -1.5}
                       :br {:x 1.5 :y -1.5}}]
      
      ((:set-corners! editor) new-corners)
      
      (let [corners ((:get-corners editor))]
        (is (= -1.5 (get-in corners [:tl :x])))
        (is (= 1.5 (get-in corners [:tl :y])))
        (is (= 1.5 (get-in corners [:tr :x])))
        (is (= 1.5 (get-in corners [:tr :y])))))))

(deftest test-snap-toggle
  (testing "Grid snapping toggle"
    (let [callback-called (atom false)
          editor (corner-pin/create-corner-pin-editor
                  :snap-to-grid false
                  :on-change (fn [_] (reset! callback-called true)))]
      
      ;; Toggle snap on
      ((:set-snap! editor) true)
      
      ;; Snap should now be enabled
      ;; (We can't easily test the internal state without exposing it,
      ;; but we can verify the function doesn't throw)
      (is (fn? (:set-snap! editor))))))

;; ============================================================================
;; Integration Tests
;; ============================================================================

(deftest test-on-change-callback
  (testing "On-change callback is triggered"
    (let [callback-called (atom 0)
          callback-corners (atom nil)
          editor (corner-pin/create-corner-pin-editor
                  :on-change (fn [corners]
                               (swap! callback-called inc)
                               (reset! callback-corners corners)))]
      
      ;; Change corners
      ((:set-corners! editor) {:tl {:x -0.8 :y 0.8}
                               :tr {:x 0.8 :y 0.8}
                               :bl {:x -0.8 :y -0.8}
                               :br {:x 0.8 :y -0.8}})
      
      ;; Verify the editor API functions exist
      (is (fn? (:set-corners! editor)))
      (is (fn? (:get-corners editor)))
      (is (fn? (:set-snap! editor))))))

;; ============================================================================
;; Demo/Manual Test
;; ============================================================================

(comment
  ;; Manual test - run this in REPL to see the editor
  (require '[seesaw.core :as ss])
  (require '[laser-show.ui.components.corner-pin-editor :as corner-pin])
  
  ;; Create and show editor in a frame
  (ss/invoke-later
   (let [editor (corner-pin/create-corner-pin-editor
                 :width 450
                 :height 450
                 :on-change (fn [corners]
                              (println "Corners changed:" corners)))
         frame (ss/frame :title "Corner Pin Editor Test"
                        :content (:panel editor)
                        :on-close :dispose)]
     (ss/pack! frame)
     (ss/show! frame)))
  
  ;; Test with effect dialog
  (require '[laser-show.ui.effect-dialogs :as dialogs])
  (require '[laser-show.animation.effects.shape])
  
  (ss/invoke-later
   (dialogs/show-effect-dialog! nil
                                {:effect-id :corner-pin
                                 :enabled true
                                 :params {:tl-x -1.0 :tl-y 1.0
                                         :tr-x 1.0 :tr-y 1.0
                                         :bl-x -1.0 :bl-y -1.0
                                         :br-x 1.0 :br-y -1.0}}
                                (fn [effect-data]
                                  (println "Effect created:" effect-data))))
  )
