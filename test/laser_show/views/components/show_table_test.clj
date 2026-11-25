(ns laser-show.views.components.show-table-test
  "The Shows table's columns, and what a keystroke in its edit field runs
   (plans/2026-11-25-timeline-shows 06 §8)."
  (:require [cljfx.api :as fx]
            [cljfx.lifecycle :as lifecycle]
            [clojure.test :refer [deftest is testing]]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.views.components.show-table :as show-table]
            [laser-show.views.harness :as harness])
  (:import [javafx.scene Parent Scene]
           [javafx.scene.control TableView TextField]))

(deftest columns-and-ids-test
  (let [descs show-table/column-descs]
    (is (= #{"shows-col-status" "shows-col-tc" "shows-col-title" "shows-col-artist" "shows-col-album"
             "shows-col-bpm" "shows-col-key" "shows-col-genre" "shows-col-duration" "shows-col-tc-start"
             "shows-col-tc-end"}
           (set (map :id (vals descs)))))
    (is (= "shows-col-tc-start" (show-table/column-id :tc-start)))
    (is (false? (:sortable (:status descs))) "▶ isn't sortable")
    (is (every? true? (map :sortable (vals (dissoc descs :status)))))
    (is (every? #(= identity (:cell-value-factory %)) (vals descs)) "each cell gets its whole row")))

(def ^:private id #uuid "00000000-0000-0000-0000-0000000000a1")

(defn- table-state []
  (-> (domains/build-initial-state)
      (assoc-in [:chains :shows] {id {:name "Strobe" :editor-type :timeline :show/info {:artist "deadmau5"}
                                      :timeline/tempo-bpm 128.0 :tracks [] :items []}})
      (assoc-in [:ui :shows :editing] {:show-id id :column :artist :draft "deadmau5" :invalid? false})))

(defn- lay-out! [^Parent root]
  (.applyCss root)
  (.layout root))

(deftest draft-keystrokes-run-one-body-test
  (let [ran (atom [])
        call-context-fn @#'lifecycle/call-context-fn]
    (with-redefs-fn {#'lifecycle/call-context-fn (fn [context desc]
                                                    (swap! ran conj (harness/type-name (:fx/type desc)))
                                                    (call-context-fn context desc))}
      (fn []
        (harness/on-fx
         (fn []
           (let [ctx (volatile! (fx/create-context (table-state) state/cache-factory))
                 desc {:fx/type :v-box :pref-width 900 :pref-height 300
                       :children [{:fx/type show-table/show-table}]}
                 component (volatile! (fx/create-component desc (harness/opts @ctx)))
                 root (fx/instance @component)
                 _ (Scene. root 900 300)
                 field #(some (fn [n] (when (and (instance? TextField n) (= "shows-edit-field" (.getId n))) n))
                              (harness/nodes root))
                 type! (fn [text]
                         (reset! ran [])
                         (vswap! ctx fx/swap-context assoc-in [:ui :shows :editing :draft] text)
                         (vswap! component fx/advance-component desc (harness/opts @ctx))
                         (lay-out! root)
                         (frequencies @ran))]
             (try
               (lay-out! root)
               (testing "the edited cell holds the field, with the draft"
                 (is (some? (field)))
                 (is (= "deadmau5" (.getText ^TextField (field))))
                 (is (instance? TableView (.lookup root "#shows-table"))))
               (testing "a keystroke runs the edit field's body, and no other"
                 (let [runs (type! "deadmau5Z")]
                   (is (= {"laser-show.views.components.show-table/edit-field" 1} runs) (pr-str runs))
                   (is (= "deadmau5Z" (.getText ^TextField (field))) "an unfocused field shows the draft")))
               (finally (fx/delete-component @component (harness/opts @ctx)))))))))))
