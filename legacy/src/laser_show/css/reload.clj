(ns laser-show.css.reload
  "CSS stylesheet reload utilities.
   
   Provides functions to reload CSS stylesheets at runtime, useful for:
   - Development hot-reload workflows
   - Menu-driven style refresh
   - Testing style changes without restart
   
   CSS namespaces are discovered dynamically from the src directory,
   so you never need to manually update a list when adding/removing CSS files.
   
   Usage:
     (reload-all-styles!)  ; Reload all CSS and trigger UI update
     (discover-css-namespaces)  ; See what CSS namespaces will be reloaded"
  (:require [clojure.tools.namespace.find :as ns-find]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; CSS Namespace Discovery

(defn discover-css-namespaces
  "Discover all CSS namespaces from the src directory.
   
   Returns a vector of namespace symbols matching laser-show.css.*
   This function dynamically scans the filesystem, so new CSS namespaces
   are automatically included without any manual updates.
   
   Note: laser-show.css.core is always placed last as it aggregates other CSS."
  []
  (let [src-dir (io/file "src")
        css-ns? #(str/starts-with? (str %) "laser-show.css.")
        core-ns 'laser-show.css.core
        namespaces (->> (ns-find/find-namespaces-in-dir src-dir)
                        (filter css-ns?)
                        (remove #{core-ns})
                        sort
                        vec)]
    ;; Put core last since it aggregates other CSS modules
    (conj namespaces core-ns)))

;; Reload Functions  

(defn reload-all-styles!
  "Reload all CSS namespace files and trigger UI re-render.
   
   This function:
   1. Discovers all CSS namespaces dynamically
   2. Reloads all CSS namespace files with :reload flag
   3. Updates app state to trigger re-render (via :styles :reload-trigger)
   4. Returns a map indicating success/failure
   
   The UI will pick up new CSS URLs with updated hashes from subscriptions.
   
   Returns:
   - {:success? true :reloaded-count n} on success
   - {:success? false :error <msg>} on failure"
  []
  (try
    ;; Discover and reload all CSS namespace files
    (let [namespaces (discover-css-namespaces)]
      (doseq [ns-sym namespaces]
        (require ns-sym :reload))
      
      ;; Trigger state update to force UI re-render
      ;; The state namespace is always loaded when this function is called
      (require 'laser-show.state.core)
      ((resolve 'laser-show.state.core/swap-state!)
       update-in [:styles :reload-trigger] (fnil inc 0))
      
      {:success? true :reloaded-count (count namespaces)})
    (catch Exception e
      {:success? false
       :error (.getMessage e)})))
