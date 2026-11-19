(ns laser-show.views.status-bar-test
  "Unit tests for status bar view components."
  (:require [cljfx.api :as fx]
            [clojure.test :refer [deftest testing is]]
            [laser-show.dev-config :as dev-config]
            [laser-show.state.core :as state]
            [laser-show.state.domains :as domains]
            [laser-show.views.status-bar :as status-bar]
            [clojure.string :as str]))

(def format-us #'laser-show.views.status-bar/format-us)

(deftest format-us-test
  (testing "formats microseconds with 5-digit width and right alignment"
    (is (= "    0µs" (format-us 0)))
    (is (= "    0µs" (format-us nil)))
    (is (= "   42µs" (format-us 42)))
    (is (= "  350µs" (format-us 350)))
    (is (= " 1200µs" (format-us 1200)))
    (is (= "15000µs" (format-us 15000)))
    (is (= 7 (count (format-us 42))))
    (is (= 7 (count (format-us 15000))))))

(deftest status-item-test
  (testing "renders default status-item without fixed width"
    (let [rendered (status-bar/status-item {:label "Test" :value "123"})
          [_ val-label] (:children rendered)]
      (is (= :h-box (:fx/type rendered)))
      (is (= "123" (:text val-label)))
      (is (nil? (:min-width val-label)))
      (is (nil? (:pref-width val-label)))))

  (testing "renders status-item with value-width and monospace font"
    (let [rendered (status-bar/status-item {:label "Base"
                                           :value "   42µs"
                                           :value-width 56
                                           :monospace? true})
          [_ val-label] (:children rendered)]
      (is (= 56 (:min-width val-label)))
      (is (= 56 (:pref-width val-label)))
      (is (= :center-left (:alignment val-label)))
      (is (str/includes? (:style val-label) "Consolas")))))

(defn- font-size
  "The -fx-font-size an inline style sets, in px."
  [style]
  (some->> style (re-find #"-fx-font-size: *(\d+)") second parse-long))

(deftest status-text-is-13-px-test
  (testing "the performer reads the status bar from a step back"
    (let [[label value] (:children (status-bar/status-item {:label "Playing" :value "A1 circle"}))]
      (is (= 13 (font-size (:style label))))
      (is (= 13 (font-size (:style value)))))
    (let [notice (status-bar/notice-status
                  {:fx/context (fx/create-context
                                (assoc-in (domains/build-initial-state) [:ui :notice]
                                          {:id 1 :text "Could not open a.zip" :level :error})
                                state/cache-factory)})]
      (is (= 13 (font-size (:style (second (:children notice))))))))
  (testing "the frame times, for developers, stay small"
    (let [stats (status-bar/frame-stats-status
                 {:fx/context (fx/create-context
                               (assoc-in (domains/build-initial-state) [:backend :streaming :frame-stats]
                                         {:avg-base-us 10 :avg-effects-us 5 :avg-latency-us 15
                                          :p95-latency-us 20 :max-latency-us 30})
                               state/cache-factory)})
          items (map #((:fx/type %) %) (:children stats))]
      (is (= 5 (count items)))
      (doseq [[label value] (map :children items)]
        (is (= 11 (font-size (:style label))))
        (is (= 11 (font-size (:style value))))))))

(defn- cue
  "A playing cue as subs/playing-cues gives it."
  [col row preset-id preset-count]
  {:col col :row row :name nil :first-preset-id preset-id :preset-count preset-count :timeline? false})

(deftest playing-list-test
  (is (nil? (status-bar/playing-list [])) "nothing plays: no item")
  (is (= "A1 circle" (status-bar/playing-list [(cue 0 0 :circle 1)])))
  (testing "each cue by its cell and the name on its cell: a custom name, or the first preset and \"+n\""
    (is (= "A1 Intro · B3 star +2"
           (status-bar/playing-list [(assoc (cue 0 0 :circle 1) :name "Intro") (cue 2 1 :star 3)]))))
  (testing "two by name, then how many more"
    (is (= "A1 circle · A2 square · +2"
           (status-bar/playing-list [(cue 0 0 :circle 1) (cue 1 0 :square 1)
                                     (cue 2 0 :triangle 1) (cue 3 0 :star 1)])))
    (is (= "A1 circle · A2 square · +1"
           (status-bar/playing-list [(cue 0 0 :circle 1) (cue 1 0 :square 1) (cue 2 0 :triangle 1)])))))

(deftest output-health-text-test
  (let [on (fn [state errors] {:running? true :state state :errors errors})
        streaming (fn [fps] {:state :streaming :fps fps})]
    (testing "nothing while streaming is off, or has nothing to stream: the toolbar says so"
      (is (nil? (status-bar/output-health-text {:running? false :state :stopped :errors []} nil)))
      (is (nil? (status-bar/output-health-text {:running? false :state :stopping :errors []} nil)))
      (is (nil? (status-bar/output-health-text (on :idle []) {}))))
    (is (= "Starting…" (status-bar/output-health-text (on :starting []) nil)))
    (is (= "Starting…" (status-bar/output-health-text (on :streaming []) {:a {:state :starting}}))
        "on, but no projector draws yet")
    (testing "the frame rate the projectors draw"
      (is (= "59.8 fps" (status-bar/output-health-text (on :streaming []) {:a (streaming 59.8)})))
      (is (= "59.8 fps" (status-bar/output-health-text (on :streaming []) {:a (streaming 59.8) :b (streaming 59.8)})))
      (is (= "25.0–59.8 fps" (status-bar/output-health-text (on :streaming []) {:a (streaming 59.8) :b (streaming 25.0)}))
          "the slowest and the fastest"))
    (testing "and how many fail"
      (is (= "59.8 fps · 1 error"
             (status-bar/output-health-text (on :error ["B: no host"])
                                            {:a (streaming 59.8) :b {:state :error :error "no host"}})))
      (is (= "2 errors"
             (status-bar/output-health-text (on :error ["A: x" "B: y"])
                                            {:a {:state :error :error "x"} :b {:state :error :error "y"}}))))))

(defn- status-bar-items
  "The components the status bar shows, with dev mode on or off."
  [dev?]
  (with-redefs [dev-config/dev-mode? (constantly dev?)]
    (->> (status-bar/status-bar {:fx/context (fx/create-context (domains/build-initial-state) state/cache-factory)})
         :children
         (map :fx/type)
         set)))

(deftest dev-stats-only-in-dev-mode-test
  (testing "the frame times are for developers"
    (is (contains? (status-bar-items true) status-bar/frame-stats-status))
    (is (not (contains? (status-bar-items false) status-bar/frame-stats-status))))
  (testing "the output's health shows either way"
    (is (contains? (status-bar-items false) status-bar/output-health))
    (is (contains? (status-bar-items true) status-bar/output-health)))
  (testing "the notice is first, then what plays"
    (with-redefs [dev-config/dev-mode? (constantly false)]
      (is (= [status-bar/notice-status status-bar/playing-status]
             (->> (status-bar/status-bar {:fx/context (fx/create-context (domains/build-initial-state) state/cache-factory)})
                  :children
                  (take 2)
                  (map :fx/type)))))))
