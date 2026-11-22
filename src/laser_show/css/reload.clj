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
   3. Dispatches :dev/reloaded {:kind :styles}, whose handler bumps
      [:styles :reload-trigger] to trigger a re-render
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
      
      ;; Its handler bumps the trigger, so the UI re-renders
      ((requiring-resolve 'laser-show.events.core/dispatch!)
       {:event/type :dev/reloaded :kind :styles})
      
      {:success? true :reloaded-count (count namespaces)})
    (catch Exception e
      {:success? false
       :error (.getMessage e)})))
