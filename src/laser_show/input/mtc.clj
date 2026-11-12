(ns laser-show.input.mtc
  "MIDI timecode (MTC) decoder.

   Quarter-frame messages (0xF1) arrive 4 per frame, 8 per complete time
   value. Full-frame SysEx messages (F0 7F <dev> 01 01 hh mm ss ff F7) locate
   to a time without playing.

   The decoder is pure (step-quarter-frame, step-full-frame, position) and
   testable without MIDI hardware. The runtime atom below is written from
   the MIDI receiver thread and read by the preview timer. MTC never goes
   through the event queue or the cljfx context: it's 100+ messages a second.
   All connected, enabled MIDI inputs feed this one decoder."
  (:require [laser-show.timeline.timecode :as tc]))


(def rate-codes
  "MTC rate code (bits 5-6 of the hours byte) -> frames per second."
  [24 25 29.97 30])

(def initial-decoder
  {:pieces [nil nil nil nil nil nil nil nil]
   :last-piece nil
   :run 0                ; consecutive pieces received in :direction order
   :last-qf-ns nil
   :synced? false        ; a full sequence assembled since the last gap
   :tc nil               ; latest time value (the frame being shown now)
   :fps nil
   :direction :forward
   :anchor-seconds nil   ; position at :anchor-ns
   :anchor-ns nil
   :located-ns nil})

(def ^:private running-ms
  "A quarter-frame within this many ms means timecode is running."
  100.0)

(def ^:private default-freewheel-ms 500.0)

(def ^:private resync-ns
  "After a gap this long the old time value is stale: wait for a fresh
   sequence before reporting a position again."
  (long (* default-freewheel-ms 1e6)))


(defn- assemble
  "Time value from the 8 pieces: {:tc {:h :m :s :f :drop?} :fps}."
  [pieces]
  (let [[p0 p1 p2 p3 p4 p5 p6 p7] (map long pieces)
        fps (rate-codes (bit-and (bit-shift-right p7 1) 3))]
    {:fps fps
     :tc {:h (bit-or p6 (bit-shift-left (bit-and p7 1) 4))
          :m (bit-or p4 (bit-shift-left (bit-and p5 3) 4))
          :s (bit-or p2 (bit-shift-left (bit-and p3 3) 4))
          :f (bit-or p0 (bit-shift-left (bit-and p1 1) 4))
          :drop? (tc/drop-frame? fps)}}))

(defn- frames-later
  "Seconds of tc plus n frames."
  [t fps n]
  (+ (tc/tc->seconds t fps) (/ (double n) (double fps))))

(defn- stopped-seconds
  "Where timecode stopped: the anchor plus the time up to the last
   quarter-frame (none after a locate)."
  [{:keys [anchor-seconds anchor-ns last-qf-ns direction]}]
  (when anchor-seconds
    (let [run-ns (max 0.0 (- (double (or last-qf-ns 0)) (double anchor-ns)))]
      (max 0.0 (+ (double anchor-seconds)
                  (* (if (= :reverse direction) -1.0 1.0) (/ run-ns 1e9)))))))

(defn step-quarter-frame
  "Apply a quarter-frame data byte (0nnn dddd: piece n, value d) received
   at now-ns.

   Forward: the 8 pieces run 0..7 and the time value is that of the frame
   when piece 0 was sent. Piece 7 arrives 1.75 frames later; by the next
   piece 0 it's 2 frames later, so :tc (what a display shows) is the value
   + 2 frames and the position anchor is the value + 1.75 frames.
   Reverse: pieces run 7..0 and the value is complete at piece 0."
  [decoder qf-byte now-ns]
  (let [b (long qf-byte)
        piece (bit-and (bit-shift-right b 4) 7)
        value (bit-and b 0x0F)
        {:keys [last-piece run last-qf-ns direction]} decoder
        gap? (or (nil? last-qf-ns) (> (- (long now-ns) (long last-qf-ns)) resync-ns))
        dir (cond
              (or gap? (nil? last-piece)) direction
              (= piece (mod (inc (long last-piece)) 8)) :forward
              (= piece (mod (dec (long last-piece)) 8)) :reverse
              :else nil)
        run (cond
              (nil? dir) 1
              gap? 1
              (not= dir direction) 2
              :else (inc (long run)))
        d (cond-> (assoc decoder
                         :pieces (assoc (:pieces decoder) piece value)
                         :last-piece piece
                         :run run
                         :direction (or dir direction)
                         :last-qf-ns now-ns)
            ;; Stale: freeze where it stopped until a fresh sequence arrives
            (and gap? (:synced? decoder))
            (assoc :synced? false
                   :anchor-seconds (stopped-seconds decoder)
                   :anchor-ns now-ns))
        complete? (and (>= (long run) 8)
                       (= piece (if (= :reverse (:direction d)) 0 7)))]
    (if-not complete?
      d
      (let [{t :tc fps :fps} (assemble (:pieces d))
            forward? (= :forward (:direction d))]
        (assoc d
               :synced? true
               :fps fps
               :tc (tc/seconds->tc (frames-later t fps (if forward? 2 0)) fps)
               :anchor-seconds (frames-later t fps (if forward? 1.75 0))
               :anchor-ns now-ns)))))

