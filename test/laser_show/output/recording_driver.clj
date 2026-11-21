(ns laser-show.output.recording-driver
  "A driver for tests that records every call made to it
   (plans/2026-11-19-output-drivers/02 D1). Not a -test namespace: tests
   require it.")

(defn- answers
  "A :ready answer as a fn of no args: a fn as it is, a sequence of booleans
   one per call (true once it runs out)."
  [ready]
  (if (fn? ready)
    ready
    (let [left (atom (seq ready))]
      (fn []
        (let [[old _] (swap-vals! left next)]
          (if old (boolean (first old)) true))))))

(defn- fail!
  "Throw for call kind i when (fail? i) is truthy: an ex-info whose ex-data
   is what fail? returned when that is a map (e.g. {:reopen? true}), else
   {}."
  [fail? kind i]
  (when-let [failed (and fail? (fail? i))]
    (throw (ex-info (str "test: " kind " " i " failed") (if (map? failed) failed {})))))

(defn recording-driver
  "{:driver d :calls (atom [...]) :frames (atom [...]) :threads (atom [...])}.

   d is a valid API 1 driver. Every call to it is recorded in :calls, in
   order: [:open config ctx], [:ready? answer], [:send n] (n points),
   [:on-error], [:close], [:status]. :frames holds every frame sent, in
   order, and :threads [kind thread-name] for each call. A call is recorded
   before it blocks or throws.

   opts:
   - :id - the driver's id (default :test/recording)
   - :ready - answers :ready?: a fn of no args, or a sequence of booleans
     (true once it runs out). Without it the driver has no :ready?
   - :fail-open? - (fail-open? i) truthy makes open i (0-based) throw; a map
     is the ex-info's ex-data (e.g. {:busy? true :holder \"…\"})
   - :block-open-ms - (block-open-ms i) ms makes open i block that long
   - :on-open - (on-open i config ctx) runs in open i after it is recorded:
     it may block (watching (:cancelled? ctx)) or throw
   - :fail-send? - (fail-send? i) truthy makes send i (0-based) throw; a map
     is the ex-data (e.g. {:reopen? true})
   - :block-send-ms - (block-send-ms i) ms makes send i block that long
   - :fail-ready? - (fail-ready? i) truthy makes :ready? call i throw, as
     :fail-send? does (needs :ready)
   - :block-close-ms - :close! blocks that long
   - :result - (result frame) is what a send returns (default nil)
   - :status - (status session) is the driver's :status (01 §1); without it
     the driver has none"
  ([] (recording-driver {}))
  ([{:keys [id ready fail-open? block-open-ms on-open fail-send? block-send-ms fail-ready?
            block-close-ms result status]
     :or {id :test/recording}}]
   (let [calls (atom [])
         frames (atom [])
         threads (atom [])
         opens (atom -1)
         sends (atom -1)
         readies (atom -1)
         ready-fn (when ready (answers ready))
         record! (fn [call]
                   (swap! threads conj [(first call) (.getName (Thread/currentThread))])
                   (swap! calls conj call))]
     {:calls calls
      :frames frames
      :threads threads
      :driver (cond-> {:id id
                       :name "Recording driver"
                       :api 1
                       :transport :other
                       :fields []
                       :open (fn [config ctx]
                               (let [i (swap! opens inc)]
                                 (record! [:open config ctx])
                                 (when-let [ms (and block-open-ms (block-open-ms i))]
                                   (Thread/sleep (long ms)))
                                 (when on-open
                                   (on-open i config ctx))
                                 (fail! fail-open? "open" i)
                                 {:config config}))
                       :send-frame! (fn [_session frame]
                                      (let [i (swap! sends inc)]
                                        (swap! frames conj frame)
                                        (record! [:send (count frame)])
                                        (when-let [ms (and block-send-ms (block-send-ms i))]
                                          (Thread/sleep (long ms)))
                                        (fail! fail-send? "send" i)
                                        (when result (result frame))))
                       :on-error (fn [_session _throwable]
                                   (record! [:on-error]))
                       :close! (fn [_session]
                                 (record! [:close])
                                 (when block-close-ms
                                   (Thread/sleep (long block-close-ms))))}
                ready-fn (assoc :ready? (fn [_session]
                                          (let [i (swap! readies inc)
                                                answer (boolean (ready-fn))]
                                            (record! [:ready? answer])
                                            (fail! fail-ready? "ready?" i)
                                            answer)))
                status (assoc :status (fn [session]
                                        (record! [:status])
                                        (status session))))})))
