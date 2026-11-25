(ns laser-show.views.components.link-settings-test
  "The Link card's controls, as its description says: Align beats with Link
   is the [:backend :link :beat-sync?] switch, offered while Link and BPM
   sync are on."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest is testing]]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.link-settings :as link-settings]))

(defn- card
  "The Link card for app state with [:backend :link] merged with link."
  [link]
  (link-settings/link-settings-section
   {:fx/context (fx/create-context (update-in (domains/build-initial-state) [:backend :link] merge link))}))

(defn- node
  "The description in desc whose :id is id."
  [desc id]
  (first (filter #(and (map? %) (= id (:id %))) (tree-seq coll? seq desc))))

(deftest align-beats-with-link-is-the-beat-sync-switch-test
  (let [following {:carabiner-connected? true :link-enabled? true :sync-enabled? true}
        row (node (card following) "link-align-beats")]
    (is (= "Align beats with Link" (:label row)))
    (is (re-find #"not bars" (:description row)) "beats line up, bars aren't aligned")
    (is (true? (:checked? row)) "on by default")
    (is (false? (boolean (:disabled? row))))
    (is (= {:event/type :timing/link-set-beat-sync} (:on-change row)))
    (testing "offered only while Link is on and its tempo followed"
      (is (true? (:disabled? (node (card (assoc following :sync-enabled? false)) "link-align-beats"))))
      (is (true? (:disabled? (node (card (assoc following :link-enabled? false)) "link-align-beats")))))
    (is (false? (:checked? (node (card (assoc following :beat-sync? false)) "link-align-beats"))))))
