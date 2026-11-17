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
   text or files dragged in from other applications are never parsed or accepted.
   start-drag!, read-payload and has-payload? do this for any drag type and
   payload map (the timeline browser's :bank-item drags use them directly)."
  (:require [clojure.edn :as edn]
            [laser-show.events.core :as events])
  (:import [javafx.scene Node]
           [javafx.scene.input Clipboard TransferMode ClipboardContent DataFormat Dragboard MouseEvent DragEvent]))


;; Payload

(def ^:private payload-marker :laser-show/drag)

(defn encode-drag-payload
  "Serialize a drag payload map as an EDN string tagged with its drag type."
  [drag-type payload]
  (pr-str (assoc payload payload-marker drag-type)))

(defn parse-drag-payload
  "Parse a payload produced by `encode-drag-payload`. Returns the map when `s`
   is a well-formed payload of `drag-type` that passes `valid?`, otherwise
   nil. Never throws and never evaluates reader forms."
  [s drag-type valid?]
  (when (and (string? s) (< (count s) 1024))
    (let [data (try (edn/read-string s) (catch Exception _ nil))]
      (when (and (map? data)
                 (= drag-type (get data payload-marker))
                 (try (boolean (valid? data)) (catch Exception _ false)))
        data))))

(defn- cell-coords? [{:keys [col row]}]
  (and (nat-int? col) (nat-int? row)))

(defn encode-payload
  "Serialize a cell drag payload as an EDN string."
  [drag-type col row]
  (encode-drag-payload drag-type {:col col :row row}))

(defn parse-payload
  "Parse a cell drag payload produced by `encode-payload`.
   Returns {:type :col :row} when `s` is a well-formed payload of `drag-type`,
   otherwise nil. Never throws and never evaluates reader forms."
  [s drag-type]
  (when-let [data (parse-drag-payload s drag-type cell-coords?)]
    {:type drag-type :col (:col data) :row (:row data)}))

(defonce ^:private !formats (atom {}))

(defn- data-format
  "App-private DataFormat for a drag type, created once per type."
  ^DataFormat [drag-type]
  (or (get @!formats drag-type)
      (let [mime (str "application/x-laser-show-drag+" (subs (str drag-type) 1))
            fmt (or (DataFormat/lookupMimeType mime) (DataFormat. (into-array String [mime])))]
        (swap! !formats assoc drag-type fmt)
        fmt)))

(defn drag-content
  "ClipboardContent carrying `payload` under the drag type's private format."
  ^ClipboardContent [drag-type payload]
  (doto (ClipboardContent.)
    (.put (data-format drag-type) (encode-drag-payload drag-type payload))))

(defonce ^:private !dragging
  ;; drag-type -> payload of the drag this app started, until end-drag!
  (atom {}))

(defn start-drag!
  "Start a drag-and-drop from `source` (call it from a drag-detected handler)
   offering the transfer `modes`, with `payload` as its content. Returns the
   Dragboard. Call end-drag! from the source's drag-done handler."
  ^Dragboard [^Node source drag-type payload modes]
  (swap! !dragging assoc drag-type (assoc payload payload-marker drag-type))
  (doto (.startDragAndDrop source (into-array TransferMode modes))
    (.setContent (drag-content drag-type payload))))

(defn end-drag!
  "The drag of this type that start-drag! began is over."
  [drag-type]
  (swap! !dragging dissoc drag-type))

(defn has-payload?
  "Does the dragboard carry a drag of this type? (Content not parsed.)"
  [^Clipboard db drag-type]
  (boolean (and db (.hasContent db (data-format drag-type)))))

(defn read-payload
  "The dragboard's payload of `drag-type` when it passes `valid?`, else nil.
   A platform that announces the format but hides the content before the
   drop gets the payload of the drag this app started (risk R6 in
   plans/2026-11-08-timeline-rework/08-drag-from-browser.md)."
  [^Clipboard db drag-type valid?]
  (when (has-payload? db drag-type)
    (let [content (.getContent db (data-format drag-type))]
      (if (string? content)
        (parse-drag-payload content drag-type valid?)
        (when-let [p (get @!dragging drag-type)]
          (when (try (valid? p) (catch Exception _ false)) p))))))


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
      (start-drag! (.getSource e) drag-type {:col col :row row} [TransferMode/MOVE])
      (.consume e))))


;; Drag Target Handlers


(defn make-drag-over-handler
  "Create a drag-over handler function.
   
   Props:
   - drag-type: keyword to match against (only accepts drags of this type)
   
   Returns a function suitable for :on-drag-over"
  [{:keys [drag-type]}]
  (fn [^DragEvent e]
    (when (has-payload? (.getDragboard e) drag-type)
      (.acceptTransferModes e (into-array TransferMode [TransferMode/MOVE])))
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
      (when-let [data (read-payload db drag-type cell-coords?)]
        (events/dispatch! (assoc on-drop
                                 :from-col (:col data)
                                 :from-row (:row data)
                                 :to-col col
                                 :to-row row))
        (.setDropCompleted e true))
      (when-not (.isDropCompleted e)
        (.setDropCompleted e false))
      (.consume e))))
