(ns laser-show.views.components.gesture
  "A pointer press as one gesture, on the node that owns it (reactivity plan
   16). capture! gives a press its id, a fresh UUID: the
   :history/gesture-id its begin, its edits and its end carry, so history
   groups exactly that press's edits into one undo step. The press ends
   once (finish!, with a reason): on release, or when the node loses the
   focus, its window does, the node leaves its window, or it goes
   (wrap-owner). Its end is sent then, for the press's own id: an end or an
   edit from an older press can't end or extend a newer one.

   The capture lives in the node's properties, on the FX thread only: never
   in app state, never in its declarative user data. It holds the press's
   id, the immutable address read from the node when it was pressed, and
   what the owner keeps for it (the last cell sent); no document, no
   context, no undo baseline."
  (:require [cljfx.api :as fx]
            [cljfx.lifecycle :as lifecycle]
            [cljfx.mutator :as mutator]
            [cljfx.prop :as prop]
            [laser-show.events.core :as events])
  (:import [javafx.application Platform]
           [javafx.beans.value ChangeListener ObservableValue]
           [javafx.event EventHandler]
           [javafx.scene Node Scene]
           [javafx.scene.control Slider]
           [javafx.scene.input KeyEvent MouseButton MouseEvent]
           [javafx.stage Window]))

(def ^:private capture-key ::capture)

(defn capture
  "node's press: {:gesture-id :address …}, or nil."
  [^Node node]
  (.get (.getProperties node) capture-key))

(declare finish!)

