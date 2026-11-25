(ns laser-show.ui-test.fixtures
  "Fixtures shared by the TestFX UI test namespaces. Every UI test namespace
   uses both:

     (use-fixtures :once fixtures/with-toolkit)
     (use-fixtures :each fixtures/with-app)

   with-app mounts a fresh app for each test, so any test can run on its own.
   On the first failure or error in a test it saves a screenshot to
   test-results/<namespace>/<test>.png (CI uploads test-results/)."
  (:require [beat-carabiner.core :as bc]
            [clojure.string :as str]
            [clojure.test :as t]
            [clojure.tools.logging :as log]
            [laser-show.app :as app]
            [laser-show.services.show-clock :as show-clock]
            [laser-show.ui-test.testfx :as ui]))

(defn with-toolkit
  "Once fixture: registers TestFX's primary stage, closes the stages after."
  [tests]
  (ui/init-toolkit!)
  (try
    (tests)
    (finally
      (ui/cleanup-toolkit!))))

(defn- screenshot-path []
  (when-let [v (first t/*testing-vars*)]
    (let [{:keys [ns name]} (meta v)]
      (str "test-results/" (str/replace (str (ns-name ns)) "." "/")
           "/" name ".png"))))

(defn- screenshot-on-failure
  "Wrap clojure.test/report so the first :fail/:error in a test captures the
   UI while it is still mounted."
  [report taken?]
  (fn [m]
    (when (and (#{:fail :error} (:type m)) (not @taken?))
      (reset! taken? true)
      (when-let [path (screenshot-path)]
        (try
          (ui/screenshot! nil path)
          (println "UI screenshot:" path)
          (catch Throwable e
            (log/warn e "Could not capture failure screenshot")))))
    (report m)))

(defn- no-real-carabiner
  "bc/connect while a UI test runs, unless it stands Carabiner in
   (input.fixtures/with-carabiner): refused, loudly. A real one would reach
   the Carabiner daemon, or start beat-carabiner's embedded one, from a
   click on a Connect or Link button."
  []
  (log/error "A UI test asked for a real Carabiner connection: refused (stand one in with input.fixtures/with-carabiner)")
  false)

(defn with-app
  "Each fixture: mounts the app with app/start-test! (starter state, no
   hardware or network services) and unmounts it after the test. No real
   Carabiner connection can be made meanwhile (no-real-carabiner)."
  [tests]
  (with-redefs [bc/connect no-real-carabiner]
    (app/start-test!)
    (try
      (ui/sync-fx!)
      (binding [t/report (screenshot-on-failure t/report (atom false))]
        (tests))
      (finally
        (app/stop!)
        (ui/sync-fx!)))))

(defn with-clock
  "Call (f at-ms) while a thread ticks the show clock every 16 ms, as the
   real clock does: at show time t0 + @at-ms (the test moves it), or, without
   t0, at the wall clock's time, so the clock's status (the beat, the cue
   warnings) keeps coming. app/start-test! runs no clock. A cell draws only
   a tick newer than STALE_MS, and an engine sends only one (else an empty
   frame, which is then what its card's thumbnail shows), so the clock has
   to keep ticking while a test reads them: on a loaded machine one read can
   take longer than that (the macOS CI runner)."
  ([f] (with-clock nil f))
  ([t0 f]
   (let [at-ms (atom 0)
         running (atom true)
         ticker (future (while @running
                          (show-clock/tick! (if t0 (+ t0 (* @at-ms 1000000)) (System/nanoTime)))
                          (Thread/sleep 16)))]
     (try
       (f at-ms)
       (finally
         (reset! running false)
         @ticker)))))
