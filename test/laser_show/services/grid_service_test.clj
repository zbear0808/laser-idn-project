(ns laser-show.services.grid-service-test
  "Tests for grid-service underlying logic and validation."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [laser-show.services.grid-service :as grid-service]
            [laser-show.state.atoms :as state]))

;; ============================================================================
;; Test Fixtures
;; ============================================================================

(defn reset-state-fixture
  "Reset all state before and after each test."
  [f]
  (state/reset-all!)
  (f)
  (state/reset-all!))

(use-fixtures :each reset-state-fixture)

;; ============================================================================
;; Position Validation Tests
;; ============================================================================

(deftest valid-position?-test
  (testing "valid-position? validates grid bounds"
    (testing "valid positions"
      (is (true? (grid-service/valid-position? 0 0)))
      (is (true? (grid-service/valid-position? 7 3)))
      (is (true? (grid-service/valid-position? 3 2))))
    
    (testing "invalid positions - out of bounds"
      (is (false? (grid-service/valid-position? -1 0)))
      (is (false? (grid-service/valid-position? 0 -1)))
      (is (false? (grid-service/valid-position? 8 0)))
      (is (false? (grid-service/valid-position? 0 4)))
      (is (false? (grid-service/valid-position? 100 100))))
    
    (testing "invalid positions - non-integer"
      (is (false? (grid-service/valid-position? 1.5 0)))
      (is (false? (grid-service/valid-position? 0 1.5)))
      (is (false? (grid-service/valid-position? "0" 0)))
      (is (false? (grid-service/valid-position? nil 0))))))

;; ============================================================================
;; Cell Write Operations Tests
;; ============================================================================

(deftest set-cell-preset!-test
  (testing "set-cell-preset! with validation"
    (testing "valid operation"
      (is (true? (grid-service/set-cell-preset! 1 1 :test-preset)))
      (is (= :test-preset (:preset-id (grid-service/get-cell 1 1)))))
    
    (testing "marks project dirty"
      (state/mark-project-clean!)
      (grid-service/set-cell-preset! 2 2 :another-preset)
      (is (true? (state/project-dirty?))))
    
    (testing "invalid position returns nil"
      (is (nil? (grid-service/set-cell-preset! -1 0 :preset)))
      (is (nil? (grid-service/set-cell-preset! 100 100 :preset))))
    
    (testing "invalid preset-id returns nil"
      (is (nil? (grid-service/set-cell-preset! 1 1 "not-a-keyword")))
      (is (nil? (grid-service/set-cell-preset! 1 1 123))))))

(deftest clear-cell!-test
  (testing "clear-cell! with validation and coordination"
    (testing "clears existing cell"
      (grid-service/set-cell-preset! 1 1 :test-preset)
      (is (true? (grid-service/clear-cell! 1 1)))
      (is (nil? (grid-service/get-cell 1 1))))
    
    (testing "marks project dirty"
      (state/mark-project-clean!)
      (grid-service/set-cell-preset! 2 2 :preset)
      (state/mark-project-clean!)
      (grid-service/clear-cell! 2 2)
      (is (true? (state/project-dirty?))))
    
    (testing "stops playback when clearing active cell"
      (grid-service/set-cell-preset! 3 3 :preset)
      (grid-service/trigger-cell! 3 3)
      (is (true? (grid-service/playing?)))
      (grid-service/clear-cell! 3 3)
      (is (false? (grid-service/playing?))))
    
    (testing "invalid position returns nil"
      (is (nil? (grid-service/clear-cell! -1 0)))
      (is (nil? (grid-service/clear-cell! 100 100))))))

;; ============================================================================
;; Selection Tests
;; ============================================================================

(deftest select-cell!-test
  (testing "select-cell! with validation"
    (testing "valid selection"
      (is (true? (grid-service/select-cell! 1 1)))
      (is (= [1 1] (grid-service/get-selected-cell))))
    
    (testing "deselect with nil/nil"
      (grid-service/select-cell! 1 1)
      (is (true? (grid-service/select-cell! nil nil)))
      (is (nil? (grid-service/get-selected-cell))))
    
    (testing "invalid position returns nil"
      (is (nil? (grid-service/select-cell! -1 0)))
      (is (nil? (grid-service/select-cell! 100 100))))))

;; ============================================================================
;; Trigger/Playback Tests
;; ============================================================================

(deftest trigger-cell!-test
  (testing "trigger-cell! with validation"
    (testing "valid trigger - cell with content"
      ;; Default grid has preset at [0 0]
      (is (true? (grid-service/trigger-cell! 0 0)))
      (is (true? (grid-service/playing?)))
      (is (= [0 0] (grid-service/get-active-cell))))
    
    (testing "empty cell returns nil"
      (grid-service/stop-playback!)
      (is (nil? (grid-service/trigger-cell! 7 3)))  ; Empty cell
      (is (false? (grid-service/playing?))))
    
    (testing "invalid position returns nil"
      (is (nil? (grid-service/trigger-cell! -1 0)))
      (is (nil? (grid-service/trigger-cell! 100 100))))))

;; ============================================================================
;; Move Cell Tests
;; ============================================================================

