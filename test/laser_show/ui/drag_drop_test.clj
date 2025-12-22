(ns laser-show.ui.drag-drop-test
  "Tests for the drag and drop utilities."
  (:require [clojure.test :refer [deftest testing is are]]
            [laser-show.ui.drag-drop :as dnd])
  (:import [java.awt.datatransfer DataFlavor]))

;; ============================================================================
;; Data Serialization Tests
;; ============================================================================

(deftest transferable-creation-test
  (testing "Creating a transferable with EDN data"
    (let [data {:type :cue-cell :cell-key [0 0] :data {:preset-id :circle}}
          transferable (dnd/create-transferable data)]
      
      (is (some? transferable) "Transferable should be created")
      
      (testing "Supports EDN data flavor"
        (is (.isDataFlavorSupported transferable dnd/edn-data-flavor)
            "Should support EDN data flavor"))
      
      (testing "Supports string flavor"
        (is (.isDataFlavorSupported transferable DataFlavor/stringFlavor)
            "Should support string flavor"))
      
      (testing "Returns correct flavors array"
        (let [flavors (.getTransferDataFlavors transferable)]
          (is (= 2 (count flavors)) "Should have 2 flavors")
          (is (some #(.equals % dnd/edn-data-flavor) flavors) "Should include EDN flavor")
          (is (some #(.equals % DataFlavor/stringFlavor) flavors) "Should include string flavor"))))))

(deftest data-extraction-test
  (testing "Extracting data from a transferable"
    (let [data {:type :cue-cell :cell-key [1 2] :data {:preset-id :triangle}}
          transferable (dnd/create-transferable data)
          extracted (dnd/extract-transfer-data transferable)]
      
      (is (= data extracted) "Extracted data should match original"))))

(deftest round-trip-test
  (testing "Round-trip serialization of various data types"
    (are [data] (= data (-> data dnd/create-transferable dnd/extract-transfer-data))
      ;; Simple map
      {:type :test}
      ;; Nested map
      {:type :cue-cell :cell-key [0 0] :data {:preset-id :circle}}
      ;; With various value types
      {:string "hello" :int 42 :float 3.14 :bool true :nil nil}
      ;; With vectors
      {:vec [1 2 3] :nested {:inner [4 5 6]}}
      ;; With keywords
      {:kw :keyword :nested-kw {:a :b :c :d}})))

;; ============================================================================
;; Ghost Image Tests
;; ============================================================================

(deftest simple-ghost-image-test
  (testing "Creating a simple ghost image"
    (let [image (dnd/create-simple-ghost-image 80 60 [100 150 200])]
      (is (some? image) "Image should be created")
      (is (= 80 (.getWidth image)) "Width should be 80")
      (is (= 60 (.getHeight image)) "Height should be 60")))
  
  (testing "Creating ghost image with text"
    (let [image (dnd/create-simple-ghost-image 80 60 [100 150 200] :text "Test")]
      (is (some? image) "Image with text should be created")))
  
  (testing "Creating ghost image with custom opacity"
    (let [image (dnd/create-simple-ghost-image 80 60 [100 150 200] :opacity 0.8)]
      (is (some? image) "Image with custom opacity should be created"))))

;; ============================================================================
;; Border Tests
;; ============================================================================

(deftest highlight-border-test
  (testing "Creating a highlight border"
    (let [border (dnd/create-highlight-border)]
      (is (some? border) "Border should be created")))
  
  (testing "Creating highlight border with custom color"
    (let [border (dnd/create-highlight-border :color java.awt.Color/RED)]
      (is (some? border) "Border with custom color should be created")))
  
  (testing "Creating highlight border with custom thickness"
    (let [border (dnd/create-highlight-border :thickness 5)]
      (is (some? border) "Border with custom thickness should be created"))))

;; ============================================================================
;; Accept Function Tests
;; ============================================================================

(deftest accept-fn-test
  (testing "Accept function for cue cells"
    (let [accept-fn (fn [data]
                      (and data
                           (= (:type data) :cue-cell)))]
      
      (is (accept-fn {:type :cue-cell :cell-key [0 0]})
          "Should accept cue-cell type")
      
      (is (not (accept-fn {:type :zone-cell :cell-key [0 0]}))
          "Should reject other types")
      
      (is (not (accept-fn nil))
          "Should reject nil"))))
