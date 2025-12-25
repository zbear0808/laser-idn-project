(ns laser-show.ui-fx.app
  "Main cljfx application setup using context-based rendering.
   
   Uses cljfx contexts for efficient subscription-based re-renders.
   Components subscribe to state via fx/sub-val and fx/sub-ctx."
  (:require [cljfx.api :as fx]
            [laser-show.state.context :as ctx]
            [laser-show.ui-fx.events :as events]
            [laser-show.ui-fx.views.main :as main])
  (:import [javafx.application Platform]
           [javafx.scene.layout HeaderBar]))

;; ============================================================================
;; HeaderBar Extension (JavaFX 26 preview feature)
;; ============================================================================

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
;; Context-Based Renderer
;; ============================================================================

(defn- create-renderer
  "Create the cljfx renderer with context support.
   
   Key configuration:
   - fx/wrap-context-desc: Passes context to all components
   - fx/fn->lifecycle-with-context: Enables subscriptions in function components
   - map-event-handler: Processes event maps"
  []
  (fx/create-renderer
   :middleware (comp
                ;; Pass context to all components via :fx/context key
                fx/wrap-context-desc
                ;; Map the context atom value to root component description
                (fx/wrap-map-desc (fn [_] {:fx/type main/root})))
   :opts {:fx.opt/type->lifecycle
          ;; Use standard lifecycle for keywords (built-in components)
          ;; Use context-aware lifecycle for functions (our components)
          (fn [type]
            (or (fx/keyword->lifecycle type)
                (fx/fn->lifecycle-with-context type)))
          :fx.opt/map-event-handler map-event-handler}))

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
   Sets up context and creates the renderer."
  []
  (when-not (:initialized? @!app-state)
    (ensure-javafx-initialized!)
    ;; Initialize the context system (sets up atom watchers)
    (ctx/init!)
    ;; Create the renderer
    (let [renderer (create-renderer)]
      (swap! !app-state assoc
             :initialized? true
             :renderer renderer)
      (println "cljfx UI initialized with context support"))))

(defn shutdown!
  "Shutdown the cljfx application."
  []
  (when (:initialized? @!app-state)
    (ctx/shutdown!)
    (swap! !app-state assoc
           :initialized? false
           :renderer nil)
    (println "cljfx UI shutdown")))

;; ============================================================================
;; Public API
;; ============================================================================

(defn show-window!
  "Show the main cljfx window.
   Initializes if needed and mounts the renderer to the context."
  []
  (init!)
  ;; Mount renderer to context atom - it will watch for changes
  (when-let [renderer (:renderer @!app-state)]
    (fx/mount-renderer ctx/!context renderer)))

(defn close-window!
  "Close the cljfx window."
  []
  (Platform/runLater
   (fn []
     (shutdown!)
     (Platform/exit))))

(defn refresh!
  "Force a refresh of the UI by re-syncing context."
  []
  (ctx/sync-context!))

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
;; Debug / REPL
;; ============================================================================

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
  
  ;; Check context state
  @ctx/!context
  )
