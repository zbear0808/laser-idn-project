(ns laser-show.dev.reload
  "App code reload utilities for rapid development iteration.
   
   Provides functions to reload all app code at runtime, useful for:
   - Development hot-reload workflows
   - Menu-driven code refresh
   - Testing code changes without JVM restart
   
   Usage:
     (reload-app-code!)  ; Reload all code and recreate UI"
  (:require [clojure.tools.logging :as log]))

(def ^:private code-namespaces
  "Namespaces to reload. Order matters for dependencies.
   
   We explicitly EXCLUDE state namespaces to preserve app state during reload:
   - laser-show.state.core
   - laser-show.state.domains
   - laser-show.state.queries
   - laser-show.state.extractors
   - laser-show.state.templates
   - laser-show.state.serialization
   - laser-show.state.persistent
   - laser-show.state.clipboard"
  '[;; Common utilities (reload first - other modules depend on these)
    laser-show.common.util
    laser-show.common.timing
    laser-show.dev-config
    
    ;; CSS (already has its own reload, but include for completeness)
    laser-show.css.colors
    laser-show.css.theme
    laser-show.css.typography
    laser-show.css.components
    laser-show.css.buttons
    laser-show.css.forms
    laser-show.css.grid-cells
    laser-show.css.layout
    laser-show.css.title-bar
    laser-show.css.cue-chain-editor
    laser-show.css.list
    laser-show.css.visual-editors
    laser-show.css.core
    laser-show.css.reload
    
    ;; Animation types and effects (core animation system)
    laser-show.animation.types
    laser-show.animation.time
    laser-show.animation.colors
    laser-show.animation.modulator-defs
    laser-show.animation.modulation
    laser-show.animation.modulation-diagnostics
    laser-show.animation.effects.common
    laser-show.animation.effects.calibration
    laser-show.animation.effects.color
    laser-show.animation.effects.curves
    laser-show.animation.effects.intensity
    laser-show.animation.effects.shape
    laser-show.animation.effects.zone
    laser-show.animation.effects
    laser-show.animation.generators
    laser-show.animation.presets
    laser-show.animation.chains
    laser-show.animation.cue-chains
    
    ;; IDN protocol
    laser-show.idn.hello
    laser-show.idn.output-config
    laser-show.idn.stream
    
    ;; Input handling
    laser-show.input.events
    laser-show.input.midi
    laser-show.input.osc
    laser-show.input.router
    
    ;; Routing
    laser-show.routing.core
    laser-show.routing.projector-matcher
    laser-show.routing.zone-effects
    
    ;; Profiling
    laser-show.profiling.async-profiler
    laser-show.profiling.frame-profiler
    laser-show.profiling.jfr-profiler
    
    ;; Backend
    laser-show.backend.streaming-engine
    laser-show.backend.multi-engine
    
    ;; Services
    laser-show.services.frame-service
    
    ;; Event handlers (order matters - helpers before handlers)
    laser-show.events.helpers
    laser-show.events.handlers.chain.helpers
    laser-show.events.handlers.chain.core
    laser-show.events.handlers.chain.params
    laser-show.events.handlers.chain.structure
    laser-show.events.handlers.chain
    laser-show.events.handlers.connection
    laser-show.events.handlers.cue-chain
    laser-show.events.handlers.effect-params
    laser-show.events.handlers.effects
    laser-show.events.handlers.grid
    laser-show.events.handlers.input
    laser-show.events.handlers.keyframe
    laser-show.events.handlers.list
    laser-show.events.handlers.menu
    laser-show.events.handlers.modulator
    laser-show.events.handlers.project
    laser-show.events.handlers.projector
    laser-show.events.handlers.timing
    laser-show.events.handlers.ui
    laser-show.events.handlers.zone-groups
    laser-show.events.handlers
    laser-show.events.core
    
    ;; Subscriptions
    laser-show.subs
    
    ;; Views - Components (reload before main views)
    laser-show.views.components.title-bar
    laser-show.views.components.drag-drop-cell
    laser-show.views.components.effect-bank
    laser-show.views.components.effect-param-ui
    laser-show.views.components.effect-parameter-editor
    laser-show.views.components.grid-cell
    laser-show.views.components.grid-tab
    laser-show.views.components.list-dnd
    laser-show.views.components.list
    laser-show.views.components.midi-settings
    laser-show.views.components.modulator-param-control
    laser-show.views.components.osc-settings
    laser-show.views.components.parameter-controls
    laser-show.views.components.preset-bank
    laser-show.views.components.preset-param-editor
    laser-show.views.components.preview
    laser-show.views.components.tabbed-bank
    laser-show.views.components.tabs
    laser-show.views.components.zone-chips
    
    ;; Views - Visual Editors
    laser-show.views.components.visual-editors.curve-canvas
    laser-show.views.components.visual-editors.custom-param-renderers
    laser-show.views.components.visual-editors.keyframe-modulator-panel
    laser-show.views.components.visual-editors.keyframe-timeline
    laser-show.views.components.visual-editors.rotate-canvas
    laser-show.views.components.visual-editors.scale-canvas
    laser-show.views.components.visual-editors.spatial-canvas
    
    ;; Views - Dialogs
    laser-show.views.dialogs.add-projector-manual
    laser-show.views.dialogs.cue-chain-editor
    laser-show.views.dialogs.effect-chain-editor
    laser-show.views.dialogs.zone-group-editor
    
    ;; Views - Tabs
    laser-show.views.tabs.effects
    laser-show.views.tabs.grid
    laser-show.views.tabs.projectors
    laser-show.views.tabs.settings
    laser-show.views.tabs.zones
    
    ;; Main views
    laser-show.views.toolbar
    laser-show.views.status-bar
    laser-show.views.root
    
    ;; App (reload last)
    laser-show.app])