(deftest move-cell!-test
  (testing "move-cell! with validation and coordination"
    (testing "valid move"
      (grid-service/set-cell-preset! 1 1 :test-preset)
      (is (true? (grid-service/move-cell! 1 1 2 2)))
      (is (nil? (grid-service/get-cell 1 1)))
      (is (= :test-preset (:preset-id (grid-service/get-cell 2 2)))))
    
    (testing "marks project dirty"
      (state/mark-project-clean!)
      (grid-service/set-cell-preset! 3 3 :preset)
      (state/mark-project-clean!)
      (grid-service/move-cell! 3 3 4 3)
      (is (true? (state/project-dirty?))))
    
    (testing "updates active cell reference when moving active cell"
      (grid-service/set-cell-preset! 1 1 :preset)
      (grid-service/trigger-cell! 1 1)
      (is (= [1 1] (grid-service/get-active-cell)))
      (grid-service/move-cell! 1 1 2 2)
      (is (= [2 2] (grid-service/get-active-cell))))
    
    (testing "updates selection when moving selected cell"
      (grid-service/set-cell-preset! 3 2 :preset)
      (grid-service/select-cell! 3 2)
      (is (= [3 2] (grid-service/get-selected-cell)))
      (grid-service/move-cell! 3 2 5 3)
      (is (= [5 3] (grid-service/get-selected-cell))))
    
    (testing "invalid source position returns nil"
      (is (nil? (grid-service/move-cell! -1 0 1 1))))
    
    (testing "invalid destination position returns nil"
      (is (nil? (grid-service/move-cell! 0 0 -1 0))))))

;; ============================================================================
;; Swap Cells Tests
;; ============================================================================

(deftest swap-cells!-test
  (testing "swap-cells! with validation and coordination"
    (testing "valid swap"
      (grid-service/set-cell-preset! 1 1 :preset-a)
      (grid-service/set-cell-preset! 2 2 :preset-b)
      (is (true? (grid-service/swap-cells! 1 1 2 2)))
      (is (= :preset-b (:preset-id (grid-service/get-cell 1 1))))
      (is (= :preset-a (:preset-id (grid-service/get-cell 2 2)))))
    
    (testing "swap with empty cell"
      (grid-service/set-cell-preset! 3 2 :preset)
      (grid-service/clear-cell! 5 2)
      (is (true? (grid-service/swap-cells! 3 2 5 2)))
      (is (nil? (grid-service/get-cell 3 2)))
      (is (= :preset (:preset-id (grid-service/get-cell 5 2)))))
    
    (testing "updates active cell when swapping active cell"
      (grid-service/set-cell-preset! 1 1 :preset)
      (grid-service/trigger-cell! 1 1)
      (is (= [1 1] (grid-service/get-active-cell)))
      (grid-service/swap-cells! 1 1 2 2)
      (is (= [2 2] (grid-service/get-active-cell))))
    
    (testing "invalid position returns nil"
      (is (nil? (grid-service/swap-cells! -1 0 1 1)))
      (is (nil? (grid-service/swap-cells! 1 1 -1 0))))))

;; ============================================================================
;; Batch Operations Tests
;; ============================================================================

(deftest clear-all-cells!-test
  (testing "clear-all-cells! clears everything"
    (grid-service/set-cell-preset! 1 1 :a)
    (grid-service/set-cell-preset! 2 2 :b)
    (grid-service/set-cell-preset! 3 3 :c)
    (grid-service/select-cell! 2 2)
    (grid-service/trigger-cell! 1 1)
    
    (is (true? (grid-service/clear-all-cells!)))
    
    ;; All original cells should be cleared (but default initial cells may remain)
    (is (nil? (grid-service/get-cell 1 1)))
    (is (nil? (grid-service/get-cell 2 2)))
    (is (nil? (grid-service/get-cell 3 3)))
    (is (nil? (grid-service/get-selected-cell)))
    (is (false? (grid-service/playing?)))))

(deftest clear-row!-test
  (testing "clear-row! clears entire row"
    (grid-service/set-cell-preset! 0 1 :a)
    (grid-service/set-cell-preset! 1 1 :b)
    (grid-service/set-cell-preset! 2 1 :c)
    (grid-service/set-cell-preset! 0 2 :d)  ; Different row
    
    (is (true? (grid-service/clear-row! 1)))
    
    (is (nil? (grid-service/get-cell 0 1)))
    (is (nil? (grid-service/get-cell 1 1)))
    (is (nil? (grid-service/get-cell 2 1)))
    (is (= :d (:preset-id (grid-service/get-cell 0 2)))))  ; Other row unaffected
  
  (testing "invalid row returns nil"
    (is (nil? (grid-service/clear-row! -1)))
    (is (nil? (grid-service/clear-row! 100)))))

(deftest clear-column!-test
  (testing "clear-column! clears entire column"
    (grid-service/set-cell-preset! 1 0 :a)
    (grid-service/set-cell-preset! 1 1 :b)
    (grid-service/set-cell-preset! 1 2 :c)
    (grid-service/set-cell-preset! 2 0 :d)  ; Different column
    
    (is (true? (grid-service/clear-column! 1)))
    
    (is (nil? (grid-service/get-cell 1 0)))
    (is (nil? (grid-service/get-cell 1 1)))
    (is (nil? (grid-service/get-cell 1 2)))
    (is (= :d (:preset-id (grid-service/get-cell 2 0)))))  ; Other column unaffected
  
  (testing "invalid column returns nil"
    (is (nil? (grid-service/clear-column! -1)))
    (is (nil? (grid-service/clear-column! 100)))))
