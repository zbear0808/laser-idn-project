(ns laser-show.css.reload
  "CSS stylesheet reload utilities.
   
   Provides functions to reload CSS stylesheets at runtime, useful for:
   - Development hot-reload workflows
   - Menu-driven style refresh
   - Testing style changes without restart
   
   Usage:
     (reload-all-styles!)  ; Reload all CSS and trigger UI update")

(def ^:private css-namespaces
  "CSS namespaces to reload.
   Order matters - core/theme should be reloaded first as they define shared values."
  '[laser-show.css.theme
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
    laser-show.css.core])

(defn reload-all-styles!
  "Reload all CSS namespace files and trigger UI re-render.
   
   This function:
   1. Reloads all CSS namespace files with :reload flag
   2. Updates app state to trigger re-render (via :styles :reload-trigger)
   3. Returns a map indicating success/failure
   
   The UI will pick up new CSS URLs with updated hashes from subscriptions.
   
   Returns:
   - {:success? true} on success
   - {:success? false :error <msg>} on failure"
  []
  (try
    ;; Reload all CSS namespace files
    (doseq [ns-sym css-namespaces]
      (require ns-sym :reload))
    
    ;; Trigger state update to force UI re-render
    ;; The state namespace is always loaded when this function is called
    (require 'laser-show.state.core)
    ((resolve 'laser-show.state.core/swap-state!)
     update-in [:styles :reload-trigger] (fnil inc 0))
    
    {:success? true}
    (catch Exception e
      {:success? false
       :error (.getMessage e)})))
