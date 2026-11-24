(ns laser-show.views.components.osc-settings-test
  "The OSC card's controls, as its description says (reactivity plan 09):
   the port field applies its text on Enter or when it loses focus, and
   Start Server asks for the port that is set when it is handled."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.osc-settings :as osc-settings]))

(defn- card
  "The OSC card for app state with [:backend :input :osc] merged with osc."
  [osc]
  (osc-settings/osc-settings-section
   {:fx/context (fx/create-context (update-in (domains/build-initial-state) [:backend :input :osc] merge osc))}))

(defn- node
  "The description in desc whose :id is id."
  [desc id]
  (first (filter #(and (map? %) (= id (:id %))) (tree-seq coll? seq desc))))

(deftest start-server-asks-for-no-port-of-its-own-test
  (is (= {:event/type :input/osc-start-server} (:on-action (node (card {:port 9100}) "osc-server-button")))
      "the handler reads the port set when Start is handled, not the one the button was drawn with")
  (is (= {:event/type :input/osc-stop-server}
         (:on-action (node (card {:port 9100 :server-running? true :enabled? true}) "osc-server-button")))))

(deftest port-field-commits-a-port-test
  (let [field (node (card {:port 9100}) "osc-port-field")
        commit (:on-commit field)]
    (is (= "9100" (:text field)) "it shows the port that is set")
    (is (false? (boolean (:disable field))))
    (is (= {:event/type :input/osc-set-port :port 9200} (commit "9200")) "on Enter or when it loses focus")
    (is (= {:event/type :input/osc-set-port :port 9300} (commit " 9300 ")))
    (testing "text that is no port commits nothing: the field shows the port that is set again"
      (doseq [typed ["abc" "0" "70000" "" "  " "9100.5" "-1"]]
        (is (nil? (commit typed)) (pr-str typed))))
    (is (true? (:disable (node (card {:port 9100 :server-running? true :enabled? true}) "osc-port-field")))
        "listening: the port is the server's, so it can't be edited")))
