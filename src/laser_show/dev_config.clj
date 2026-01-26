(ns laser-show.dev-config
  "Development mode configuration and detection.
   
   This namespace provides utilities for detecting whether the application
   is running in development mode, enabling features like cljfx dev tools.
   
   Dev mode is controlled by the `laser-show.dev` system property:
   - Set via JVM opts: -Dlaser-show.dev=true
   - Automatically set in the :dev alias in deps.edn
   
   Usage:
     (dev-mode?)  ; Returns true if running in dev mode
     
   Debug logging flags (runtime toggleable):
     (set-idn-stream-logging! true)  ; Enable IDN stream debug logging
     (idn-stream-logging?)           ; Check if IDN stream logging is enabled")


;; Runtime debug flags - these can be toggled at the REPL without restart


(defonce ^:private !idn-stream-logging (atom false))

(defn idn-stream-logging?
  "Returns true if IDN stream debug logging is enabled.
   Defaults to false. Toggle with set-idn-stream-logging!"
  []
  @!idn-stream-logging)

(defn set-idn-stream-logging!
  "Enable or disable IDN stream debug logging at runtime.
   
   Usage from REPL:
     (require '[laser-show.dev-config :as dev])
     (dev/set-idn-stream-logging! true)  ; Enable logging
     (dev/set-idn-stream-logging! false) ; Disable logging"
  [enabled?]
  (reset! !idn-stream-logging (boolean enabled?)))


;; Dev mode detection


(defn dev-mode?
  "Returns true if the application is running in development mode.
   
   Checks the `laser-show.dev` system property which is set via JVM opts
   in the :dev alias. This ensures dev tools are only loaded during
   development, not in production builds."
  []
  (= "true" (System/getProperty "laser-show.dev")))


