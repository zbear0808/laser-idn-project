(ns laser-show.triggers.janitor
  "Pure expiry and retention helpers for trigger state.")

(defn expired?
  [now-ms ttl-ms last-seen-ms]
  (and (some? last-seen-ms) (>= (- now-ms last-seen-ms) ttl-ms)))

(defn remove-expired
  "Removes entries whose :last-seen (or supplied key) has exceeded ttl-ms."
  ([entries now-ms ttl-ms]
   (remove-expired entries now-ms ttl-ms :last-seen))
  ([entries now-ms ttl-ms timestamp-key]
   (into (empty entries)
         (remove (fn [[_ value]]
                   (expired? now-ms ttl-ms (get value timestamp-key))))
         entries)))

(defn prune-history
  [history max-count]
  (vec (take-last max-count history)))

(defn touch
  [entry timestamp-ms]
  (assoc entry :last-seen timestamp-ms))
