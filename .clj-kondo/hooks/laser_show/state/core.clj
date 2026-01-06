(ns hooks.laser-show.state.core
  (:require [clj-kondo.hooks-api :as api]))

(defn defstate [{:keys [node]}]
  (let [[_ domain-name docstring field-specs] (:children node)
        domain-name-str (api/sexpr domain-name)
        initial-var-name (symbol (str domain-name-str "-initial"))
        
        ;; Generate def node for the -initial var
        ;; This is the only thing the macro generates now
        initial-def-node
        (api/list-node
          [(api/token-node 'def)
           (api/token-node initial-var-name)
           docstring
           field-specs])
        
        ;; Combine into a do block (just the initial var now)
        new-node (api/list-node
                   [(api/token-node 'do)
                    initial-def-node])]
    {:node new-node}))
