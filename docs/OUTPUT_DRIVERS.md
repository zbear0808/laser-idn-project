# Output drivers

Laser Show sends every projector's frames through an **output driver**. A driver only deals with the wire: it gets a frame of normalized points and sends it to its device however it likes (UDP, TCP, USB or a native SDK). Everything else stays in the app: pacing, keepalive, statistics, error blanking, stop, status and the safety zones. A driver can't skip the safety rules, and a slow or stuck driver can't hang the app.

There are two built-in drivers:

- `:idn`: ILDA Digital Network (IDN-Stream over IDN-Hello), for IDN DACs;
- `:lsps`: the **Laser Show Point Stream**, our own documented UDP point format (§3). Use it to write a *bridge*, a separate program in any language that receives points and drives hardware.

Other drivers come from **add-on jars** (§4).

This document is for people writing a driver or a bridge. It describes **output API 1**. You don't need to know the rest of the app's code.

**Status:** the contract, the point encoder, the LSPS driver, the streaming core that calls every driver this way, add-on jars (loaded at startup, listed in Settings → Add-ons, installed with Install add-on…), and the projector model and UI are in: **Add Manual…** has a Type picker listing every installed driver (the add-ons' among them), a projector's settings show its driver's fields, and its card shows what the driver says (§1.9). **Scan** runs every driver's `:discover` next to IDN's own discovery, and each output it finds becomes a card on the rig strip (§1.12).

Contents:

1. [The driver contract](#1-the-driver-contract)
2. [The point encoder](#2-the-point-encoder)
3. [LSPS v1: the Laser Show Point Stream](#3-lsps-v1-the-laser-show-point-stream)
4. [Add-on jars](#4-add-on-jars)

---

## 1. The driver contract

### 1.1 Points and frames

A **point** is a vector `[x y r g b]` of doubles:

- `x` and `y` are normalized to −1.0 … 1.0. +x is right and +y is up (front projection). (0, 0) is the centre;
- `r`, `g` and `b` are normalized to 0.0 … 1.0;
- a point is **dark** (blanked) when all three colours are below 1e-6. There is no separate blanking or intensity value.

A **frame** is a vector of points, drawn in order. `laser-show.output.api` has the indexes `X Y R G B` and `blanked?`.

### 1.2 The driver map

A driver is a plain Clojure map. It is not a protocol and not a Java interface. A Java or Kotlin author writes a small Clojure wrapper around their classes.

| Key | Required? | Value |
|---|---|---|
| `:id` | yes | A keyword. The built-ins are unqualified (`:idn`, `:lsps`). An add-on's driver **must** be qualified (`:acme/nova`), so it can't take a built-in's or another add-on's id |
| `:name` | yes | Shown in the Type picker and the projector's settings, e.g. `"LSPS point stream (UDP)"` |
| `:short-name` | no | A non-blank string for the projector's card, before its summary (§1.3): `"LSPS"` gives "LSPS · 127.0.0.1:7266". A projector added without a name is called after it and a number ("LSPS 1", "LSPS 2" …). Without it both use `:name`, which a 136 px card cuts off, so give one (`"Nova"`) |
| `:api` | yes | `1` (`laser-show.output.api/api-version`) |
| `:transport` | yes | `:network`, `:usb` or `:other`. Only used to group drivers in the UI |
| `:fields` | yes | A vector of field specs (§1.3). The app builds the driver's settings from them. `[]` is allowed |
| `:capabilities` | no | `{:scan-rate {:min :max :default} :max-points-per-frame n-or-nil}`. The projector's scan-rate setting is clamped to `:scan-rate` |
| `:open` | yes | `(fn [config ctx] session)`. Opens the device and returns your session, any value you like but not `nil`. `config` is the projector's driver settings (§1.3). `ctx` is `{:id projector-id :scan-rate pts-per-s :fps 60 :cancelled? f}`; `(f)` turns true once the projector is stopping (§1.4). It runs on the projector's own streaming thread, so it may block (DNS, USB, a native SDK). Throw if the device can't be opened: the exception's message becomes the projector's status (an `ex-info`'s message as it is, any other exception's after its class name), and the app tries again every second. `ex-data` with `:busy?` says another program has the device (§1.8) |
| `:send-frame!` | yes | `(fn [session frame] result)`. Sends one frame. `result` is `nil` or `{:transfers n :duration-us d}` (§1.5). Throw with `ex-data` `{:reopen? true}` when the session is dead and must be opened again (§1.8) |
| `:close!` | yes | `(fn [session] _)`. Leaves the beam dark and releases everything |
| `:ready?` | no | `(fn [session] bool)`, for device-paced DACs (§1.6). It may throw `{:reopen? true}` too |
| `:on-error` | no | `(fn [session throwable] _)`. Called after a failed frame, before the blanking frame. IDN uses it to forget its cached address |
| `:discover` | no | `(fn [{:keys [timeout-ms broadcast-address broadcast-addresses]}] [device …])`. Lists the devices it can reach, for **Scan** (§1.12) |
| `:describe` | no | `(fn [config] "127.0.0.1:7266")`, for the logs (the UI builds its line from `:summary?` fields, §1.3) |
| `:status` | no | `(fn [session] {:level :ok\|:warning\|:error :message "Interlock open" :info {…}})`: what the device says, for the projector's status (§1.9). It must return at once from what the session already knows: read atoms, never do I/O. It runs on another thread than the sends |

The data keys (`:id :name :short-name :transport :fields :capabilities`) are copied into the app's state as plain data. The functions stay in a registry the UI never sees.

### 1.3 Settings (`:fields`)

Each field spec describes one setting:

```clojure
{:key :port :label "Port" :type :int :min 1 :max 65535 :default 7266 :summary? true}
```

- **Types:** `:host` (a non-blank string, trimmed), `:string`, `:int` (with `:min` and `:max`), `:choice` (with `:options [[value "Label"] …]`), `:boolean`.
- **Optional keys:** `:required?`, `:default`, `:summary?` (shown after the driver's `:short-name` on the projector's card, e.g. "LSPS · 127.0.0.1:7266"; a `:host` field followed by a `:port` one is written `host:port`, other values are joined by " · "), `:hint` (the empty field's prompt in Add Manual).
- The app only stores a value that passes its spec, so an invalid edit leaves the old value. What it says of one that doesn't:

  | Spec | Typed | Result |
  |---|---|---|
  | `:int` 1–65535 | `7266` | stored as `7266` |
  | `:int` 1–65535 | `99999` | "Port must be 1–65535" |
  | `:int` | `72.5` | "Port must be a whole number" |
  | `:host`, `:required?` | (blank) | "Host is required" |
  | `:host` | ` 10.0.0.9 ` | stored as `"10.0.0.9"` |
  | `:choice` `[[:a "A"] [:b "B"]]` | `:c` | "Not one of A, B" |
  | `:boolean` | `true` | stored as `true` |
  | anything not `:required?` | (blank) | unset: the `:default` applies |

- **Add Manual** shows your fields when your driver is picked, and Add stays off until every one is usable. A projector's settings show them again, each applied on Enter or when it loses focus. The projector's driver itself can't be changed: for another connection, add another projector.

The `config` your `:open` gets is the projector's stored settings over the fields' `:default`s: for LSPS, `{:host "127.0.0.1" :port 7266 :max-packet-bytes 1400}`. A key you don't declare in `:fields` can't be edited in the UI.

### 1.4 Lifecycle and threads

| Step | Thread | What the app does |
|---|---|---|
| start | the output lifecycle thread | starts the projector's streaming thread. It never calls into your driver |
| open | the projector's own streaming thread, `output-streaming-<id>` (`idn-streaming-<id>` for IDN) | the thread's first job: `:open`. If it throws (or returns `nil`), the projector's status shows the error and the thread tries again `REOPEN_MS` = 1000 ms later, for as long as the projector runs. Nothing is sent without a session |
| each frame | the same | waits for the show clock's next tick (or a deadline) → `:ready?` (§1.6) → builds the frame (zones, projector effects, the safety zone) → cleans it (§1.7, C-1) → `:send-frame!` → statistics |
| error | the same | reports the error, calls `:on-error`, then `:send-frame!` with `[]` |
| reopen | the same | an error whose `ex-data` has `:reopen?` (§1.8): reports it, calls `:close!` on `output-close-<id>` (as stop does), then `:open` again `REOPEN_MS` later. No `:on-error` and no `[]` for the dead session |
| status | the output status thread, `output-status` | every `STATUS_INTERVAL_MS` = 250 ms, `:status` for each running projector that has a session (§1.9) |
| stop | the lifecycle thread | clears every projector's running flag (so `(:cancelled? ctx)` turns true), stops every streaming thread (it waits up to `STOP_JOIN_MS` = 1000 ms in all), then calls `:close!` on a new daemon thread `output-close-<id>`, which it waits for up to `CLOSE_MS` = 500 ms. A close that takes longer is logged and left behind |

Every call into an add-on's driver runs with the add-on's own classloader as the thread's context classloader, so `require` and `Class/forName` find the add-on's classes.

Each projector has its own thread, so a driver that blocks only stalls its own projector, and Stop always gets through.

**A long `:open`.** Check `((:cancelled? ctx))` between its steps (each handshake try, each USB request), and give up by throwing once it says true: the app doesn't report an error then. If your `:open` returns a session after Stop anyway, the app closes it at once and never sends to it. Keep each step short (below `STUCK_MS` = 1000 ms), or the status says "The output driver is not responding" meanwhile (C-5).

The app never interrupts a projector's streaming thread to stop a driver: an interrupt closes any NIO channel the thread is using and can break a native call. Discovery has its own threads and timeout (§1.12).

### 1.5 The `:send-frame!` result

| You return | The app's statistics use |
|---|---|
| `{:transfers 5 :duration-us 23960}` | as given: 5 packets (or USB transfers…), and the frame takes 23.96 ms to draw |
| `nil`, or a key is missing | `:transfers` 1. `:duration-us` is the frame interval for `[]`, otherwise `(scan-duration-us n scan-rate)` |
| `{:transfers -1}` or a value that isn't a number | treated as missing, with a warning logged once per projector |

A buffered DAC whose driver queues a frame more than once (to keep the device's buffer filled until the next frame) should return the time of everything it queued as `:duration-us`; the statistics otherwise count too little. For such a DAC, C-2's "as soon as it can" is once the points already queued have played.

### 1.6 Device-paced drivers (`:ready?`)

Some DACs say when they can take the next frame. Give such a driver a `:ready?`. After the usual wait for the clock, the app polls it every `READY_POLL_US` = 500 µs until it returns true, or until one frame interval has passed since the loop woke up. Only then does it build the frame, so the frame that goes out is always the newest one.

At 60 fps (16.7 ms a frame):

| Case | Tick | `:ready?` answers | Result |
|---|---|---|---|
| A | 5 at t = 0 | true | tick 5's frame goes out at ~0 ms |
| B | 6 at 16.7 ms | false (16.7), false (17.2), true (17.7) | tick 6's frame goes out at ~17.7 ms (tick 7's, if tick 7 is already there) |
| C | 7 at 33.3 ms | false until 50.0 ms | nothing is sent for tick 7. The `:frames-skipped` statistic goes up by 1, and the next wait is for tick 8 |
| D | no `:ready?` | — | frames go out on the clock |
| E | `:ready?` throws | — | handled like a failed send (§1.4, error) |

### 1.7 Guarantees and rules

What the app guarantees, and what your driver must do:

- **C-1.** A driver only gets frames that went through the safety zone and the cleaning step: a vector of `[x y r g b]` doubles, all finite, either empty or at least two points with a dark first point. Values are **not** guaranteed to be in range, so clamp them. The point encoder (§2) does.
- **C-2.** `[]` means "dark now". Blank the beam as soon as you can.
- **C-3.** `:send-frame!` and `:ready?` are never called at the same time for one session, and are only called from that projector's streaming thread. A session is opened once and never used after `:close!`.
- **C-4.** `:close!` is called at most once per session. It must work even while a `:send-frame!` is still stuck on the streaming thread (the app gave up waiting for it), for example by closing the socket or handle, which ends the stuck call.
- **C-5.** If a running projector's last attempt to send is more than `STUCK_MS` = 1000 ms old, or its `:open` has run for more than `STUCK_MS`, its status says "The output driver is not responding". The other projectors keep streaming.
- **C-6.** Drivers get no access to the app's state or events. `laser-show.output.api` has none.
- **C-7.** The app calls `:open`, `:ready?`, `:send-frame!`, `:on-error` and `:describe` on the projector's streaming thread, `:close!` on its `output-close-<id>` thread, and `:status` on the `output-status` thread. Never on the output lifecycle thread.
- **C-8.** After a `{:reopen? true}` error, that session gets nothing but its `:close!` (once, C-4); the next one comes from a new `:open`.
- **C-9.** `:status` is only called while the projector runs and has a session. It may run while `:send-frame!` or `:ready?` is running on the streaming thread, so read the session's state in a thread-safe way (atoms). What it returns is cleaned before the app keeps it, and a `:status` that throws is ignored.
- **C-10.** `(:cancelled? ctx)` is false while the projector runs and true once it is stopping, and it never turns false again.
- **C-11.** Threads of your own (§1.10) never make light: only frames from `:send-frame!` may be lit.

### 1.8 Errors that say more: `:reopen?` and `:busy?`

An exception's `ex-data` can tell the app what kind of failure it is. Use `ex-info`:

| Thrown by | `ex-data` | What the app does | The projector's status |
|---|---|---|---|
| `:send-frame!` or `:ready?` | `{:reopen? true}` | the session is dead (the USB device was unplugged, the DAC rebooted or dropped the session): reports the error, calls `:close!` on `output-close-<id>` within `CLOSE_MS`, and opens a new session `REOPEN_MS` later, retrying as for a failed `:open`. No `:on-error`, and no `[]` is sent to the dead session | the error's message, until a frame goes out on the new session |
| `:send-frame!` or `:ready?` | anything else, or `{:reopen? false}` | the usual error path (§1.4): `:on-error`, then `[]`, and the same session goes on | the error's message |
| `:open` (or a send) | `{:busy? true :holder "ShowCtl on 10.0.0.3"}` | as any failed open: tried again every `REOPEN_MS` | `{:state :error :error "In use by ShowCtl on 10.0.0.3" :busy? true}` |
| `:open` (or a send) | `{:busy? true}` (no `:holder`, or one that isn't a string) | the same | `{:state :error :error "In use by another program" :busy? true}` |

```clojure
(throw (ex-info "Nova unplugged" {:reopen? true}))
(throw (ex-info "Nova is in use by another controller" {:busy? true :holder "ShowCtl on 10.0.0.3"}))
```

A `:reopen?` error from a projector that is stopping just ends the stream: the stop closes the session.

### 1.9 Driver status (`:status`)

With `:status`, the device's own state shows on the projector's card: an open interlock, an e-stop, the scan rate it really runs at, how full its buffer is.

```clojure
:status (fn [session]
          (let [{:keys [interlock-open? fifo-points scan-rate]} @(:state session)]
            {:level (if interlock-open? :warning :ok)
             :message (when interlock-open? "Interlock open")
             :info {:scan-rate scan-rate :buffer-ms (quot (* 1000 fifo-points) scan-rate)}}))
```

- The `output-status` thread calls it every `STATUS_INTERVAL_MS` = 250 ms for each running projector that has a session, with your add-on's classloader. That is another thread than the sends (C-9), and one thread for every projector, so it **must never block**: read atoms the session already keeps, and do no I/O.
- If it throws, the app ignores it (and logs it once per start). If it returns something that isn't a map, it is ignored too.
- `:level`:

  | `:level` | The projector's status |
  |---|---|
  | `:ok`, missing, or anything else | as without `:status`; the `:message` isn't shown |
  | `:warning` | the state stays (`:streaming`), and `:warning` is the message ("The output driver reports a warning" without one), after any warning of the app's own: `"Corner Pin skipped; Interlock open"` |
  | `:error` | `{:state :error :error message}` ("The output driver reports an error" without one). The app's own errors (safety, not responding, a failed open or send) come first |

  The app keeps streaming whatever the level: if the device must go dark, your driver blanks it or throws.
- `:info` is any small map. It goes into the projector's status as `:driver-info`, in every state, and the projector's card shows its plain entries on a line of up to three wrapped lines, by key (all of it in its tooltip): `{:scan-rate 30303 :buffer-ms 40}` reads "buffer-ms 40 · scan-rate 30303" (nested maps and vectors are kept in the status but not shown). It is made **plain EDN** first, since it is kept in the app's state: keywords, strings (cut to 200 characters), finite numbers (a ratio as a double), booleans, `nil`, and vectors and maps of those (keyword or string keys, at most 64 entries each, 4 levels deep including `:info` itself). Anything else (functions, atoms, Java objects, sets, lists, NaN) is left out:

  ```clojure
  {:scan-rate 30303 :fifo [1 2 {:free 3}] :callback inc :rtt ##NaN :set #{1}}
  ;; becomes :driver-info
  {:scan-rate 30303 :fifo [1 2 {:free 3}]}
  ```

### 1.10 Threads of your own

A driver may start daemon threads in `:open`, for example to read a device's replies, answer retransmit requests, send keepalives, or drain a USB endpoint. The rules:

- start them in `:open` (they then inherit the add-on's context classloader), mark them daemon, and name them after the driver, what they do and the device (`nova-io-10.0.0.9`);
- stop them in `:close!`, within `CLOSE_MS`;
- they may send keepalives, retransmissions of what was already sent, and blank points. **They never send new lit content**: only frames from `:send-frame!` may be lit (C-11), so the safety zones always apply;
- they never touch the app's state or call into the app (C-6). What they learn goes into the session, for `:status` to read.

### 1.11 Logging

`laser-show.output.api/log` writes to the app's log under a logger name of your own, so your add-on needs no logging library and no macros:

```clojure
(api/log :warn "acme.nova" "Nova at 10.0.0.9 is slow")
(api/log :error "acme.nova" e "Nova failed")          ; with the throwable's stack trace
```

The levels are `:trace :debug :info :warn :error`; any other throws an `IllegalArgumentException`. It returns `nil`, and writes nothing when the app's logging has that level off for that name.

### 1.12 Scan (`:discover`)

**Scan** on the Projectors tab (also run at startup) asks IDN and every installed driver with `:discover` at the same time. Your function and the reading of its results run on a worker thread with your add-on's classloader. A new output appears as a card with **On** unchecked. Without `:discover`, use **Add Manual…**.

**What `:discover` gets:**

```clojure
{:timeout-ms 3000                                   ; how long to listen
 :broadcast-address "255.255.255.255"               ; the scan's own, for one broadcast
 :broadcast-addresses ["192.168.1.255" "10.0.0.255"]} ; one per network interface
```

`:broadcast-addresses` lists the first available IPv4 broadcast for each up, non-loopback interface in OS order, skipping duplicates and interfaces without a broadcast. This lets a network driver reach devices on both Ethernet and Wi-Fi when the OS sends a limited broadcast out of just one interface. The singular `:broadcast-address` is the user's selected broadcast and stays available independently.

| Machine | `:broadcast-addresses` |
|---|---|
| Wi-Fi 192.168.1.20/24 and Ethernet 10.0.0.2/24, both up | `["192.168.1.255" "10.0.0.255"]` |
| Ethernet 10.0.0.2/16 only | `["10.0.255.255"]` |
| Only loopback, or no IPv4 | `[]` |

A USB driver ignores both and lists its devices.

**What it returns:** a vector (or any sequential collection, or `nil` for none) of devices:

```clojure
[{:device-key "NV-00123"                 ; unique and stable within your driver: a serial number, a MAC
  :name "Nova 00123"                     ; optional
  :outputs [{:output-id 0                ; unique within the device
             :name "Output A"            ; optional
             :driver-config {:serial "NV-00123" :output 0}}]}]   ; what :open gets (§1.3)
```

- `:device-key` is a non-blank string or an integer. Pick something that stays the same when the device gets a new address: it is how the app knows the device again.
- `:output-id` is a non-blank string or an integer, and each output is one projector. A device with one output has one.
- `:driver-config` is a map of plain EDN data that can be saved in the rig file. Return the complete connection config, including any non-editable settings your driver needs. The app stores it as returned; when opening a session it supplies field defaults for omitted keys (§1.3).
- A device whose shape isn't this is left out, and so is a second device with a key one before it has; each one gets a warning.

**Timeouts and failures.** Return before the scan's shared `:timeout-ms` deadline (3 seconds). The app cancels overdue discovery futures and ignores late results; give device I/O its own timeouts and release any handles in `finally`.

| Outcome | What the user sees |
|---|---|
| Every discoverer answers | the cards; no notice |
| `:discover` throws, for example because a native library is missing | the other discoverers' cards, and a warning with the driver's `:name` and the message: "Acme Nova: libnova not found" |
| Yours doesn't answer in time | the other cards, and "Acme Nova: no answer within 3 s" |
| Yours returns something that isn't a list | "Acme Nova: :discover returned something other than a list of devices" |
| A device is left out | "Acme Nova: left out device NV-2: it has no :outputs" |
| Every discoverer fails, IDN's included | "Could not scan the network: <IDN's error>", as with IDN alone |

Several warnings show as one notice, joined by "; ".

**What happens to what it finds.** Each output is matched to a projector by its identity, `[:device driver-id device-key output-id]` (the projector's `:stable-id`):

- **A new output** gets an id made from the driver, device key and output (`:projector-acme-nova-NV-00123-0`). Characters outside `[A-Za-z0-9-]` become `-`; collisions get `-2`, `-3` and so on. It starts at the driver's default scan rate with the default calibration. Multiple outputs get distinct card titles, e.g. "Nova 00123 · Output A" and "Nova 00123 · Output B".
- **A known output** keeps its projector, whether it's on, its name, calibration, groups and place. If its `:driver-config` changes (say a new IP), the connection config alone is replaced: "Stage left moved (same device)." Keep the identity stable across such moves, just as an IDN unit keeps its unit id at a new address.
- **A projector added by hand** (Add Manual) is never matched by a scan, even for a driver with `:discover`: it is its own (`[:manual driver-id projector-id]`).

Discovery is backfilled into Undo and the saved baseline. A missing driver output **keeps its card**, with "Not found in the last scan". This indication only applies to outputs Scan looks for. When a device has multiple cards, its menu offers "Turn on every output of NV-00123".

### 1.13 A minimal driver

An illustrative driver for a made-up USB DAC (`acme.nova.sdk` stands for your device's library):

```clojure
(ns acme.nova.driver
  (:require [acme.nova.sdk :as sdk]
            [laser-show.output.api :as api])
  (:import [java.nio ByteBuffer]))

(def ^:private max-points 4096)

(def ^:private layout
  (api/compile-layout {:byte-order :little
                       :fields [[:x :u12] [:y :u12] [:r :u8] [:g :u8] [:b :u8] [:i :u8]]}))

(defn- open [{:keys [serial]} {:keys [scan-rate]}]
  {:device (sdk/open! serial)            ; throws if the device isn't there
   :scan-rate scan-rate
   :buf (ByteBuffer/allocateDirect (* max-points (api/bytes-per-point layout)))})

(defn- send-frame! [{:keys [device scan-rate ^ByteBuffer buf]} frame]
  (if (empty? frame)
    (do (sdk/blank! device) nil)         ; nil: the app fills in the defaults
    (let [n (min (count frame) max-points)]
      (.clear buf)
      (api/encode-points! layout buf frame 0 n)
      (.flip buf)
      (sdk/write-frame! device buf scan-rate)
      {:transfers 1 :duration-us (api/scan-duration-us n scan-rate)})))

(defn- close! [{:keys [device]}]
  (sdk/blank! device)
  (sdk/close! device))

(def driver
  {:id :acme/nova
   :name "Acme Nova (USB)"
   :api 1
   :transport :usb
   :fields [{:key :serial :label "Serial number" :type :string :required? true :summary? true}]
   :capabilities {:scan-rate {:min 1000 :max 30000 :default 30000}
                  :max-points-per-frame max-points}
   :open open
   :send-frame! send-frame!
   :close! close!
   :ready? (fn [{:keys [device]}] (sdk/ready? device))
   :discover (fn [_opts]                  ; a USB driver lists what is plugged in (§1.12)
               (for [serial (sdk/list-serials)]
                 {:device-key serial
                  :name (str "Nova " serial)
                  :outputs [{:output-id 0 :driver-config {:serial serial}}]}))
   :describe (fn [{:keys [serial]}] (str "Nova " serial))})
```

The built-in LSPS driver, `laser-show.output.lsps`, is a complete working example over UDP.

---

## 2. The point encoder

Most DACs take a fixed binary record per point. Instead of converting points yourself, describe your record as data and let the encoder (`laser-show.output.api`, implemented in `laser-show.output.sample-format`) write frames into a `java.nio.ByteBuffer`:

```clojure
{:byte-order :big                     ; or :little. :big when left out
 :fields [[:x :s16] [:y :s16] [:r :u16] [:g :u16] [:b :u16]]}
```

**Sources** (the first of each pair):

| Source | Value |
|---|---|
| `:x`, `:y`, `:r`, `:g`, `:b` | the point's values |
| `:i` | intensity: the largest of r, g and b |
| `:dark?` | 1 when the point is dark (`blanked?`), else 0 |
| `:lit?` | the inverse of `:dark?`: 1 when the point isn't dark, else 0. For a DAC whose blanking flag is set for lit points |
| `:zero` | always 0 (padding) |

**Types** (the second):

| Type | Bytes | Range |
|---|---|---|
| `:s8` | 1 | −127 … 127 |
| `:s16` | 2 | −32767 … 32767 |
| `:u8` | 1 | 0 … 255 |
| `:u12` | 2 | 0 … 4095 (the upper 4 bits are 0) |
| `:u16` | 2 | 0 … 65535 |

**Scaling.** x and y are clamped to [−1, 1] and colours to [0, 1], then scaled and **rounded half away from zero**:

- x and y as a **signed** n-bit type: `v × (2^(n−1) − 1)`, so ±32767 or ±127. −32768 and −128 are never used, so the range is symmetric, as in IDN;
- x and y as an **unsigned** type: `(v + 1) / 2 × (2^bits − 1)`, so −1 is 0 and +1 is the top of the range;
- colours (and `:i`) as an unsigned type: `c × (2^bits − 1)`;
- a colour or `:i` with a signed type is a layout error. `:dark?`, `:lit?` and `:zero` take any type.

**Functions:**

- `(compile-layout layout)` checks a layout and returns the compiled form the other functions take. It throws an `ex-info` "Invalid sample layout: …" whose data is `{:problems [...]}`, listing every problem;
- `(bytes-per-point compiled)`;
- `(encode-points! compiled buf frame start end)` writes points `[start, end)` at `buf`'s position and returns how many it wrote. The position moves on by that many × `bytes-per-point`. The buffer's own `ByteOrder` is neither used nor changed: the layout's `:byte-order` decides. If the points don't fit in what remains of the buffer, nothing is written and it throws a `BufferOverflowException`. It allocates nothing per point;
- `(scale-xy v type)` and `(scale-color c type)` give one value, for drivers that pack bits themselves.

**Examples:**

| Input | Type | Output |
|---|---|---|
| x −1.0 | `:s16` | −32767 |
| x 1.0 | `:s16` | 32767 |
| x 0.5 | `:s16` | 16384 (16383.5 rounds away from zero) |
| y −0.5 | `:s16` | −16384 |
| x 2.0 | `:s16` | 32767 (clamped) |
| x −1.0, 0.0, 1.0 | `:u12` | 0, 2048, 4095 |
| r 0.5 | `:u8` | 128 |
| r 1.0 | `:u16` | 65535 |
| `:i` of `[0 0 0.2 0.9 0.1]` | `:u8` | 230 |
| `[[:r :s16]]` | — | `compile-layout` throws, `{:problems ["colour :r can't be signed"]}` |

A frame example with an illustrative little-endian layout (not any real DAC's format), `{:byte-order :little :fields [[:x :u12] [:y :u12] [:r :u8] [:g :u8] [:b :u8] [:i :u8]]}`, 8 bytes a point. The point `[0.0 1.0 0.2 0.9 0.1]` becomes `00 08 FF 0F 33 E6 1A E6`.

**What always holds:**

- **E-1.** Each point takes exactly `bytes-per-point` bytes, and the buffer moves on by count × `bytes-per-point`.
- **E-2.** The mapping never decreases as the input grows, and the endpoints are exact.
- **E-3.** A dark point encodes 0 in every colour field, in `:i` and in `:lit?`, and 1 in `:dark?`. A lit point encodes 1 in `:lit?` and 0 in `:dark?`.
- **E-4.** Out-of-range values clamp. Nothing throws on a finite value.
- **E-5.** Encoding allocates nothing per point (measured: about 3 KB in all for 100 calls of 1,000 points).

---

## 3. LSPS v1: the Laser Show Point Stream

LSPS is a one-way UDP protocol: Laser Show sends, your program (a *bridge*) receives. Everything is **big-endian**. There is one stream per projector: each LSPS projector in Laser Show has its own host, port and socket. There's no handshake, no reply and no discovery: you add an LSPS projector by hand (Add Manual → Type: LSPS) with your bridge's host and port.

### 3.1 Settings

| Field | Type | Default |
|---|---|---|
| `:host` | host name or IP address, required | — |
| `:port` | 1–65535 | **7266** |
| `:max-packet-bytes` | 120–65507 | 1400 |

The host is looked up once, when the stream starts. If it can't be found, the projector shows an error and doesn't start.

### 3.2 Packets

Each datagram is a 20-byte header followed by points.

**Header:**

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `"LSPS"` (`4C 53 50 53`) |
| 4 | 1 | version, `1` |
| 5 | 1 | flags: bit 0 `LAST` (the last packet of this frame), bit 1 `CLOSE` (the stream ends). The other bits are 0 |
| 6 | 2 | packet sequence: +1 for every packet, close included. Wraps from 0xFFFF to 0 |
| 8 | 2 | frame number: +1 for every frame sent (resends of an unchanged frame included). Wraps |
| 10 | 2 | total points in the frame |
| 12 | 2 | index of this packet's first point in the frame |
| 14 | 2 | points in this packet |
| 16 | 4 | frame duration, µs: how long the whole frame takes to draw |

Both counters start at 0 each time the stream starts.

**Points**, 10 bytes each:

| Offset | Size | Value |
|---|---|---|
| 0 | 2 | x, signed: −32767 (left) … 32767 (right) |
| 2 | 2 | y, signed: −32767 (down) … 32767 (up) |
| 4 | 2 | red, unsigned: 0 … 65535 |
| 6 | 2 | green, unsigned |
| 8 | 2 | blue, unsigned |

This is the point encoder's layout `{:byte-order :big :fields [[:x :s16] [:y :s16] [:r :u16] [:g :u16] [:b :u16]]}` (§2), so x and y are `round(v × 32767)` and colours `round(c × 65535)`, clamped, rounded half away from zero. A point with all three colours 0 is dark.

**Splitting a frame.** Each packet carries up to `floor((max-packet-bytes − 20) / 10)` points: 138 at the default of 1400, and at least 10 at the minimum of 120. A frame's packets go out in order, with contiguous indexes. A frame longer than 65,535 points is cut to its first 65,535, and its duration counts only those.

**Duration.** For a frame of n points at the projector's scan rate, `round(10^6 × (n − 1) / scan-rate)` µs (the first point isn't timed), at least 1. For an empty frame, it is one frame interval, `floor(10^6 / fps)`: 16,666 µs at 60 fps.

**Special packets:**

- **Empty frame** (go dark): one packet, flags `LAST`, total 0, index 0, count 0, the duration of one frame interval, and no points.
- **Close** (the stream ends): one packet, flags `CLOSE`. Frame number, total, index, count and duration are all 0, and there are no points. It is the last packet of the stream. If the stream's last send is stuck when Laser Show stops it, the close packet may come from a different source port.

Laser Show sends frames at its frame rate (60 fps by default), and resends the current frame when nothing changes, so a healthy stream never goes quiet for long.

### 3.3 Examples

**1.** The frame `[[0 0 0 0 0] [0.5 -0.5 1 0 0] [1 1 0 0 1]]` with sequence 41, frame 7, at 25,000 pts/s (duration (3 − 1) / 25000 s = 80 µs). This is one 50-byte packet:

```
4C 53 50 53 01 01 00 29 00 07 00 03 00 00 00 03 00 00 00 50     header: LAST, seq 41, frame 7, total 3, index 0, count 3, 80 µs
0000 0000 0000 0000 0000                                         ( 0.0,  0.0) dark
4000 C000 FFFF 0000 0000                                         ( 0.5, -0.5) red
7FFF 7FFF 0000 0000 FFFF                                         ( 1.0,  1.0) blue
```

**2.** 600 points at the default packet size: 5 packets.

| Packet | flags | first index | count | size |
|---|---|---|---|---|
| 1–4 | 0x00 | 0, 138, 276, 414 | 138 each | 1400 bytes |
| 5 | 0x01 | 552 | 48 | 500 bytes |

Each one says total 600 and duration 23,960 µs (599 / 25000 s).

**3.** `[]`: one 20-byte packet, flags 0x01, total 0, count 0, duration 16,666 µs at 60 fps. For the receiver this means **go dark**.

**4.** Stop: one 20-byte packet, flags 0x02, frame number, total, count and duration all 0.

### 3.4 Receiver rules

- Draw a frame only once all of its packets have arrived. A frame with a missing packet is dropped, and the previous one keeps drawing.
- Frame numbers may skip.
- With no packet for more than 1 s, or on `CLOSE`, go dark.
- Ignore a packet whose magic or version you don't know.

What always holds for a stream:

- **L-1.** Within a frame the indexes are contiguous from 0, the counts add up to the total, and only the last packet has `LAST`.
- **L-2.** The sequence goes up by exactly 1 per packet.
- **L-3.** No packet is larger than `max-packet-bytes`.
- **L-4.** The points are encoded as §2 says, so E-1 to E-4 hold.

### 3.5 A receiver in Python

A minimal bridge loop, to show the rules. `draw` and `go_dark` stand for your hardware:

```python
import socket
import struct

HEADER = struct.Struct(">4sBBHHHHHI")   # 20 bytes
POINT = struct.Struct(">hhHHH")         # 10 bytes: x, y, r, g, b

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 7266))
sock.settimeout(1.0)

points, frame_no, expected = [], None, 0
while True:
    try:
        data, _ = sock.recvfrom(65535)
    except socket.timeout:              # nothing for 1 s
        go_dark()
        continue
    if len(data) < HEADER.size:
        continue
    magic, version, flags, seq, frame, total, index, count, duration_us = HEADER.unpack_from(data)
    if magic != b"LSPS" or version != 1:
        continue
    if flags & 0x02:                    # CLOSE
        go_dark()
        points, frame_no, expected = [], None, 0
        continue
    if frame != frame_no or index != expected:
        points, frame_no, expected = [], frame, 0
        if index != 0:                  # this frame's start went missing: drop it
            frame_no = None
            continue
    points.extend(POINT.iter_unpack(data[HEADER.size:HEADER.size + POINT.size * count]))
    expected += count
    if flags & 0x01:                    # LAST
        if expected == total:
            draw(points, duration_us)   # an empty frame means go dark
        points, frame_no, expected = [], None, 0
```

### 3.6 Details of the built-in sender

- The socket isn't connected, so Laser Show keeps sending while your bridge isn't running yet, and the bridge can start or restart at any time.
- The sender doesn't use `:on-error`: the address it looked up at the start is kept until the stream stops.
- When Laser Show stops the stream it sends the close packet first, then closes its socket. If a send is still stuck at that point, it waits at most 100 ms for it, then closes the socket (which ends the stuck send) and sends the close packet from a short-lived socket of its own.

---

## 4. Add-on jars

An add-on is a `.jar` file that brings one or more drivers. Laser Show loads every add-on in its **add-ons folder** once, when it starts. Its drivers then show up next to the built-in ones, in **Add Manual…**'s Type picker and, for drivers with `:discover`, in **Scan** (§1.12). **Settings → Add-ons** lists what loaded, what didn't and why.

The test fixture `test-resources/addons/sample/` in this repo is a small working example of the layout below (it is never built or shipped as an add-on).

### 4.1 The jar

```
acme-nova-0.3.0.jar
├── laser-show-addon.edn          ; the descriptor, at the root (§4.2)
├── acme/nova/driver.clj          ; your namespaces, as source or AOT-compiled classes
├── acme/nova/protocol.clj
├── acme/nova/native/…            ; native libraries, if you have any (§4.3)
└── META-INF/MANIFEST.MF
```

- The jar's file name is up to you; it is how the add-on is installed, replaced and removed (§4.5). Only files whose name ends in `.jar` (in any case: `Acme.JAR` counts) and doesn't start with a dot are loaded. macOS's `._acme.jar` files, `.DS_Store`, text files and sub-folders are ignored.
- Sources are fine: Clojure compiles them as they load, and the app logs how long each add-on took ("Add-on :acme/nova-bridge 0.3.0 loaded from … in 840 ms"). AOT-compile if that is too slow.
- Each jar gets its own classloader. Classes and resources are looked up in the app first, then in your jar, so the app's Clojure and its `laser-show.output.api` are always the ones used.

### 4.2 The descriptor: `laser-show-addon.edn`

```clojure
{:id :acme/nova-bridge                      ; required: a qualified keyword, unique across add-ons
 :name "Acme Nova output"                   ; shown in Settings → Add-ons
 :version "0.3.0"                           ; shown there too
 :api 1                                     ; required: the output API it was built for
 :drivers [acme.nova.driver/driver]         ; required: the vars that hold its driver maps
 :platforms [:windows [:macos :aarch64]]}   ; optional (§4.3); absent means every platform
```

- It is read as EDN (UTF-8, LF or CRLF), never evaluated: reader tricks such as `#=(…)` make it fail.
- `:api` must be `1`, this app's `laser-show.output.api/api-version`. An add-on built for another API version isn't loaded at all.
- `:drivers` is a vector of qualified symbols. For each one the app `require`s its namespace, with your jar's classloader as the thread's context classloader, then reads the var. Each value must be a valid driver map (§1.2) with a **qualified** `:id` (`:acme/nova`) that no built-in and no other add-on has taken.
- An add-on loads **all or nothing**: if any of its drivers is wrong, none of them is registered.
- `:id` is the add-on's own id, not a driver's. Two jars with the same `:id` can't both load: the second one fails. Jars for different platforms (§4.3) may share an id, since only one of them loads on each machine.

### 4.3 Platforms (`:platforms`)

Leave `:platforms` out if your add-on runs everywhere the app does (pure Clojure or Java, a network protocol). Give it when you ship native libraries for some platforms only. Each entry is an OS, `:macos`, `:windows` or `:linux`, or an `[os arch]` pair, where the arch is `:aarch64` or `:x86-64`:

| `:platforms` | This machine | Result |
|---|---|---|
| absent | any | loads |
| `[:windows :linux]` | a Mac on Apple Silicon | **skipped**: "Not available on macOS (aarch64); it supports Windows and Linux" |
| `[[:macos :aarch64]]` | an Intel Mac | skipped |
| `[:macos]` | an Intel Mac | loads |
| `[:beos]` | any | **failed**: "Unknown platform :beos" |

A skipped add-on isn't opened beyond its descriptor: none of its code runs. The app only decides whether to load the add-on; which native library to load for the platform (`os.name`, `os.arch`) is up to your code.

### 4.4 Dependencies and namespaces

- **Provided by the app, never bundled:** Clojure and Laser Show itself. A jar that contains `clojure/core__init.class` fails ("acme-nova.jar bundles Clojure; build it with Clojure as a provided dependency"), and so does one with any file under `laser_show/`.
- **The only Laser Show namespace you may use is `laser-show.output.api`** (the API version, the point indexes, `blanked?`, the point encoder, `scan-duration-us`, `log`). Everything else in the app can change at any time.
- **Your namespaces have a root of your own**: `acme.nova.driver`, `com.example.nova.driver`. They must not start with `laser-show.`: the loader refuses any jar with a file under `laser_show/` ("acme-nova.jar has files under laser_show/: add-on namespaces must not start with laser-show., and laser-show must be a provided dependency, not bundled"). A root that only begins with the same letters, such as `laser-show-acme`, is fine.
- **Anything else your code needs goes into the jar**, under its own package or namespace names: a USB or device library, a small Java helper, native libraries. Clojure namespaces are global to the app, so two add-ons that bundle the same library at different versions would clash; prefer libraries that are already in your own namespace, or none.

A `deps.edn` for an add-on keeps the app out of `:deps` and puts it in a development alias, so the build never includes it:

```clojure
{:paths ["src" "resources"]                ; resources/laser-show-addon.edn
 :deps {}                                  ; Clojure and laser-show: provided by the app
 :aliases
 {:dev {:extra-deps {org.clojure/clojure {:mvn/version "1.12.4"}
                     laser-show/laser-show {:local/root "../laser-show"}}}
  :build {:deps {io.github.clojure/tools.build {:mvn/version "0.10.6"}}
          :ns-default build}}}
```

and its `build.clj` copies `src` and `resources` into the jar with `b/copy-dir` and `b/jar`, nothing else (no uberjar).

### 4.5 Installing and removing

**The add-ons folder:**

| OS | Folder |
|---|---|
| macOS | `/Users/<you>/LaserShowProjects/addons` |
| Windows | `C:\Users\<you>\LaserShowProjects\addons` |
| Linux | `/home/<you>/LaserShowProjects/addons` |

It is `LaserShowProjects/addons` in your home folder, made when Laser Show first starts. The JVM property `-Dlaser-show.addons-dir=<path>` puts it anywhere else. **Settings → Add-ons** shows its full path, and **Open add-ons folder** opens it in Finder, Explorer or your Linux file manager (if that can't be done, a notice shows the path).

**Installing.** Either:

- **Settings → Add-ons → Install add-on…**, and pick the `.jar`. The app checks the jar the way it checks it at startup (it can be read, has a valid descriptor for API 1 with known platforms, and bundles neither Clojure nor Laser Show), then copies it into the folder as `<name>.jar.new`. The notice says "Installed Acme Nova output 0.3.0. Restart Laser Show to load it." ("Replaced …" when a jar of that name is already there), and Settings lists it as "Installed, loads at next start". A jar it refuses is not copied, and the notice says why ("Could not install acme-nova.jar: Built for output API 2; this app has API 1");
- or quit Laser Show and copy the `.jar` into the folder yourself.

Then **restart Laser Show**: add-ons only load at startup. At the start, each `<name>.jar.new` first replaces its `<name>.jar` (a loaded jar can't be replaced while the app runs on Windows, which is why Install writes `.new`), then the jars load in file-name order.

**Updating** is installing the new jar under the same file name, then restarting.

**Removing.** Quit Laser Show, delete the add-on's file from the folder, and start it again. A projector that used one of its drivers stays in your show, with all its settings, and says "Output driver :acme/nova is not installed" until the add-on is back.

### 4.6 What Settings → Add-ons says

Each add-on the app tried to load gets a row: its name and version, its file, and one of these states.

| State | Meaning |
|---|---|
| **Loaded** | Its drivers are registered and listed ("Drivers: Acme Nova (USB) (:acme/nova)"), and can be picked in Add Manual… |
| **Failed: …** | Nothing from it was registered. The reason says what to fix |
| **Skipped: …** | Its `:platforms` leave out this machine (§4.3). Nothing in it was loaded. Not an error |

The reasons a jar fails, in the order they are checked:

| Reason | Example |
|---|---|
| The jar can't be read | `Can't read acme-nova.jar: zip END header not found` |
| No descriptor | `No laser-show-addon.edn in acme-nova.jar` |
| The descriptor isn't EDN, or is empty | `Can't read laser-show-addon.edn in acme-nova.jar: …`, `Bad laser-show-addon.edn in acme-nova.jar: it is empty` |
| Its shape | `Bad laser-show-addon.edn in acme-nova.jar: :id must be a qualified keyword` (also for `:api` that isn't a number, `:drivers` that isn't a vector of qualified symbols, `:platforms` that isn't a vector) |
| Another API | `Built for output API 2; this app has API 1` |
| An unknown platform | `Unknown platform :beos` |
| It bundles Clojure or Laser Show | see §4.4 |
| Its `:id` already loaded | `Add-on :acme/nova-bridge is already loaded from …/acme-nova-old.jar` |
| A namespace that throws while it loads | `Could not load acme.nova.driver: <the exception's message>` |
| A var that isn't there | `Driver var acme.nova.driver/driver is not defined` |
| A driver that breaks the contract | `Driver :acme/nova: missing :send-frame!` (one driver's problems joined with `, `, several drivers' with `; `) |
| A driver id that is taken | `Driver id :acme/nova is already taken by :other/addon`, `Driver id :idn is already taken by a built-in driver`, `Driver id :acme/nova appears twice in this add-on` |
| A `.jar.new` that can't replace its jar | `Can't install acme-nova.jar.new: …` (the old jar loads) |

A failed or skipped add-on never stops the app from starting, and never stops the other add-ons from loading. The app's log has the same lines, and each loaded add-on's load time.

### 4.7 Developing an add-on

Run Laser Show from its own checkout with your add-on on the classpath, and it loads your add-on from its `laser-show-addon.edn` resource before the folder's jars, with no jar to build:

```sh
clj -M:dev:laser-show:mac-arm -Sdeps '{:deps {acme/nova {:local/root "../acme-nova"}}}'
```

Restart the app to pick up a change to a driver namespace that has already loaded: Clojure doesn't load a namespace twice.
