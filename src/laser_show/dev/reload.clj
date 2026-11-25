(ns laser-show.dev.reload
  "App code reload utilities for rapid development iteration.
   
   Provides functions to reload all app code at runtime, useful for:
   - Development hot-reload workflows
   - Menu-driven code refresh
   - Testing code changes without JVM restart
   Usage:
     (reload-app-code!)  ; Reload all code and recreate UI
     (discover-namespaces)  ; See what namespaces will be reloaded"
  (:require [clojure.tools.logging :as log]
            [clojure.tools.namespace.find :as ns-find]
            [clojure.tools.namespace.dependency :as ns-dep]
            [clojure.tools.namespace.parse :as ns-parse]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; -----------------------------------------------------------------------------
;; Namespace Discovery
;; -----------------------------------------------------------------------------

(def ^:private excluded-prefixes
  "Namespace prefixes to exclude from reload.
   State namespaces must NOT be reloaded to preserve app state during reload."
  #{"laser-show.state"})

(defn- excluded-namespace?
  "Returns true if the namespace should be excluded from reload."
  [ns-sym]
  (let [ns-str (str ns-sym)]
    (some #(str/starts-with? ns-str %) excluded-prefixes)))

(defn- build-dependency-graph
  "Build a dependency graph from namespace declarations in source files."
  [src-dir]
  (let [clj-files (ns-find/find-sources-in-dir src-dir)]
    (reduce
     (fn [graph file]
       (try
         (let [decl (ns-parse/read-ns-decl (io/reader file))
               ns-sym (second decl)
               deps (ns-parse/deps-from-ns-decl decl)]
           (reduce (fn [g dep]
                     (if (str/starts-with? (str dep) "laser-show.")
                       (ns-dep/depend g ns-sym dep)
                       g))
                   graph
                   deps))
         (catch Exception _
           ;; Skip files that can't be parsed
           graph)))
     (ns-dep/graph)
     clj-files)))

(defn- topo-sort-namespaces
  "Sort namespaces in dependency order (dependencies first)."
  [src-dir namespaces]
  (let [graph (build-dependency-graph src-dir)
        ns-set (set namespaces)]
    ;; topo-sort returns in reverse dependency order (dependents first)
    ;; We want dependencies first, so reverse it
    (->> (ns-dep/topo-sort graph)
         (filter ns-set)
         reverse
         ;; Add any namespaces not in the graph (no deps or not parsed)
         (concat (remove (set (ns-dep/topo-sort graph)) namespaces))
         distinct
         vec)))

(defn discover-namespaces
  "Discover all laser-show namespaces from the src directory.
   
   Returns a vector of namespace symbols in dependency order,
   excluding state namespaces (to preserve app state during reload).
   
   This function dynamically scans the filesystem, so new namespaces
   are automatically included without any manual updates."
  []
  (let [src-dir (io/file "src")]
    (->> (ns-find/find-namespaces-in-dir src-dir)
         (filter #(str/starts-with? (str %) "laser-show."))
         (remove excluded-namespace?)
         (topo-sort-namespaces src-dir))))

;; Reload Functions

(defn reload-app-code!
  "Reload all app code and trigger UI re-render.
   
   This function:
   1. Discovers and reloads all code namespaces (views, events, animation, backend)
   2. Publishes the effect and modulator registries as they are now
      (:registries/published, laser-show.animation.registries): a reloaded
      namespace registers its effects and modulators again
   3. Triggers a re-render: :dev/reloaded {:kind :app} sets
      [:ui :last-reload-timestamp]

   The show clock keeps running through it: its loop calls tick! through the
   var, so the next tick runs the reloaded code, and the output never stops.

   Note: Does NOT create a new app/renderer. The existing renderer continues
   watching the same context atom. Because views use var references
   (e.g., {:fx/type root/root-view}), reloading namespaces updates the vars
   and the next render will use the new functions.
   
   Preserves:
   - All application state (state namespaces not reloaded)
   - Device and network connections (IDN, MIDI, OSC, Link): their runtime
     owners keep them
   - The single application window
   
   Limitations:
   - Event handler changes require app restart (handlers captured at app creation)
   
   Returns:
   - {:success? true :reloaded-count n} on success
   - {:success? false :error <msg>} on failure"
  []
  (try
    (log/info "🔄 Reloading app code...")
    
    ;; Step 1: Discover and reload all code namespaces
    (log/info "  Discovering namespaces...")
    (let [namespaces (discover-namespaces)]
      (log/info "  Found" (count namespaces) "namespaces to reload")
      (log/info "  Reloading namespaces...")
      (doseq [ns-sym namespaces]
        (try
          (require ns-sym :reload)
          (catch Exception e
            (log/warn "  ⚠ Failed to reload" ns-sym ":" (.getMessage e)))))
      (log/info "  ✓ Code namespaces reloaded")
      
      ;; Step 2: What the registries hold now, for the views that read them
      ;; from app state (the effect banks, the tap reactions)
      ((requiring-resolve 'laser-show.events.core/dispatch!)
       ((requiring-resolve 'laser-show.animation.registries/published-event)))
      
      ;; Step 3: Trigger re-render by touching state
      ;; The renderer watches the context atom; updating state triggers re-render.
      ;; Since view functions use var references, the new code is used.
      (log/info "  Triggering re-render...")
      ((requiring-resolve 'laser-show.events.core/dispatch!)
       {:event/type :dev/reloaded :kind :app})
      (log/info "  ✓ Re-render triggered")
      
      (log/info "✅ App reload complete!")
      (log/info "   Note: Event handler changes require app restart")
      
      {:success? true :reloaded-count (count namespaces)})
    
    (catch Exception e
      (log/error "❌ App reload failed:" (.getMessage e))
      (.printStackTrace e)
      {:success? false
       :error (.getMessage e)})))