(defn- listen!
  "Add listener to observable; a fn that takes it away."
  [^ObservableValue observable ^ChangeListener listener]
  (.addListener observable listener)
  #(.removeListener observable listener))

(defn- end-listeners!
  "The listeners that end press id on node: the node's focus going (it had
   it), its window's focus going, the node leaving its scene, the scene
   leaving its window, the window hiding. A fn that takes them all away."
  [^Node node id]
  (let [end! (fn [reason] (finish! node id reason))
        on-false (fn [reason] (reify ChangeListener
                                (changed [_ _ _ v] (when-not v (end! reason)))))
        on-change (fn [reason] (reify ChangeListener
                                 (changed [_ _ _ _] (end! reason))))
        ^Scene scene (.getScene node)
        ^Window window (some-> scene .getWindow)
        removers (cond-> [(listen! (.focusedProperty node) (on-false :blur))
                          (listen! (.sceneProperty node) (on-change :detach))]
                   scene (conj (listen! (.windowProperty scene) (on-change :detach)))
                   window (conj (listen! (.focusedProperty window) (on-false :window-blur))
                                (listen! (.showingProperty window) (on-false :detach))))]
    #(run! (fn [remove!] (remove!)) removers)))

(defn capture!
  "A new press on node: its capture {:gesture-id (a fresh UUID) :address
   address} with extra merged in, and the listeners that end it (finish!
   with :blur, :window-blur or :detach). (on-end capture reason) is called
   once, when it ends. A press still captured on node ends first
   (:replaced). Returns the capture. FX thread only."
  [^Node node address on-end & {:as extra}]
  (finish! node nil :replaced)
  (let [id (random-uuid)
        captured (merge extra {:gesture-id id :address address})]
    (.put (.getProperties node) capture-key (assoc captured ::on-end on-end))
    (.put (.getProperties node) [capture-key id] (end-listeners! node id))
    captured))

(defn update-capture!
  "node's press with (apply f capture args), while it is still press id's."
  [^Node node id f & args]
  (let [c (capture node)]
    (when (and c (= id (:gesture-id c)))
      (.put (.getProperties node) capture-key (apply f c args)))))

(defn finish!
  "End node's press when it is expected-id's (any press, for nil): clear
   it, take its listeners away and call its on-end with reason. Nothing for
   another press or none, so ending twice is harmless. FX thread only."
  [^Node node expected-id reason]
  (let [props (.getProperties node)
        c (.get props capture-key)]
    (when (and c (or (nil? expected-id) (= expected-id (:gesture-id c))))
      (let [id (:gesture-id c)]
        (.remove props capture-key)
        (when-let [remove! (.remove props [capture-key id])]
          (remove!))
        ((::on-end c) (dissoc c ::on-end) reason)))))

(defn wrap-owner
  "desc, whose node's press ends (:dispose) when the node is deleted or
   replaced; on-created (fn [node]) runs for each new node."
  [desc {:keys [on-created]}]
  {:fx/type fx/ext-on-instance-lifecycle
   :on-created (fn [node] (when on-created (on-created node)))
   :on-advanced (fn [old new]
                  (finish! old nil :dispose)
                  (when on-created (on-created new)))
   :on-deleted (fn [node] (finish! node nil :dispose))
   :desc desc})


;; A slider whose drag is one gesture

(defn- primary? [^MouseEvent e] (= MouseButton/PRIMARY (.getButton e)))

(defn- declared
  "slider's :gesture, as its last render declared it."
  [^Slider slider]
  (.get (.getProperties slider) ::gesture))

(defn- address-of [{:keys [label source-id]}] {:label label :source-id source-id})

(defn- slider-press!
  "A primary press on slider: one press id, captured before the skin moves
   the value (an event filter), and its transaction begun."
  [^Slider slider ^MouseEvent e]
  (when (primary? e)
    (let [{:keys [label source-id] :as address} (address-of (declared slider))
          {:keys [gesture-id]} (capture! slider address
                                         (fn [{:keys [gesture-id address]} _reason]
                                           (events/dispatch! {:event/type :history/commit-transaction
                                                              :source-id (:source-id address)
                                                              :history/gesture-id gesture-id})))]
      (events/dispatch! {:event/type :history/begin-transaction
                         :label label
                         :source-id source-id
                         :history/gesture-id gesture-id}))))

(defn- slider-release!
  "A primary release: the press ends after the skin's last value (once the
   release has gone through the slider)."
  [^Slider slider ^MouseEvent e]
  (when (primary? e)
    (when-let [{:keys [gesture-id]} (capture slider)]
      (Platform/runLater #(finish! slider gesture-id :release)))))

(defn- slider-value!
  "The slider's value changed while a press is captured, and not by the
   model's value coming back (set-model-value!): its value event (the
   :on-value-changed template, with the value) tagged with the press. Any
   other change (bounds clamping it, or a key's, which slider-key! sends)
   sends nothing."
  [^Slider slider value]
  (when-not (.get (.getProperties slider) ::echo)
    (when-let [{:keys [gesture-id]} (capture slider)]
      (when-let [template (:on-value-changed (declared slider))]
        (events/dispatch! (assoc template :fx/event value :history/gesture-id gesture-id))))))

(defn- slider-key!
  "A key pressed on the slider with no press captured: once the slider has
   handled it, its new value, if it moved, as a discrete edit."
  [^Slider slider]
  (when-not (capture slider)
    (let [before (.getValue slider)]
      (Platform/runLater
       #(let [after (.getValue slider)]
          (when (and (not= before after) (nil? (capture slider)))
            (when-let [template (:on-value-changed (declared slider))]
              (events/dispatch! (assoc template :fx/event after)))))))))

(defn- set-model-value!
  "The model's value, put on slider (its :value): an echo, never an edit,
   even mid-press."
  [^Slider slider value]
  (when (some? value)
    (let [props (.getProperties slider)]
      (.put props ::echo true)
      (try (.setValue slider (double value))
           (finally (.remove props ::echo))))))

(defn- set-gesture!
  "slider's :gesture, kept in its properties for its listeners. A press
   still captured for another :label or :source-id (the slider now stands
   for another control) is retired: it ends, and the rest of its drag
   sends nothing."
  [^Slider slider gesture]
  (.put (.getProperties slider) ::gesture gesture)
  (when-let [{:keys [gesture-id address]} (capture slider)]
    (when (not= address (address-of gesture))
      (finish! slider gesture-id :retarget))))

(def ^:private with-slider-props
  (fx/make-ext-with-props
   {:gesture (prop/make (mutator/setter set-gesture!) lifecycle/scalar)
    :model-value (prop/make (mutator/setter set-model-value!) lifecycle/scalar)}))

(defn- install-slider!
  "slider's press, release, key and value listeners (installed once: they
   read the last render's gesture from its properties)."
  [^Slider slider]
  (.addEventFilter slider MouseEvent/MOUSE_PRESSED (reify EventHandler (handle [_ e] (slider-press! slider e))))
  (.addEventFilter slider MouseEvent/MOUSE_RELEASED (reify EventHandler (handle [_ e] (slider-release! slider e))))
  (.addEventFilter slider KeyEvent/KEY_PRESSED (reify EventHandler (handle [_ _] (slider-key! slider))))
  (.addListener (.valueProperty slider) (reify ChangeListener (changed [_ _ _ v] (slider-value! slider v)))))

(defn slider
  "A :slider whose pointer drag is one gesture, one undo step: a primary
   press takes a press id before the first value change and begins its
   transaction; each value change while pressed is sent with the id; the
   press ends after the last value on release, or when the slider loses
   the focus, its window does, it goes, or it comes to stand for another
   control (its :label or :source-id changes). A key's change, with no
   press, is sent as a discrete edit. The model's value coming back to the
   slider (its :value) is never an edit, and neither is bounds clamping it
   with no press.

   Props: a :slider's, but no :on-value-changed, :on-mouse-pressed or
   :on-mouse-released; and :gesture {:label (the undo label) :source-id
   (the control) :on-value-changed (the value event's template, as cljfx
   would take it: the value as :fx/event)}."
  [{:keys [gesture value] :as props}]
  (wrap-owner {:fx/type with-slider-props
               :props {:gesture gesture :model-value value}
               :desc (-> props
                         (dissoc :gesture :value :fx/context)
                         (assoc :fx/type :slider))}
              {:on-created install-slider!}))
