(ns laser-show.test-runner
  "Entry point for the :test, :test-all and :test-timing aliases.

   Wraps cognitect.test-runner and adds a default namespace regex that skips
   laser-show.ui-test.* (TestFX tests need the :test-ui classpath). The
   regex is only added when no -n/-r is given, because cognitect.test-runner
   ORs -n with -r, so a fixed -r would stop `-n <ns>` from narrowing the run."
  (:require [cognitect.test-runner :as runner]))

(def default-namespace-regex "^(?!laser-show\\.ui-test\\.).*-test$")

(def ^:private namespace-selectors
  #{"-n" "--namespace" "-r" "--namespace-regex"})

(defn -main [& args]
  (apply runner/-main
         (if (some namespace-selectors args)
           args
           (concat ["-r" default-namespace-regex] args))))
