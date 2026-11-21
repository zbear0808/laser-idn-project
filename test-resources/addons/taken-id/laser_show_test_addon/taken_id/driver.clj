(ns laser-show-test-addon.taken-id.driver
  "Drivers for the loader's refusals. fresh is valid and its id is free;
   taken has the sample add-on's driver id; idn has the built-in IDN
   driver's; no-send has no :send-frame!; not-a-map is a string. The
   descriptor lists fresh and taken; tests list others in their own.")

(def fresh
  {:id :test/taken-fresh
   :name "Fresh driver"
   :api 1
   :transport :other
   :fields []
   :open (fn [_config _ctx] {})
   :send-frame! (fn [_session _frame] nil)
   :close! (fn [_session] nil)})

(def taken (assoc fresh :id :test/sample-stream :name "Taken driver"))

(def idn (assoc fresh :id :idn :name "Not IDN"))

(def no-send (-> fresh (assoc :id :test/no-send) (dissoc :send-frame!)))

(def not-a-map "a driver")
