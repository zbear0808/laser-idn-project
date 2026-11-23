(ns laser-show.views.dialogs.common
  "What the dialogs share: names and titles, the trigger mode picker, the
   undo/redo keys and dialog-stage, the one stage every dialog window is
   (keyboard plan 02, D4 A)."
  (:require [cljfx.api :as fx]
            [laser-show.common.util :as u]
            [laser-show.events.core :as events]
            [laser-show.subs :as subs])
  (:import [javafx.application Platform]
           [javafx.beans.value ChangeListener]
           [javafx.scene Parent]
           [javafx.scene.input KeyCode KeyEvent]))

(defn cell-label
  "A grid cell's name, as the grid's row letters and column numbers read:
   \"A1\" for [0 0], \"D8\" for col 7, row 3. A nil col or row counts as 0."
  [col row]
  (u/cell-label col row))

(defn default-name
  "A chain's name until it has its own: \"Cell A1\"."
  [col row]
  (str "Cell " (cell-label col row)))

(defn window-title
  "\"Cue Chain Editor - Intro (Cell A1)\", or \"Cue Chain Editor - Cell A1\"
   when the chain has no name: the chain editors' titles."
  [prefix chain-name col row]
  (let [cell-id (default-name col row)]
    (str prefix " - " (if (seq chain-name) (str chain-name " (" cell-id ")") cell-id))))


;; Trigger Mode Picker (the cue chain and timeline editors)


(def trigger-mode-options
  "Options for per-cue trigger mode dropdown."
  [{:id :toggle :name "Toggle"}
   {:id :retrigger :name "Retrigger"}])

(defn trigger-mode-dropdown
  "Dropdown for selecting trigger mode for this cue chain.
   Renders as a horizontal row: 'Trigger:' label + dropdown, neither ever
   narrower than its text.
   
   Trigger modes:
   - :toggle (default) - Click to turn ON, click again to turn OFF
   - :retrigger - Always start/restart the cue on click
   
   Note: Global trigger mode setting can override this per-cue setting.
   
   Props:
   - :col, :row - Grid coordinates
   - :trigger-mode - Current trigger mode (default :toggle)"
  [{:keys [col row trigger-mode]}]
  (let [current-mode (or trigger-mode :toggle)
        current-option (or (u/seek #(= (:id %) current-mode) trigger-mode-options)
                           (first trigger-mode-options))]
    {:fx/type :h-box
     :spacing 8
     :alignment :center-left
     :children [{:fx/type :label
                 :text "Trigger:"
                 :min-width :use-pref-size
                 :style-class "zone-picker-label"}
                {:fx/type :combo-box
                 :value current-option
                 :pref-width 120
                 :min-width :use-pref-size
                 :items trigger-mode-options
                 :tooltip {:fx/type :tooltip
                           :text (str "Toggle: a click starts the cue, the next one stops it\n"
                                      "Retrigger: every click starts it again\n"
                                      "The toolbar's Trigger can set it for every cue")}
                 :button-cell (fn [mode]
                                {:text (or (:name mode) "Toggle")})
                 :cell-factory {:fx/cell-type :list-cell
                                :describe (fn [mode]
                                            {:text (or (:name mode) "Toggle")})}
                 :on-value-changed {:event/type :cue-chain/set-trigger-mode
                                    :col col
                                    :row row}}]}))


;; Keys


(defn undo-redo-event
  "The event a key press in an editor window means: :edit/undo for
   Shortcut+Z, :edit/redo for Shift+Shortcut+Z or Shortcut+Y, else nil.
   key-data is {:code :z :shortcut? bool :shift? bool}."
  [{:keys [code shortcut? shift?]}]
  (when shortcut?
    (case code
      :z (if shift? :edit/redo :edit/undo)
      :y :edit/redo
      nil)))

(defn- key-data
  "What undo-redo-event reads of a key press. Ctrl counts as the shortcut on
   macOS too, as it always has in the editors."
  [^KeyEvent e]
  {:code (condp = (.getCode e) KeyCode/Z :z KeyCode/Y :y nil)
   :shortcut? (or (.isShortcutDown e) (.isControlDown e))
   :shift? (.isShiftDown e)})

(defn- handle-undo-redo-key
  "Dispatch undo or redo and consume the key when it's one of their chords."
  [^KeyEvent e]
  (when-let [event-type (undo-redo-event (key-data e))]
    (.consume e)
    (events/dispatch! {:event/type event-type})
    true))

(def ^:private key-handler
  (memoize
   (fn [undo? extra-fn]
     (fn [^KeyEvent e]
       (when-not (and undo? (handle-undo-redo-key e))
         (when extra-fn (extra-fn e)))))))

(defn scene-key-handler
  "A scene's :on-key-pressed: undo/redo first, then extra-fn (a dialog's
   own keys, which consumes what it uses). The scene sees a key only after
   the focused control had it, so a control that uses the key keeps it.
   The same extra-fn gives the same handler, so a re-render doesn't install
   a new one."
  ([extra-fn]
   (key-handler true extra-fn))
  ([undo? extra-fn]
   (key-handler (boolean undo?) extra-fn)))


