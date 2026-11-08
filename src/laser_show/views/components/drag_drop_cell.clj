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
   
   These handlers are re-created on each render, so they always have current values.
   
   The payload travels under an app-private DataFormat (one per drag type), so
   text or files dragged in from other applications are never parsed or accepted."
  (:require [clojure.edn :as edn]
            [laser-show.events.core :as events])
  (:import [javafx.scene.input TransferMode ClipboardContent DataFormat MouseEvent DragEvent]))


;; Payload

(def ^:private payload-marker :laser-show/cell-drag)

(defn encode-payload
  "Serialize a cell drag payload as an EDN string."
  [drag-type col row]
  (pr-str {payload-marker true :type drag-type :col col :row row}))

(defn parse-payload
  "Parse a cell drag payload produced by `encode-payload`.
   Returns {:type :col :row} when `s` is a well-formed payload of `drag-type`,
   otherwise nil. Never throws and never evaluates reader forms."
  [s drag-type]
  (when (and (string? s) (< (count s) 1024))
    (let [data (try (edn/read-string s) (catch Exception _ nil))]
      (when (and (map? data)
                 (true? (get data payload-marker))
                 (= drag-type (:type data))
                 (nat-int? (:col data))
                 (nat-int? (:row data)))
        (select-keys data [:type :col :row])))))

(defonce ^:private !formats (atom {}))

(defn- data-format
  "App-private DataFormat for a drag type, created once per type."
  ^DataFormat [drag-type]
  (or (get @!formats drag-type)
      (let [mime (str "application/x-laser-show-drag+" (subs (str drag-type) 1))
            fmt (or (DataFormat/lookupMimeType mime) (DataFormat. (into-array String [mime])))]
        (swap! !formats assoc drag-type fmt)
        fmt)))


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
        (.put content (data-format drag-type) (encode-payload drag-type col row))
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
    (when-let [db (.getDragboard e)]
      (when (.hasContent db (data-format drag-type))
        (.acceptTransferModes e (into-array TransferMode [TransferMode/MOVE]))))
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
    (let [db (.getDragboard e)
          fmt (data-format drag-type)]
      (when-let [data (when (and db (.hasContent db fmt))
                        (parse-payload (.getContent db fmt) drag-type))]
        (events/dispatch! (assoc on-drop
                                 :from-col (:col data)
                                 :from-row (:row data)
                                 :to-col col
                                 :to-row row))
        (.setDropCompleted e true))
      (when-not (.isDropCompleted e)
        (.setDropCompleted e false))
      (.consume e))))
