(ns laser-show.ui.drag-drop
  "Reusable drag and drop utilities for Swing components.
   Provides a generic, data-agnostic DnD system using EDN-encoded data.
   
   This module can be used by any grid or component that needs drag and drop:
   - Cue grids (preset assignments)
   - Zone configuration grids
   - Scene/playlist editors
   - Any future grid-based UI
   
   Usage:
   1. Call (make-draggable! component opts) to enable dragging FROM a component
   2. Call (make-drop-target! component opts) to enable dropping ON a component
   3. Use callbacks to handle drag events and visual feedback"
  (:require [clojure.edn :as edn])
  (:import [java.awt Color Component Graphics2D Image Point RenderingHints]
           [java.awt.datatransfer DataFlavor StringSelection Transferable UnsupportedFlavorException]
           [java.awt.dnd DnDConstants DragGestureListener DragSource DragSourceAdapter 
            DragSourceDragEvent DragSourceDropEvent DragSourceListener DropTarget 
            DropTargetAdapter DropTargetDragEvent DropTargetDropEvent DropTargetListener]
           [java.awt.image BufferedImage]
           [javax.swing JComponent TransferHandler]))

;; ============================================================================
;; Data Transfer Format
;; ============================================================================

(def ^:private edn-mime-type "application/x-clojure-edn")

(def edn-data-flavor
  "Custom DataFlavor for EDN-encoded Clojure data."
  (DataFlavor. (str edn-mime-type ";class=java.lang.String") "EDN Data"))

(defn- serialize-data
  "Serialize Clojure data to EDN string."
  [data]
  (pr-str data))

(defn- deserialize-data
  "Deserialize EDN string to Clojure data."
  [s]
  (try
    (edn/read-string s)
    (catch Exception e
      (println "Failed to deserialize drag data:" (.getMessage e))
      nil)))

;; ============================================================================
;; Transferable Implementation
;; ============================================================================

(defn create-transferable
  "Create a Transferable for the given data.
   Data will be EDN-encoded and can be transferred as either
   our custom EDN flavor or plain string."
  [data]
  (let [edn-str (serialize-data data)]
    (reify Transferable
      (getTransferDataFlavors [_]
        (into-array DataFlavor [edn-data-flavor DataFlavor/stringFlavor]))
      
      (isDataFlavorSupported [_ flavor]
        (or (.equals flavor edn-data-flavor)
            (.equals flavor DataFlavor/stringFlavor)))
      
      (getTransferData [_ flavor]
        (cond
          (.equals flavor edn-data-flavor) edn-str
          (.equals flavor DataFlavor/stringFlavor) edn-str
          :else (throw (UnsupportedFlavorException. flavor)))))))

(defn extract-transfer-data
  "Extract Clojure data from a Transferable.
   Returns nil if extraction fails."
  [^Transferable transferable]
  (try
    (cond
      (.isDataFlavorSupported transferable edn-data-flavor)
      (deserialize-data (.getTransferData transferable edn-data-flavor))
      
      (.isDataFlavorSupported transferable DataFlavor/stringFlavor)
      (deserialize-data (.getTransferData transferable DataFlavor/stringFlavor))
      
      :else nil)
    (catch Exception e
      (println "Failed to extract transfer data:" (.getMessage e))
      nil)))

;; ============================================================================
;; Ghost Image Creation
;; ============================================================================

