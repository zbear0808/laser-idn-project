(ns laser-show.views.components.drag-drop-cell
  "Reusable drag-and-drop cell behavior.
   
   Provides handler-creating functions for drag source and target behavior.
   Used by grid cells and effects cells.
   
   Usage:
   Create handlers with make-drag-* functions and pass them as inline props:
   
   {:fx/type :stack-pane
    :on-drag-detected (drag-drop/make-drag-detected-handler
                        {:drag-type :grid-cell :col 0 :row 0 :has-content? true})
    :on-drag-over (drag-drop/make-drag-over-handler {:drag-type :grid-cell})
    :on-drag-dropped (drag-drop/make-drag-dropped-handler
                       {:drag-type :grid-cell :col 0 :row 0
                        :on-drop {:event/type :grid/move-cell}})}
   
   These handlers are re-created on each render, so they always have current values."
  (:require [laser-show.events.core :as events])
  (:import [javafx.scene.input TransferMode ClipboardContent MouseEvent DragEvent]))


;; Drag Source Handlers


(defn make-drag-detected-handler
  "Create a drag-detected handler function.
   
   Props:
   - drag-type: keyword like :grid-cell or :effects-cell
   - col, row: cell coordinates
   - has-content?: whether cell has draggable content
   
   Returns a function suitable for :on-drag-detected"
  [{:keys [drag-type col row has-content?]}]
  (fn [^MouseEvent e]
    (when has-content?
      (let [source (.getSource e)
            db (.startDragAndDrop source (into-array TransferMode [TransferMode/MOVE]))
            content (ClipboardContent.)]
        (.putString content (pr-str {:col col :row row :type drag-type}))
        (.setContent db content)
        (.consume e)))))


;; Drag Target Handlers


(defn make-drag-over-handler
  "Create a drag-over handler function.
   
   Props:
   - drag-type: keyword to match against (only accepts drags of this type)
   
   Returns a function suitable for :on-drag-over"
  [{:keys [drag-type]}]
  (fn [^DragEvent e]
    (when (and (.getDragboard e)
               (.hasString (.getDragboard e)))
      (let [data (try (read-string (.getString (.getDragboard e))) 
                     (catch Exception _ nil))]
        (when (= drag-type (:type data))
          (.acceptTransferModes e (into-array TransferMode [TransferMode/MOVE])))))
    (.consume e)))


(defn make-drag-dropped-handler
  "Create a drag-dropped handler function.
   
   Props:
   - drag-type: keyword to match against
   - col, row: cell coordinates
   - on-drop: event map to dispatch on successful drop
             Will be augmented with :from-col, :from-row, :to-col, :to-row
   
   Returns a function suitable for :on-drag-dropped"
  [{:keys [drag-type col row on-drop]}]
  (fn [^DragEvent e]
    (let [db (.getDragboard e)]
      (when (.hasString db)
        (let [data (try (read-string (.getString db)) (catch Exception _ nil))]
          (when (and data (= drag-type (:type data)))
            (events/dispatch! (assoc on-drop
                                    :from-col (:col data)
                                    :from-row (:row data)
                                    :to-col col
                                    :to-row row))
            (.setDropCompleted e true))))
      (when-not (.isDropCompleted e)
        (.setDropCompleted e false))
      (.consume e))))
