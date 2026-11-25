(ns laser-show.ui-test.shows-tab-test
  "The bare Shows tab (plans/2026-11-25-timeline-shows 02 S2 step 10, 01
   §7): the library as a list by title, ▶ and ✎ on each row, + New Show and
   the count, the placeholder while it is empty, and the banner for what
   Open moved from the grid, with its ✕. The preview stays beside it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [laser-show.events.core :as events]
            [laser-show.state.core :as state]
            [laser-show.ui-test.fixtures :as fixtures]
            [laser-show.ui-test.testfx :as ui])
  (:import [javafx.scene Node]
           [javafx.scene.control Button]))

(use-fixtures :once fixtures/with-toolkit)
(use-fixtures :each fixtures/with-app)

(def ^:private zebra #uuid "00000000-0000-0000-0000-0000000000a1")
(def ^:private alpha #uuid "00000000-0000-0000-0000-0000000000a2")
(def ^:private untitled #uuid "00000000-0000-0000-0000-0000000000a3")

(defn- show [title]
  {:name title :editor-type :timeline :tracks [] :items []})

(defn- top-to-bottom
  "The ids of the nodes matching selector whose ids start with prefix, top
   to bottom, without prefix."
  [selector prefix]
  (->> (ui/query-all selector)
       (keep (fn [^Node n]
               (let [id (str (ui/on-fx-thread #(.getId n)))]
                 (when (str/starts-with? id prefix)
                   [(second (ui/scene-bounds n)) (subs id (count prefix))]))))
       (sort-by first)
       (mapv second)))

(defn- recording
  "pipeline, recording each :shows/* event the app handles into sent (its
   type and show), then handling it as before. The handlers may not be
   there yet: the tab's part is what it sends."
  [pipeline sent]
  (fn [event]
    (when (= "shows" (some-> (:event/type event) namespace))
      (swap! sent conj (select-keys event [:event/type :show-id])))
    (pipeline event)))

(defn- open-shows! [robot]
  (ui/click! robot "#shows-tab")
  (ui/wait-for! #(= :shows (get-in (state/get-raw-state) [:ui :active-tab])))
  (ui/assert-visible! "#shows-tab-root"))

(deftest bare-list-opens-and-plays-test
  (let [robot (ui/robot)
        sent (atom [])
        pipeline events/ordered-pipeline]
    (open-shows! robot)
    (is (ui/visible? "#preview-panel") "the preview stays, as on Grid")
    (testing "an empty library"
      (ui/assert-text! "#shows-empty" "No shows yet. + New Show, or New Show from Cue in the cue editor.")
      (ui/assert-text! "#shows-count" "0 shows")
      (is (not (ui/visible? "#shows-migration-banner")) "nothing moved, no banner"))
    (events/write-state! assoc-in [:chains :shows] {zebra (show "Zebra") alpha (show "alpha") untitled (show nil)})
    (testing "a row per show, by title, untitled last"
      (ui/wait-for! #(= 3 (count (top-to-bottom ".list-item" "shows-row-"))))
      (is (= (mapv str [alpha zebra untitled]) (top-to-bottom ".list-item" "shows-row-")))
      (is (= ["alpha" "Zebra" "Untitled"] (mapv #(ui/node-text (str "#shows-title-" %)) [alpha zebra untitled])))
      (is (ui/has-style-class? (str "#shows-title-" untitled) "text-muted") "Untitled, muted")
      (is (not (ui/has-style-class? (str "#shows-title-" alpha) "text-muted")))
      (ui/assert-text! "#shows-count" "3 shows")
      (is (empty? (ui/query-all "#shows-empty"))))
    (testing "▶, + New Show and ✎ send their events"
      (with-redefs [events/ordered-pipeline (recording pipeline sent)]
        (ui/click! robot (str "#shows-play-" zebra))
        (ui/wait-for! #(= [{:event/type :shows/play :show-id zebra}] @sent))
        (ui/click! robot "#shows-new")
        (ui/wait-for! #(= :shows/create (:event/type (peek @sent))))
        (ui/click! robot (str "#shows-edit-" alpha))
        (ui/wait-for! #(= {:event/type :shows/open-editor :show-id alpha} (peek @sent))))
      (is (= [{:event/type :shows/play :show-id zebra}
              {:event/type :shows/create}
              {:event/type :shows/open-editor :show-id alpha}]
             @sent)))))

(deftest migration-banner-shows-what-moved-until-dismissed-test
  (let [robot (ui/robot)
        sent (atom [])
        pipeline events/ordered-pipeline]
    (open-shows! robot)
    (is (not (ui/visible? "#shows-migration-banner")))
    (events/write-state! assoc-in [:ui :shows :migrated] {:moved 2 :timecode-off ["Timeline A2"]
                                                          :vibe-refs-removed 0 :triggers-rewritten 0})
    (ui/assert-visible! "#shows-migration-banner")
    (ui/assert-text! "#shows-migration-text"
                     "Moved 2 timeline cues from the grid into Shows. Timecode turned off for 'Timeline A2' (it overlapped). Saving writes the new layout; older versions won't see these shows.")
    (is (= ["shows-banner-dismiss"] (keep #(when (instance? Button %) (.getId ^Node %)) (ui/children "#shows-migration-banner")))
        "only the ✕: no Show me")
    (with-redefs [events/ordered-pipeline (recording pipeline sent)]
      (ui/click! robot "#shows-banner-dismiss")
      (ui/wait-for! #(= [{:event/type :shows/dismiss-migration}] @sent)))
    (events/write-state! assoc-in [:ui :shows :migrated] nil)
    (ui/wait-for! #(not (ui/visible? "#shows-migration-banner")))
    (is (not (ui/visible? "#shows-migration-banner")) "cleared, it goes")))
