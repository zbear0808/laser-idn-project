(ns laser-show.views.components.drag-drop-cell
  "Reusable drag-and-drop cell behavior.
   
   Provides handler-creating functions for drag source and target behavior.
   Used by grid cells and effects cells.
   
   Usage:
   Create handlers with make-drag-* functions and pass them as inline props:
   
   {:fx/type :stack-pane
    :on-drag-detected (drag-drop/make-drag-detected-handler
                        {:drag-type :grid-cell :col 0 :row 0 :has-content? true})
    :on-drag-done (drag-drop/make-drag-done-handler)
    :on-drag-over (drag-drop/make-drag-over-handler {:drag-type :grid-cell})
    :on-drag-dropped (drag-drop/make-drag-dropped-handler
                       {:drag-type :grid-cell :col 0 :row 0
                        :on-drop {:event/type :grid/move-cell}})}
   
   These handlers are re-created on each render, so they always have current values.
   
   The payload travels under an app-private DataFormat (one per drag type), so
   text or files dragged in from other applications are never parsed or accepted.
   start-drag!, end-drag!, read-payload and has-payload? do this for any drag
   type and payload map (the timeline browser's :bank-item drags use them
   directly).
   
   A platform may announce the format and hide its content until the drop.
   read-payload then reads the drag from the node that started it: each
   Scene keeps the one drag started in it (FX thread only), until the
   source's drag-done (end-drag!), the scene's next start, the source
   leaving the scene, or the scene leaving or hiding its window."
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [laser-show.events.core :as events])
  (:import [javafx.beans.value ChangeListener]
           [javafx.scene Node Scene]
           [javafx.scene.input Clipboard TransferMode ClipboardContent DataFormat Dragboard MouseEvent DragEvent]
           [javafx.stage Window]))


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

(defn- content-of
  "ClipboardContent carrying the encoded payload under drag-type's private
   format."
  ^ClipboardContent [drag-type encoded]
  (doto (ClipboardContent.)
    (.put (data-format drag-type) encoded)))

(defn drag-content
  "ClipboardContent carrying `payload` under the drag type's private format."
  ^ClipboardContent [drag-type payload]
  (content-of drag-type (encode-drag-payload drag-type payload)))


;; The drag this app started, kept on the scene it started in

(defn- active-drag
  "The drag started in scene and not ended, {:source :drag-type :encoded
   :detach!}, or nil."
  [^Scene scene]
  (when scene
    (.get (.getProperties scene) ::active-drag)))

(defn- retire-drag!
  "End entry, a drag of scene: out of the scene's slot while the slot still
   holds it, its listeners removed (once, however often it is retired)."
  [^Scene scene entry]
  (when entry
    (let [props (.getProperties scene)]
      (when (identical? entry (.get props ::active-drag))
        (.remove props ::active-drag)))
    ((:detach! entry))))

(defn- publish-drag!
  "Make the drag source started the active one of scene, until retire-drag!
   ends it: end-drag!, the scene's next start, or the listeners added here
   for the drag's time, for source leaving scene and scene leaving or
   hiding its window."
  [^Node source ^Scene scene drag-type encoded]
  (let [^Window window (.getWindow scene)
        !entry (volatile! nil)
        retire! #(retire-drag! scene @!entry)
        left-scene (reify ChangeListener
                     (changed [_ _ _ now] (when-not (identical? now scene) (retire!))))
        left-window (reify ChangeListener
                      (changed [_ _ _ _] (retire!)))
        hidden (reify ChangeListener
                 (changed [_ _ _ showing?] (when-not showing? (retire!))))
        detached? (volatile! false)
        detach! (fn []
                  (when-not @detached?
                    (vreset! detached? true)
                    (.removeListener (.sceneProperty source) ^ChangeListener left-scene)
                    (.removeListener (.windowProperty scene) ^ChangeListener left-window)
                    (when window
                      (.removeListener (.showingProperty window) ^ChangeListener hidden))))
        entry {:source source :drag-type drag-type :encoded encoded :detach! detach!}]
    (vreset! !entry entry)
    (try
      (.addListener (.sceneProperty source) ^ChangeListener left-scene)
      (.addListener (.windowProperty scene) ^ChangeListener left-window)
      (when window
        (.addListener (.showingProperty window) ^ChangeListener hidden))
      (.put (.getProperties scene) ::active-drag entry)
      (catch Exception e
        (retire-drag! scene entry)
        (throw e)))))

