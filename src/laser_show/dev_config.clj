(ns laser-show.dev-config
  "Development mode configuration and detection.
   
   This namespace provides utilities for detecting whether the application
   is running in development mode, enabling features like cljfx dev tools.
   
   Dev mode is controlled by the `laser-show.dev` system property:
   - Set via JVM opts: -Dlaser-show.dev=true
   - Automatically set in the :dev alias in deps.edn
   
   Usage:
     (dev-mode?)  ; Returns true if running in dev mode")

(defn dev-mode?
  "Returns true if the application is running in development mode.
   
   Checks the `laser-show.dev` system property which is set via JVM opts
   in the :dev alias. This ensures dev tools are only loaded during
   development, not in production builds."
  []
  (= "true" (System/getProperty "laser-show.dev")))


