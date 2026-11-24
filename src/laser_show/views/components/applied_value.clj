(ns laser-show.views.components.applied-value
  "A native control that shows the value that applies, after an edit the
   app refused too (plans/2026-11-23-cljfx-reactivity/13). JavaFX changes a
   check box, a radio button, a check menu item, a combo box, a slider or a
   text field as it is used, before the app decides. An edit that applies
   renders its own value; one the app refuses (the show lock) changes
   nothing, so the description stays the one before and cljfx sets nothing
   back: the control would go on showing a value that doesn't apply.

   applied-control sends each change once, as the event its :on-change
   makes of the new value. settle-ms after the newest change (one timer per
   control, started again by each), if the control is still bound to the
   same :address, still shows that change and what applies (:value, from
   the view's subscription) isn't it, it shows what applies. The timer is
   no answer: a value that applies later is rendered when it comes, and
   wins. Bound to another address (the inspector shows another laser), it
   shows that one's value at once. Setting itself back is no edit: it sends
   nothing, so it can't toggle, warn or start another wait; neither does a
   value cljfx renders.

   The address, the value and :on-change are the control's :user-data,
   which each render updates; its listener reads them when the input
   arrives. The listener and the timer belong to the instance (a node, or
   a check menu item, never its popup's skin): they go when it does."
  (:require [cljfx.api :as fx]
            [cljfx.lifecycle :as lifecycle]
            [cljfx.mutator :as mutator]
            [cljfx.prop :as prop]
            [laser-show.events.core :as events])
  (:import [javafx.animation PauseTransition]
           [javafx.beans.property Property]
           [javafx.beans.value ChangeListener]
           [javafx.collections ObservableMap]
           [javafx.event EventHandler]
           [javafx.scene Node]
           [javafx.scene.control CheckBox CheckMenuItem ChoiceBox ComboBoxBase MenuItem RadioMenuItem Slider
            TextInputControl ToggleButton]
           [javafx.util Duration]))

(def settle-ms
  "How long after a change a control waits for the value that applies to
   come back before it shows it again (commit-field's wait too)."
  300)

(def ^:private ^:dynamic *showing-applied?*
  "True while a control is set to what applies: that change is no edit."
  false)

(defonce ^:private !attached
  ;; Controls with a listener and a timer, not detached yet (attached-count)
  (atom 0))

(defn attached-count
  "How many controls have their listener and timer (for tests: they go when
   their instance does)."
  []
  @!attached)


;; The control's instance: a node, or a menu item

(defn- properties ^ObservableMap [owner]
  (if (instance? MenuItem owner) (.getProperties ^MenuItem owner) (.getProperties ^Node owner)))

(defn- binding-of
  "owner's :user-data: {:address :value :on-change}, its latest render's."
  [owner]
  (if (instance? MenuItem owner) (.getUserData ^MenuItem owner) (.getUserData ^Node owner)))

(defn- instance-of
  "What applied-control keeps on owner while it is mounted: {:property
   :listener :timer :attempt}."
  [owner]
  (.get (properties owner) ::instance))

(defn- native-property
  "owner's JavaFX property that property names: :selected, :value or
   :text."
  ^Property [owner property]
  (case property
    :selected (cond
                (instance? CheckBox owner) (.selectedProperty ^CheckBox owner)
                (instance? ToggleButton owner) (.selectedProperty ^ToggleButton owner)
                (instance? CheckMenuItem owner) (.selectedProperty ^CheckMenuItem owner)
                (instance? RadioMenuItem owner) (.selectedProperty ^RadioMenuItem owner))
    :value (cond
             (instance? ComboBoxBase owner) (.valueProperty ^ComboBoxBase owner)
             (instance? ChoiceBox owner) (.valueProperty ^ChoiceBox owner)
             (instance? Slider owner) (.valueProperty ^Slider owner))
    :text (when (instance? TextInputControl owner)
            (.textProperty ^TextInputControl owner))
    nil))

(defn- rendered
  "value as property shows it."
  [property value]
  (if (= :selected property) (boolean value) value))

(defn- same? [a b]
  (if (and (number? a) (number? b)) (== a b) (= a b)))


;; Showing what applies

(defn- show-applied!
  "Set owner to what applies (its :user-data :value), unless it shows it.
   No edit: the listener lets it by."
  [owner]
  (when-let [{:keys [^Property property]} (instance-of owner)]
    (let [{:keys [value]} (binding-of owner)]
      (when-not (same? value (.getValue property))
        (binding [*showing-applied?* true]
          (.setValue property value)
          (when (instance? TextInputControl owner)
            (.end ^TextInputControl owner)))))))

(defn- changed!
  "A change of owner's value to v that neither cljfx nor show-applied!
   made: the event its :on-change makes of v, sent once, and what applies
   again settle-ms after this newest change. A change that is no edit
   (:on-change gives nil) shows what applies at once."
  [owner v]
  (let [{:keys [address on-change]} (binding-of owner)
        {:keys [^PauseTransition timer attempt]} (instance-of owner)]
    (if-let [event (on-change v)]
      (do (vreset! attempt {:address address :value v})
          (events/dispatch! event)
          (.playFromStart timer))
      (do (vreset! attempt nil)
          (.stop timer)
          (show-applied! owner)))))

(defn- settled!
  "settle-ms after owner's last change: what applies, if owner is still
   bound to the address it was changed for and still shows that change."
  [owner]
  (when-let [{:keys [^Property property attempt]} (instance-of owner)]
    (when-let [{:keys [address value]} @attempt]
      (vreset! attempt nil)
      (when (and (= address (:address (binding-of owner)))
                 (same? value (.getValue property)))
        (show-applied! owner)))))

(defn- rebound!
  "A render bound owner to another address: what it showed for the last
   one goes, and it shows what applies to this one."
  [owner _address]
  (when-let [{:keys [^PauseTransition timer attempt]} (instance-of owner)]
    (.stop timer)
    (vreset! attempt nil)
    (show-applied! owner)))


;; The instance's listener and timer

(defn- attach!
  "Listen to owner's property, with its own settle timer, until detach!."
  [owner property]
  (let [^Property p (or (native-property owner property)
                        (throw (ex-info "applied-control: the control has no such property"
                                        {:property property :control (class owner)})))
        timer (PauseTransition. (Duration/millis settle-ms))
        listener (reify ChangeListener
                   (changed [_ _ _ v]
                     ;; cljfx renders what applies with *in-progress?* on, as it
                     ;; does for its own listeners
                     (when-not (or lifecycle/*in-progress?* *showing-applied?*)
                       (changed! owner v))))]
    (.setOnFinished timer (reify EventHandler (handle [_ _] (settled! owner))))
    (.put (properties owner) ::instance {:property p :listener listener :timer timer :attempt (volatile! nil)})
    (.addListener p ^ChangeListener listener)
    (swap! !attached inc)))

(defn- detach! [owner]
  (when-let [{:keys [^Property property listener ^PauseTransition timer]} (.remove (properties owner) ::instance)]
    (.stop timer)
    (.removeListener property ^ChangeListener listener)
    (swap! !attached dec)))

(def ^:private ext-with-address
  "The control with an :address prop, which calls rebound! when a render
   changes it (after the control's own props)."
  (fx/make-ext-with-props
   {:address (prop/make (mutator/setter rebound!) lifecycle/scalar)}))


(defn applied-control
  "The native control desc, showing value and sending what its user
   changes (see the namespace). Props:
   - :desc - the control's description, without its value, a listener of
     it or :user-data, which applied-control owns
   - :property - its value: :selected (a check box, toggle or radio button,
     check or radio menu item), :value (a combo box, choice box, color
     picker or slider) or :text (a text field)
   - :address - what the value is of, the entity and the field or role:
     [:projector :a :enabled?]
   - :value - what applies there (the view's subscription)
   - :on-change - (fn [v]) of the control's new value, plain data: the
     event to send, or nil when the change is no edit (it then shows what
     applies at once)

   Layout props (:grid-pane/row, :h-box/hgrow …) and :fx/key go with it,
   as with any component."
  [{:keys [desc property address value on-change]}]
  (let [value (rendered property value)]
    {:fx/type fx/ext-on-instance-lifecycle
     :on-created #(attach! % property)
     :on-advanced (fn [old new]
                    (detach! old)
                    (attach! new property))
     :on-deleted detach!
     :desc {:fx/type ext-with-address
            :props {:address address}
            :desc (assoc desc
                         property value
                         :user-data {:address address :value value :on-change on-change})}}))