;; The stage


(defn dialog-stage
  "The window of the dialog dialog-id: shown while the dialog is open, its
   stylesheets the main window's (they follow Reload Styles), closed by the
   window's close through :ui/close-dialog unless :on-close-request says
   otherwise.
   
   Props:
   - :dialog-id, :title, :content (the scene's root description) - required
   - :modality - :none (default) or :application-modal; :resizable
   - :width :height :min-width :min-height - the stage's
   - :on-close-request - the window's close (default :ui/close-dialog)
   - :undo-keys? - Shortcut+Z, Shift+Shortcut+Z and Shortcut+Y undo and
     redo (default true for :none, false for a modal question)
   - :on-key-extra - (fn [KeyEvent]) for the dialog's own keys, after undo
     and redo; it consumes what it uses. Pass a top-level fn"
  [{:keys [fx/context dialog-id title content modality resizable
           width height min-width min-height on-close-request undo-keys? on-key-extra]}]
  (let [modality (or modality :none)
        undo? (if (some? undo-keys?) undo-keys? (= :none modality))]
    (cond-> {:fx/type :stage
             :showing (fx/sub-ctx context subs/dialog-open? dialog-id)
             :title title
             :modality modality
             :on-close-request (or on-close-request {:event/type :ui/close-dialog :dialog-id dialog-id})
             :scene (cond-> {:fx/type :scene
                             :stylesheets (fx/sub-ctx context subs/stylesheet-urls)
                             :root content}
                      (or undo? on-key-extra)
                      (assoc :on-key-pressed (scene-key-handler undo? on-key-extra)))}
      (some? resizable) (assoc :resizable resizable)
      width (assoc :width width)
      height (assoc :height height)
      min-width (assoc :min-width min-width)
      min-height (assoc :min-height min-height))))


(defn fit-window-to-content!
  "Keep a dialog's window the size of its content (an :on-created of
   fx/ext-on-instance-lifecycle around the scene's root; the stage sets no
   :width or :height). After any layout change the window is sized to the
   scene again when the content's preferred height isn't the window's, so
   the window is as big as the platform's fonts and title bar need. Reads
   only the node, never state."
  [^Parent root]
  (.addListener (.needsLayoutProperty root)
                (reify ChangeListener
                  (changed [_ _ _ needs?]
                    (when needs?
                      (Platform/runLater
                       (fn []
                         (when-let [scene (.getScene root)]
                           (when-let [window (.getWindow scene)]
                             (when (> (Math/abs (- (.prefHeight root (.getWidth scene)) (.getHeight scene))) 0.5)
                               (.sizeToScene window)))))))))))