(defn- open-dragboard!
  "The platform's drag-and-drop from source, offering modes: its Dragboard.
   A start's one native call."
  ^Dragboard [^Node source modes]
  (.startDragAndDrop source (into-array TransferMode modes)))

(defn start-drag!
  "Start a drag-and-drop from `source` (call it from a drag-detected handler)
   offering the transfer `modes`, with `payload` as its content. Returns the
   Dragboard, or nil when the platform can't start the drag or take the
   content. An earlier drag of the source's scene ends first; a drag that
   starts becomes the scene's active one, which read-payload reads while a
   platform hides the content. Call end-drag! from the source's drag-done
   handler."
  ^Dragboard [^Node source drag-type payload modes]
  (when-let [scene (.getScene source)]
    (retire-drag! scene (active-drag scene))
    (let [encoded (encode-drag-payload drag-type payload)
          db (try
               (let [db (open-dragboard! source modes)]
                 (when (.setContent db (content-of drag-type encoded))
                   db))
               (catch Exception e
                 (log/warn e "Could not start a drag of" drag-type)
                 nil))]
      (when db
        (publish-drag! source scene drag-type encoded)
        db))))

(defn end-drag!
  "The drag `source` started is over, however it ended (dropped, refused,
   released elsewhere, cancelled): call it from the source's drag-done
   handler. Ends only a drag of that node."
  [^Node source]
  (when-let [scene (some-> source .getScene)]
    (let [entry (active-drag scene)]
      (when (identical? source (:source entry))
        (retire-drag! scene entry)))))

(defn has-payload?
  "Does the dragboard carry a drag of this type? (Content not parsed.)"
  [^Clipboard db drag-type]
  (boolean (and db (.hasContent db (data-format drag-type)))))

(defn- origin-drag
  "The active drag of drag-type that origin (a drag event's gesture source)
   started, while origin is in that scene and the scene in a showing
   window; else nil."
  [origin drag-type]
  (when (instance? Node origin)
    (let [scene (.getScene ^Node origin)
          ^Window window (some-> scene .getWindow)
          entry (active-drag scene)]
      (when (and window
                 (.isShowing window)
                 (identical? origin (:source entry))
                 (= drag-type (:drag-type entry)))
        entry))))

(defn read-payload
  "The payload of `drag-type` the drag event `e` carries when it passes
   `valid?`, else nil; read it while handling the event. A string under the
   type's format is the payload, parsed (nil when malformed or invalid), and
   any other value is none. Only when the format is there with its content
   hidden (nil), as a platform may hide it before the drop, does the event's
   gesture source supply it: the drag that node started, while it is still
   its scene's active drag (start-drag!)."
  [^DragEvent e drag-type valid?]
  (let [db (.getDragboard e)]
    (when (has-payload? db drag-type)
      (let [content (.getContent db (data-format drag-type))]
        (cond
          (string? content) (parse-drag-payload content drag-type valid?)
          (some? content) nil
          :else (some-> (origin-drag (.getGestureSource e) drag-type)
                        :encoded
                        (parse-drag-payload drag-type valid?)))))))


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

(defn- drag-done [^DragEvent e]
  (end-drag! (.getSource e))
  (.consume e))

(defn make-drag-done-handler
  "Create the drag-done handler of the node a drag starts from (the same
   node as its drag-detected handler): it ends that node's drag, however
   it ended, the node empty by then or not. The same function each time.
   
   Returns a function suitable for :on-drag-done"
  []
  drag-done)


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
    (when-let [data (read-payload e drag-type cell-coords?)]
      (events/dispatch! (assoc on-drop
                               :from-col (:col data)
                               :from-row (:row data)
                               :to-col col
                               :to-row row))
      (.setDropCompleted e true))
    (when-not (.isDropCompleted e)
      (.setDropCompleted e false))
    (.consume e)))
