(ns laser-show-test-addon.sample.driver
  "The sample add-on's driver: it keeps every frame it is sent, and on each
   frame records which thread sent it and that thread's context
   classloader. Its helper namespace is only required on the first frame, so
   that require finds the helper only if the caller's context classloader
   is the add-on's own (driver/with-loader).")

(defonce frames (atom []))

(defonce calls (atom []))

(def driver
  {:id :test/sample-stream
   :name "Sample add-on stream"
   :api 1
   :transport :other
   :fields [{:key :label :label "Label" :type :string :default "sample"}]
   :open (fn [config _ctx] {:config config})
   :send-frame! (fn [_session frame]
                  (let [describe (requiring-resolve 'laser-show-test-addon.sample.helper/describe)
                        thread (Thread/currentThread)]
                    (swap! frames conj frame)
                    (swap! calls conj {:thread (.getName thread)
                                       :loader (.getContextClassLoader thread)
                                       :description (describe frame)})
                    nil))
   :close! (fn [_session] nil)})
