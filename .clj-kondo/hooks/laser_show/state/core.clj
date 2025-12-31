(ns hooks.laser-show.state.core
  (:require [clj-kondo.hooks-api :as api]))

(defn defstate [{:keys [node]}]
  (let [[_ domain-name docstring field-specs] (:children node)
        domain-name-str (api/sexpr domain-name)
        initial-var-name (symbol (str domain-name-str "-initial"))
        
        ;; Extract field names from the field-specs map
        field-map (api/sexpr field-specs)
        field-names (keys field-map)
        
        ;; Generate def node for the -initial var
        initial-def-node
        (api/list-node
          [(api/token-node 'def)
           (api/token-node initial-var-name)
           docstring
           field-specs])
        
        ;; Generate def nodes for setter and updater functions
        field-fn-nodes
        (mapcat
          (fn [field-name]
            (let [setter-name (symbol (str "set-" domain-name-str "-" (name field-name) "!"))
                  updater-name (symbol (str "update-" domain-name-str "-" (name field-name) "!"))]
              [(api/list-node
                 [(api/token-node 'defn)
                  (api/token-node setter-name)
                  (api/vector-node [(api/token-node 'value)])])
               (api/list-node
                 [(api/token-node 'defn)
                  (api/token-node updater-name)
                  (api/vector-node [(api/token-node 'f)
                                    (api/token-node '&)
                                    (api/token-node 'args)])])]))
          field-names)
        
        ;; Combine all nodes into a do block
        new-node (api/list-node
                   (list* (api/token-node 'do)
                          initial-def-node
                          field-fn-nodes))]
    {:node new-node}))