(defn create-ghost-image
  "Create a semi-transparent ghost image of a component.
   
   Options:
   - :opacity - Ghost opacity (0.0 to 1.0, default 0.5)
   - :scale - Scale factor (default 1.0)
   - :border-color - Optional border color to add"
  [^Component component & {:keys [opacity scale border-color]
                           :or {opacity 0.5 scale 1.0}}]
  (let [width (max 1 (.getWidth component))
        height (max 1 (.getHeight component))
        scaled-width (int (* width scale))
        scaled-height (int (* height scale))
        image (BufferedImage. scaled-width scaled-height BufferedImage/TYPE_INT_ARGB)
        g2d ^Graphics2D (.createGraphics image)]
    
    ;; Set up rendering hints
    (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setRenderingHint g2d RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
    
    ;; Scale if needed
    (when (not= scale 1.0)
      (.scale g2d scale scale))
    
    ;; Paint the component
    (.paint component g2d)
    
    ;; Apply opacity
    (doseq [y (range scaled-height)
            x (range scaled-width)]
      (let [rgb (.getRGB image x y)
            alpha (bit-and (bit-shift-right rgb 24) 0xFF)
            new-alpha (int (* alpha opacity))
            new-rgb (bit-or (bit-shift-left new-alpha 24)
                           (bit-and rgb 0x00FFFFFF))]
        (.setRGB image x y new-rgb)))
    
    ;; Add border if requested
    (when border-color
      (let [g2 ^Graphics2D (.createGraphics image)]
        (.setColor g2 border-color)
        (.drawRect g2 0 0 (dec scaled-width) (dec scaled-height))
        (.dispose g2)))
    
    (.dispose g2d)
    image))

(defn create-simple-ghost-image
  "Create a simple colored rectangle ghost image.
   Useful when component painting is expensive or not desired."
  [width height color & {:keys [opacity text]
                         :or {opacity 0.6}}]
  (let [image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        g2d ^Graphics2D (.createGraphics image)
        [r g b] (if (instance? Color color)
                  [(.getRed color) (.getGreen color) (.getBlue color)]
                  color)]
    
    (.setRenderingHint g2d RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    
    ;; Fill with semi-transparent color
    (.setColor g2d (Color. r g b (int (* 255 opacity))))
    (.fillRoundRect g2d 0 0 width height 8 8)
    
    ;; Draw border
    (.setColor g2d (Color. (min 255 (+ r 40)) (min 255 (+ g 40)) (min 255 (+ b 40))))
    (.drawRoundRect g2d 0 0 (dec width) (dec height) 8 8)
    
    ;; Draw text if provided
    (when text
      (.setColor g2d Color/WHITE)
      (.setFont g2d (.deriveFont (.getFont g2d) (float 10)))
      (let [fm (.getFontMetrics g2d)
            text-width (.stringWidth fm text)
            text-x (/ (- width text-width) 2)
            text-y (+ (/ height 2) (/ (.getAscent fm) 2))]
        (.drawString g2d text (int text-x) (int text-y))))
    
    (.dispose g2d)
    image))

;; ============================================================================
;; Drag Source Implementation
;; ============================================================================

(defn make-draggable!
  "Make a component draggable.
   
   Options:
   - :data-fn - Required. Function that returns the data to transfer.
                Called when drag starts: (data-fn) -> data-map
   - :ghost-fn - Optional. Function to create ghost image: (ghost-fn component data) -> Image
                 If not provided, uses create-ghost-image
   - :ghost-offset - Optional. [x y] offset for ghost image from cursor (default [10 10])
   - :on-drag-start - Optional. Called when drag starts: (on-drag-start data)
   - :on-drag-end - Optional. Called when drag ends: (on-drag-end data success?)
   - :enabled-fn - Optional. Function to check if drag is enabled: (enabled-fn) -> boolean"
  [^Component component {:keys [data-fn ghost-fn ghost-offset on-drag-start on-drag-end enabled-fn]
                         :or {ghost-offset [10 10]}}]
  (let [drag-source (DragSource.)
        
        create-ghost (fn [data]
                       (if ghost-fn
                         (ghost-fn component data)
                         (create-ghost-image component :opacity 0.6)))
        
        drag-gesture-listener
        (reify DragGestureListener
          (dragGestureRecognized [_ event]
            (when (or (nil? enabled-fn) (enabled-fn))
              (when-let [data (data-fn)]
                (let [ghost (create-ghost data)
                      [ox oy] ghost-offset
                      offset (Point. (- ox) (- oy))
                      transferable (create-transferable data)]
                  
                  (when on-drag-start
                    (on-drag-start data))
                  
                  (.startDrag event
                              DragSource/DefaultMoveDrop
                              ghost
                              offset
                              transferable
                              (proxy [DragSourceAdapter] []
                                (dragDropEnd [^DragSourceDropEvent e]
                                  (when on-drag-end
                                    (on-drag-end data (.getDropSuccess e)))))))))))
        ]
    
    (.createDefaultDragGestureRecognizer 
     drag-source 
     component 
     DnDConstants/ACTION_MOVE
     drag-gesture-listener)
    
    ;; Return the drag source for potential cleanup
    drag-source))

;; ============================================================================
;; Drop Target Implementation
;; ============================================================================

(defn make-drop-target!
  "Make a component a drop target.
   
   Options:
   - :accept-fn - Optional. Function to check if data is accepted: (accept-fn data) -> boolean
                  If not provided, accepts all EDN data
   - :on-drop - Required. Called when data is dropped: (on-drop data) -> boolean
                Return true if drop was handled successfully
   - :on-drag-enter - Optional. Called when drag enters: (on-drag-enter data)
   - :on-drag-over - Optional. Called during drag over: (on-drag-over data x y)
   - :on-drag-exit - Optional. Called when drag exits: (on-drag-exit)"
  [^Component component {:keys [accept-fn on-drop on-drag-enter on-drag-over on-drag-exit]}]
  (let [current-data (atom nil)
        
        get-data (fn [^DropTargetDragEvent event]
                   (try
                     (let [transferable (.getTransferable event)]
                       (extract-transfer-data transferable))
                     (catch Exception e
                       nil)))
        
        check-accept (fn [data]
                       (and data
                            (or (nil? accept-fn)
                                (accept-fn data))))
        
        drop-target-listener
        (reify DropTargetListener
          (dragEnter [_ event]
            (let [data (get-data event)]
              (reset! current-data data)
              (if (check-accept data)
                (do
                  (.acceptDrag event DnDConstants/ACTION_MOVE)
                  (when on-drag-enter
                    (on-drag-enter data)))
                (.rejectDrag event))))
          
          (dragOver [_ event]
            (let [data @current-data]
              (if (check-accept data)
                (do
                  (.acceptDrag event DnDConstants/ACTION_MOVE)
                  (when on-drag-over
                    (let [loc (.getLocation event)]
                      (on-drag-over data (.x loc) (.y loc)))))
                (.rejectDrag event))))
          
          (dragExit [_ _event]
            (reset! current-data nil)
            (when on-drag-exit
              (on-drag-exit)))
          
          (dropActionChanged [_ event]
            (if (check-accept @current-data)
              (.acceptDrag event DnDConstants/ACTION_MOVE)
              (.rejectDrag event)))
          
          (drop [_ event]
            (try
              (let [transferable (.getTransferable event)
                    data (extract-transfer-data transferable)]
                (reset! current-data nil)
                (when on-drag-exit
                  (on-drag-exit))
                (if (and (check-accept data) on-drop)
                  (let [success (on-drop data)]
                    (.dropComplete event (boolean success)))
                  (.dropComplete event false)))
              (catch Exception e
                (println "Drop failed:" (.getMessage e))
                (.dropComplete event false)))))]
    
    (DropTarget. component DnDConstants/ACTION_MOVE drop-target-listener true)))

;; ============================================================================
;; Visual Feedback Helpers
;; ============================================================================

(def highlight-border-color (Color. 100 200 255))
(def reject-border-color (Color. 255 100 100))

(defn create-highlight-border
  "Create a highlighted border for drop target feedback."
  [& {:keys [color thickness]
      :or {color highlight-border-color thickness 3}}]
  (javax.swing.BorderFactory/createLineBorder color thickness))

(defn with-drop-highlight
  "Wrap drop target callbacks to automatically handle border highlighting.
   
   Takes the original callbacks map and the component's default border.
   Returns a new callbacks map with highlighting added."
  [callbacks ^Component component default-border]
  (let [highlight-border (create-highlight-border)]
    (merge callbacks
           {:on-drag-enter (fn [data]
                             (.setBorder component highlight-border)
                             (when-let [f (:on-drag-enter callbacks)]
                               (f data)))
            :on-drag-exit (fn []
                            (.setBorder component default-border)
                            (when-let [f (:on-drag-exit callbacks)]
                              (f)))})))

;; ============================================================================
;; Convenience Functions
;; ============================================================================

(defn make-cell-draggable!
  "Convenience function specifically for grid cells.
   Creates consistent drag behavior for cell-like components.
   
   Options:
   - :cell-key - The [col row] key for this cell
   - :get-data-fn - Function to get cell's transferable data: (get-data-fn) -> data
   - :data-type - Type identifier for filtering (e.g. :cue-cell, :zone-cell)
   - :source-id - Identifier for the source grid
   - :on-drag-start - Optional callback
   - :on-drag-end - Optional callback
   - :ghost-color - Optional color for ghost image"
  [component {:keys [cell-key get-data-fn data-type source-id on-drag-start on-drag-end ghost-color]
              :or {data-type :grid-cell source-id :default}}]
  (make-draggable! component
    {:data-fn (fn []
                (when-let [data (get-data-fn)]
                  {:type data-type
                   :source-id source-id
                   :cell-key cell-key
                   :data data}))
     :ghost-fn (if ghost-color
                 (fn [comp _data]
                   (create-simple-ghost-image 
                    (.getWidth comp) (.getHeight comp) 
                    ghost-color
                    :opacity 0.7))
                 (fn [comp _data]
                   (create-ghost-image comp :opacity 0.6)))
     :on-drag-start on-drag-start
     :on-drag-end on-drag-end
     :enabled-fn (fn [] (some? (get-data-fn)))}))

(defn make-cell-drop-target!
  "Convenience function specifically for grid cell drop targets.
   
   Options:
   - :cell-key - The [col row] key for this cell
   - :accept-types - Set of data types to accept (e.g. #{:cue-cell})
   - :source-id - Identifier for this grid (to handle same-grid drops)
   - :on-drop-fn - Function called on drop: (on-drop-fn source-cell-key data) -> boolean
   - :default-border - The cell's default border for highlight reset"
  [component {:keys [cell-key accept-types source-id on-drop-fn default-border]}]
  (let [callbacks {:accept-fn (fn [transfer-data]
                                (and transfer-data
                                     (:type transfer-data)
                                     (or (nil? accept-types)
                                         (contains? accept-types (:type transfer-data)))))
                   :on-drop (fn [transfer-data]
                              (let [source-key (:cell-key transfer-data)
                                    data (:data transfer-data)]
                                ;; Don't allow dropping on self
                                (when (not= source-key cell-key)
                                  (on-drop-fn source-key data))))}
        callbacks-with-highlight (if default-border
                                   (with-drop-highlight callbacks component default-border)
                                   callbacks)]
    (make-drop-target! component callbacks-with-highlight)))