(defn reload-app-code!
  "Reload all app code and trigger UI re-render.
   
   This function:
   1. Stops services (frame-service timer)
   2. Reloads all code namespaces (views, events, animation, backend)
   3. Restarts services
   4. Reinitializes CSS styles
   5. Triggers a re-render by touching state
   
   Note: Does NOT create a new app/renderer. The existing renderer continues
   watching the same context atom. Because views use var references
   (e.g., {:fx/type root/root-view}), reloading namespaces updates the vars
   and the next render will use the new functions.
   
   Preserves:
   - All application state (state namespaces not reloaded)
   - Network connections (IDN, MIDI, OSC managed by state atoms)
   - The single application window
   
   Limitations:
   - Event handler changes require app restart (handlers captured at app creation)
   
   Returns:
   - {:success? true} on success
   - {:success? false :error <msg>} on failure"
  []
  (try
    (log/info "🔄 Reloading app code...")
    
    ;; Step 1: Stop services before reloading
    (log/info "  Stopping services...")
    (require 'laser-show.services.frame-service)
    ((resolve 'laser-show.services.frame-service/stop-preview-updates!))
    
    ;; Step 2: Reload all code namespaces
    (log/info "  Reloading namespaces...")
    (doseq [ns-sym code-namespaces]
      (try
        (require ns-sym :reload)
        (catch Exception e
          (log/warn "  ⚠ Failed to reload" ns-sym ":" (.getMessage e)))))
    (log/info "  ✓ Code namespaces reloaded")
    
    ;; Step 3: Restart services
    (log/info "  Restarting services...")
    ((resolve 'laser-show.services.frame-service/start-preview-updates!) 30)
    (log/info "  ✓ Services restarted")
    
    ;; Step 4: Reinitialize CSS styles (URLs may have changed after reload)
    (log/info "  Reinitializing styles...")
    (let [init-styles-fn (resolve 'laser-show.app/init-styles!)]
      (init-styles-fn))
    (log/info "  ✓ Styles reinitialized")
    
    ;; Step 5: Trigger re-render by touching state
    ;; The renderer watches the context atom; updating state triggers re-render.
    ;; Since view functions use var references, the new code is used.
    (log/info "  Triggering re-render...")
    (let [assoc-in-state! (resolve 'laser-show.state.core/assoc-in-state!)]
      ;; Touch a timestamp to force context change and re-render
      (assoc-in-state! [:ui :last-reload-timestamp] (System/currentTimeMillis)))
    (log/info "  ✓ Re-render triggered")
    
    (log/info "✅ App reload complete!")
    (log/info "   Note: Event handler changes require app restart")
    
    {:success? true}
    
    (catch Exception e
      (log/error "❌ App reload failed:" (.getMessage e))
      (.printStackTrace e)
      {:success? false
       :error (.getMessage e)})))
