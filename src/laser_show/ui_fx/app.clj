(ns laser-show.ui-fx.app
  "Main cljfx application setup, renderer, and entry point."
  (:require [cljfx.api :as fx]
            [cljfx.ext.node :as fx.ext.node]
            [laser-show.state.atoms :as state]
            [laser-show.ui-fx.subs :as subs]
            [laser-show.ui-fx.events :as events]
            [laser-show.ui-fx.views.main :as main])
  (:import [javafx.application Platform]
           [javafx.scene.layout HeaderBar]))

;; ============================================================================
;; HeaderBar Extension (JavaFX 25 preview feature)
;; ============================================================================
;; 
;; HeaderBar is a new JavaFX 25 feature for extended title bar support.
;; Since cljfx doesn't have built-in support, we create it imperatively.
;; The main.clj view must use a different approach to include HeaderBar.

(defn create-header-bar!
  "Imperatively create a HeaderBar and set its children.
   Returns the HeaderBar instance.
   
   Args:
   - style - Optional CSS style string
   - leading-node - Optional Node for leading area
   - center-node - Optional Node for center area
   - trailing-node - Optional Node for trailing area"
  [{:keys [style leading-node center-node trailing-node]}]
  (let [hb (HeaderBar.)]
    (when style (.setStyle hb style))
    (when leading-node (.setLeading hb leading-node))
    (when center-node (.setCenter hb center-node))
    (when trailing-node (.setTrailing hb trailing-node))
    hb))

;; ============================================================================
;; Application State
;; ============================================================================

(defonce ^:private !app-state
  (atom {:initialized? false
         :renderer nil}))

;; ============================================================================
;; Event Handler for cljfx
;; ============================================================================

(defn- map-event-handler
  "Event handler that processes cljfx event maps.
   Delegates to events/handle-event multimethod."
  [event]
  (when (and (map? event) (:event/type event))
    (events/handle-event event)))

;; ============================================================================
;; Renderer
;; ============================================================================

(defn- create-renderer
  "Create the cljfx renderer with map event handler."
  []
  (fx/create-renderer
   :middleware (fx/wrap-map-desc (fn [state]
                                   {:fx/type main/root
                                    :state state}))
   :opts {:fx.opt/map-event-handler map-event-handler}))

;; ============================================================================
;; State Sync
;; ============================================================================

(defn- get-combined-state
  "Get the combined state from all atoms for rendering."
  []
  (subs/main-view-state))

(defn- render!
  "Trigger a render with the current state."
  []
  (when-let [renderer (:renderer @!app-state)]
    (renderer (get-combined-state))))

(defn- setup-atom-watchers!
  "Set up watchers on state atoms to trigger re-renders."
  []
  ;; Watch all relevant atoms and re-render on changes
  (add-watch state/!grid :fx-render (fn [_ _ _ _] (render!)))
  (add-watch state/!playback :fx-render (fn [_ _ _ _] (render!)))
  (add-watch state/!timing :fx-render (fn [_ _ _ _] (render!)))
  (add-watch state/!idn :fx-render (fn [_ _ _ _] (render!)))
  (add-watch state/!effects :fx-render (fn [_ _ _ _] (render!)))
  (add-watch state/!ui :fx-render (fn [_ _ _ _] (render!)))
  (add-watch state/!project :fx-render (fn [_ _ _ _] (render!))))

(defn- remove-atom-watchers!
  "Remove the render watchers from state atoms."
  []
  (remove-watch state/!grid :fx-render)
  (remove-watch state/!playback :fx-render)
  (remove-watch state/!timing :fx-render)
  (remove-watch state/!idn :fx-render)
  (remove-watch state/!effects :fx-render)
  (remove-watch state/!ui :fx-render)
  (remove-watch state/!project :fx-render))

;; ============================================================================
;; Initialization
;; ============================================================================

(defn- ensure-javafx-initialized!
  "Ensure JavaFX toolkit is initialized."
  []
  (try
    (Platform/startup (fn []))
    (catch IllegalStateException _
      ;; Already initialized, that's fine
      nil)))

(defn init!
  "Initialize the cljfx application.
   Creates the renderer and sets up atom watchers."
  []
  (when-not (:initialized? @!app-state)
    (ensure-javafx-initialized!)
    (let [renderer (create-renderer)]
      (swap! !app-state assoc
             :initialized? true
             :renderer renderer)
      (setup-atom-watchers!)
      (println "cljfx UI initialized"))))

(defn shutdown!
  "Shutdown the cljfx application.
   Removes watchers and cleans up."
  []
  (when (:initialized? @!app-state)
    (remove-atom-watchers!)
    (swap! !app-state assoc
           :initialized? false
           :renderer nil)
    (println "cljfx UI shutdown")))

;; ============================================================================
;; Public API
;; ============================================================================

(defn show-window!
  "Show the main cljfx window.
   Initializes if needed and triggers initial render."
  []
  (init!)
  (render!))

(defn close-window!
  "Close the cljfx window."
  []
  (Platform/runLater
   (fn []
     (shutdown!)
     (Platform/exit))))

(defn refresh!
  "Force a refresh of the UI."
  []
  (render!))

;; ============================================================================
;; Development Helpers
;; ============================================================================

(defn dev-start!
  "Start the cljfx UI for development.
   Call from REPL to launch the window."
  []
  (show-window!))

(defn dev-stop!
  "Stop the cljfx UI for development.
   Call from REPL to close the window."
  []
  (shutdown!))

(defn dev-restart!
  "Restart the cljfx UI for development.
   Useful after making changes to views."
  []
  (shutdown!)
  (Thread/sleep 500)
  (show-window!))

;; ============================================================================
;; Main Entry Point
;; ============================================================================

(defn -main
  "Main entry point for launching the cljfx UI."
  [& _args]
  (show-window!))

;; ============================================================================
;; Alternative: Simple standalone launch
;; ============================================================================

(defn launch-standalone!
  "Launch a standalone cljfx window without atom syncing.
   Useful for quick testing."
  []
  (fx/on-fx-thread
   (fx/create-component
    {:fx/type main/root
     :state (subs/main-view-state)})))

(comment
  ;; REPL usage examples:
  
  ;; Start the cljfx UI
  (dev-start!)
  
  ;; Stop the UI
  (dev-stop!)
  
  ;; Restart (after code changes)
  (dev-restart!)
  
  ;; Force refresh
  (refresh!)
  
  ;; Quick standalone test
  (launch-standalone!)
  
  )