(defn full-frame?
  "Is sysex-data (SysexMessage.getData: the bytes after F0) an MTC full-frame?"
  [sysex-data]
  (let [d (vec (map #(bit-and (long %) 0xFF) sysex-data))]
    (and (>= (count d) 8)
         (= 0x7F (d 0))
         (= 0x01 (d 2))
         (= 0x01 (d 3)))))

(defn step-full-frame
  "Apply an MTC full-frame message: locate to its time. sysex-data is
   midi-clj's :data, i.e. 7F <dev> 01 01 hh mm ss ff F7. Pieces collected so
   far belong to the old position, so they're dropped. Ignored when it isn't
   a full-frame."
  [decoder sysex-data now-ns]
  (if-not (full-frame? sysex-data)
    decoder
    (let [d (vec (map #(bit-and (long %) 0xFF) sysex-data))
          hr (d 4)
          fps (rate-codes (bit-and (bit-shift-right hr 5) 3))
          t {:h (bit-and hr 0x1F) :m (bit-and (d 5) 0x3F) :s (bit-and (d 6) 0x3F)
             :f (bit-and (d 7) 0x1F) :drop? (tc/drop-frame? fps)}]
      (assoc decoder
             :pieces (:pieces initial-decoder)
             :last-piece nil
             :run 0
             :tc t
             :fps fps
             :anchor-seconds (tc/tc->seconds t fps)
             :anchor-ns now-ns
             :located-ns now-ns))))

(defn position
  "Where timecode is at now-ns: {:state :seconds :fps :tc}.
   :state is :running (a quarter-frame within 100 ms), :freewheel (up to
   freewheel-ms, default 500, still extrapolating at nominal speed) or
   :stopped. :seconds is nil until a full sequence or a full-frame arrived;
   when stopped it's where timecode stopped (or was located to)."
  [{:keys [synced? last-qf-ns anchor-ns anchor-seconds direction fps] :as decoder} now-ns
   {:keys [freewheel-ms] :or {freewheel-ms default-freewheel-ms}}]
  (let [age-ms (when last-qf-ns (/ (- (double now-ns) (double last-qf-ns)) 1e6))
        state (cond
                (or (not synced?) (nil? age-ms)) :stopped
                (< age-ms running-ms) :running
                (< age-ms (double freewheel-ms)) :freewheel
                :else :stopped)
        seconds (cond
                  (nil? anchor-seconds) nil
                  (not synced?) (double anchor-seconds)
                  (= :stopped state) (stopped-seconds decoder)
                  :else (max 0.0 (+ (double anchor-seconds)
                                    (* (if (= :reverse direction) -1.0 1.0)
                                       (/ (- (double now-ns) (double anchor-ns)) 1e9)))))]
    {:state state
     :seconds seconds
     :fps fps
     :tc (when seconds (tc/seconds->tc seconds fps))}))


;; Status for the UI
;;
;; The UI never reads the runtime atom. The preview timer mirrors this small
;; map into [:backend :input :mtc], at most every 100 ms while only the time
;; text changes, and right away when the state or rate change.


(defn status
  "{:state :fps :text}: text is \"HH:MM:SS:FF\" while running or freewheeling."
  [{:keys [state fps tc]}]
  {:state state
   :fps fps
   :text (when (and tc (not= :stopped state)) (tc/format-tc tc))})

(defn mirror-status
  "The status map to store, given the stored one (or nil). Returns `old`
   itself when nothing needs writing."
  [old pos now-ms]
  (let [new (status pos)]
    (cond
      (= (dissoc old :at-ms) new) old
      (or (not= (:state old) (:state new))
          (not= (:fps old) (:fps new))
          (>= (- (long now-ms) (long (:at-ms old 0))) 100)) (assoc new :at-ms now-ms)
      :else old)))


;; Runtime


(defonce ^:private !state (atom initial-decoder))

(defn on-quarter-frame!
  "Called on the MIDI receiver thread with a quarter-frame's data byte."
  [qf-byte]
  (swap! !state step-quarter-frame qf-byte (System/nanoTime)))

(defn on-sysex!
  "Called on the MIDI receiver thread with a SysEx message's data bytes."
  [sysex-data]
  (when (full-frame? sysex-data)
    (swap! !state step-full-frame sysex-data (System/nanoTime))))

(defn current
  "Position right now (see position)."
  []
  (position @!state (System/nanoTime) nil))

(defn reset-decoder!
  "Forget everything received (tests, or a fresh start)."
  []
  (reset! !state initial-decoder))
