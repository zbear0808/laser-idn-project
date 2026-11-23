(ns laser-show.views.key-triggers
  "Keyboard input for the main window and non-modal editors. The capture
   filter runs before controls and menu accelerators; keyboard/decide says
   whether a press belongs to the router or to the focused editor."
  (:require [cljfx.api :as fx]
            [laser-show.events.core :as events]
            [laser-show.events.handlers.triggers :as triggers]
            [laser-show.input.events :as input]
            [laser-show.input.keyboard :as keyboard]
            [laser-show.input.router :as router]
            [laser-show.state.core :as state]
            [laser-show.views.keys :as keys])
  (:import [javafx.beans.value ChangeListener]
           [javafx.event EventHandler]
           [javafx.scene Scene]
           [javafx.scene.input KeyEvent]
           [javafx.stage Window]))

(defn- modifiers [^KeyEvent e]
  (cond-> #{}
    (.isControlDown e) (conj :ctrl)
    (.isMetaDown e) (conj :meta)
    (.isAltDown e) (conj :alt)
    (.isShiftDown e) (conj :shift)))

(defn- release-all!
  "Forget the keys before emitting their releases. A key used only to learn
   never played anything, so its release must not act on the new binding."
  [held]
  (let [before @held]
    (vreset! held {})
    (doseq [[id owner] before :when (= :binding owner)]
      (router/dispatch! (input/trigger :keyboard id :released)))))

(defn- handle-key! [^Scene scene held window-keys ^KeyEvent e]
  (let [phase (if (= KeyEvent/KEY_PRESSED (.getEventType e)) :pressed :released)
        id (keyboard/key-id (.name (.getCode e)))
        s (state/get-raw-state)
        event (input/trigger :keyboard id phase)
        owner (get @held id)
        enabled? (get-in s [:backend :input :keyboard :enabled?] true)
        typing? (keys/typing-target? e)
        mods (modifiers e)
        claimed? (or (contains? window-keys id) (keys/claims? (.getFocusOwner scene) id))
        learning (get-in s [:backend :input :learning-capture])
        {:keys [consume? emit cancel-learn?]}
        (keyboard/decide {:key-id id :phase phase :enabled? enabled? :modifiers mods
                          :text-target? typing? :claimed? claimed? :held? (some? owner) :learning learning
                          ;; Don't search user-defined matchers on key-up, repeat,
                          ;; or while the user is typing into a control.
                          :bound? (and enabled? (= :pressed phase) (nil? owner) (nil? learning)
                                       (not typing?) (not claimed?) (triggers/bound? s event))})]
    (when consume? (.consume e))
    (when cancel-learn? (events/dispatch! {:event/type :input/cancel-learn}))
    (case emit
      :pressed (do (vswap! held assoc id (if (= :press learning) :learn :binding))
                   (router/dispatch! event))
      :released (do (vswap! held dissoc id)
                    (when (= :binding owner) (router/dispatch! event)))
      nil)))

(defn- uninstall! [^Scene scene]
  (when-let [cleanup (.remove (.getProperties scene) ::cleanup)] (cleanup)))

(defn- install! [^Scene scene window-keys]
  (let [held (volatile! {}) ; FX thread only, private to this scene
        filter (reify EventHandler (handle [_ e] (handle-key! scene held window-keys e)))
        blur (reify ChangeListener
               (changed [_ _ _ focused?] (when-not focused? (release-all! held))))
        attach (fn [^Window w] (when w (.addListener (.focusedProperty w) ^ChangeListener blur)))
        detach (fn [^Window w] (when w (.removeListener (.focusedProperty w) ^ChangeListener blur)))
        ;; cljfx creates the Scene before assigning it to a Stage.
        window-change (reify ChangeListener
                        (changed [_ _ old new]
                          (detach old)
                          (release-all! held)
                          (attach new)))]
    (.addEventFilter scene KeyEvent/KEY_PRESSED filter)
    (.addEventFilter scene KeyEvent/KEY_RELEASED filter)
    (.addListener (.windowProperty scene) ^ChangeListener window-change)
    (attach (.getWindow scene))
    (.put (.getProperties scene) ::cleanup
          (fn []
            (.removeEventFilter scene KeyEvent/KEY_PRESSED filter)
            (.removeEventFilter scene KeyEvent/KEY_RELEASED filter)
            (.removeListener (.windowProperty scene) ^ChangeListener window-change)
            (detach (.getWindow scene))
            (release-all! held)))))

(defn wrap-scene
  "Install once on this scene, remove on disposal. window-keys are keys
   that the whole window edits with (e.g. Space in the timeline editor)."
  [scene window-keys]
  {:fx/type fx/ext-on-instance-lifecycle
   :desc scene
   :on-created #(install! % window-keys)
   :on-advanced (fn [old new] (uninstall! old) (install! new window-keys))
   :on-deleted uninstall!})
