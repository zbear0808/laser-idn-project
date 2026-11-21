# Output drivers: the contract

**Status:** API 1 through D3 landed at `5a9a41c`. **D4 landed** at `4b22368`; D5 hardware acceptance is pending. **Last verified against:** `0d673ea` for discovery (§1, §5.3). Earlier implementation notes retain their historical revisions (`02` and `00` §7).
This doc is the contract every phase in `02-phases.md` builds and tests against. "API 1" is the version that `laser-show.output.api/api-version` (NEW) reports. Step 0's `laser-show.output.driver/validate` checks the table below.

## 1. The driver map (API 1)

A driver is a plain map. Its data keys are copied into app state (`00` §5). Its function keys stay in the runtime registry.

| Key | Required? | Value |
|---|---|---|
| `:id` | yes | A keyword. Built-ins are unqualified (`:idn`, `:lsps`). Add-on drivers **must** be qualified (`:acme/nova`) |
| `:name` | yes | Shown in the Type picker and the projector list, e.g. `"LSPS point stream (UDP)"` |
| `:short-name` | no | D2: a non-blank string for the card's address line before the summary fields (`"LSPS"`, `"IDN"`); without it, `:name`. Copied into the descriptor only when present |
| `:api` | yes | `1` |
| `:transport` | yes | `:network`, `:usb` or `:other`. Only used to group drivers in the UI |
| `:fields` | yes | A vector of field specs (§5.4). The UI builds the driver's settings from them. `[]` is allowed |
| `:capabilities` | no | `{:scan-rate {:min :max :default} :max-points-per-frame n-or-nil}`. The scan-rate field clamps to it (today: `stream/clamp-scan-rate`, `idn/stream.clj:561-568`) |
| `:open` | yes | `(fn [config ctx] session)`. config is §5.2's map. ctx is `{:id projector-id :scan-rate pps :fps 60 :cancelled? f}`; `(f)` is true once the engine is stopping (1D, C-10), so a long open checks it between steps and gives up. Called on the engine's streaming thread (C-7), so it may block (DNS, USB, a native SDK). Throws if the device can't be opened; the core reports it and tries again (§1.1). `ex-data` `{:busy? true :holder "…"}` says another program has it (§1.1, error). Must return a non-nil session (nil counts as a failed open) |
| `:send-frame!` | yes | `(fn [session frame] result)`. result is nil or `{:transfers n :duration-us d}` (§1.2). A throwable with `{:reopen? true}` asks for the session to be closed and opened again (§1.1, reopen) |
| `:close!` | yes | `(fn [session] _)`. Leaves the beam dark and releases everything, threads of the driver's own included (C-11) |
| `:ready?` | no | `(fn [session] bool)`, for device-paced DACs (§1.3). May throw `{:reopen? true}` as `:send-frame!` does |
| `:on-error` | no | `(fn [session throwable] _)`, called after a failed frame, before the blanking frame (IDN uses it to drop its cached address, as `streaming_engine.clj:388` did before D1; now `idn/driver.clj` `on-error`) |
| `:discover` | no | `(fn [{:keys [timeout-ms broadcast-address broadcast-addresses]}] [device …])` (§5.3). Runs in its own future under its driver's classloader. `:broadcast-addresses` (D4, 1D's G10) is a vector with one broadcast address per up, non-loopback IPv4 interface, worked out by the core, e.g. `["10.0.0.255" "192.168.1.255"]`; `:broadcast-address` stays for drivers that send one broadcast |
| `:describe` | no | `(fn [config] "127.0.0.1:7266")`, for logs and the projector list |
| `:status` | no | 1D (G2, G5): `(fn [session] {:level :ok\|:warning\|:error :message "Interlock open" :info {:scan-rate 30303 :buffer-ms 40}})`. Called by the output-status thread, not the streaming one (C-9), so it must never block: it reads atoms the session keeps, no I/O. Sanitized to plain EDN and shown in the projector's status (§1.5) |

### 1.1 Lifecycle and threads

| Step | Thread | What the core does |
|---|---|---|
| start | `output-lifecycle` (`backend/output_runtime.clj:348`) | `streaming-engine/start!` only creates and starts the engine's thread, as it always did. It never calls into the driver (C-7). A driver that isn't installed fails before this, in `multi-engine/create-engine-for-projector`, and becomes the projector's `:failed` message ("Output driver :acme/nova is not installed") |
| open | the engine's own thread (below) | First thing on the thread: `:open`, through `with-loader`, with ctx's `:cancelled?` (`(not @running?)`, `streaming_engine.clj:506-507`). On success the session goes into the engine's `:session`. If it throws (or returns nil), it is reported under `[:engine id]` (`output-errors/report!`, so the status shows it while frames-sent is 0), and the thread waits NEW `REOPEN_MS` = 1000 (waking at once on Stop) and tries again while the engine runs. A throwable that ends an open while the engine is stopping isn't reported (1D, `:516-519`): it is the driver giving up. No frame is sent without a session. A session that opens after Stop was given up on is closed by the streaming thread itself, since `close!` found none (`:528-531`) |
| each frame | `<prefix>-<id>` (the engine's own thread; IDN keeps `idn-streaming-<id>`, `streaming_engine.clj:461-466`, others get `output-streaming-<id>`) | wait (show clock or deadline, unchanged) → `:ready?` (§1.3) → frame provider → `sanitize-frame` → `:send-frame!` → stats |
| error | same | `output-errors/report! [:engine id]`, then `:on-error`, then `:send-frame!` with `[]` (as `:386-396` did before D1; now `streaming_engine.clj:420-441`). While the engine is stopping, `:on-error` and the blanking frame are skipped: the close follows, and the session isn't used again (C-3). 1D (G6): a throwable from `:open` or a send whose `ex-data` has `:busy?` is reported as the string `"In use by <holder>"` when `:holder` is a non-blank string (trimmed, cut to 200 characters), else `"In use by another program"` (`busy-message`, `:235-242`; `report-error!`, `:244-251`), and the status gets `:busy? true` until a frame goes out or another error replaces it |
| reopen | same | 1D (G1): a throwable from `:send-frame!` or `:ready?` (or the blanking send) whose `ex-data` has `:reopen?`, while the engine runs: reported as above, then the loop ends at once (`:425`), with **no `:on-error` and no blanking frame** for the dead session. `run-engine` (`:543-552`) closes the session on `output-close-<id>` within `CLOSE_MS`, as stop does, unless Stop already took it (`drop-session!`, `:533-541`, a `compare-and-set!`, so C-4 holds), clears `:session` and `:last-attempt-ns` (so the wait isn't "not responding"), and goes back to open's path: the first new `:open` comes `REOPEN_MS` later, then retries as above |
| status | `output-status` (`output_runtime.clj:315-327`) | 1D (G2): each tick (`STATUS_INTERVAL_MS` = 250), before the status is built, `poll-driver-statuses!` (`:307-313`) asks every engine in the registry: `streaming-engine/poll-driver-status!` (`streaming_engine.clj:631-649`) calls `:status` through `with-loader` only while the engine runs **and** has a session, catches any throwable (logged once per start, then nil), and keeps `driver/sanitize-status`'s result (`driver.clj:260-276`) for `engine-status` |
| stop | lifecycle thread, or the drain caller (`output_runtime.clj:396-417`) | `stop-all!` clears every engine's `running?` first (unchanged, `streaming_engine.clj:598-612`; from then on ctx's `:cancelled?` is true), joins the threads within `STOP_JOIN_MS`, then, if there is a session, calls `:close!` on a NEW daemon thread `output-close-<id>` joined for up to NEW `CLOSE_MS` = 500. One that overruns is logged and left behind. Engines are closed one after another, so N stuck closes take N × `CLOSE_MS`. Without a session (its `:open` never returned) there is nothing to close, and Stop returns within `STOP_JOIN_MS` |

The core sets each thread's context classloader to the driver's own loader (`::loader`, §4) around every call into the driver.

**Threads of the driver's own (1D, G4).** A driver may start daemon threads in `:open` (they inherit the add-on's context classloader from the streaming thread), for replies, retransmit requests, keepalives or a USB endpoint that must be drained, and must stop them in `:close!` within `CLOSE_MS`. They may only send keepalives, retransmissions of what was already sent, and blank points, never new lit content (C-11), and they never touch app state or call into the app (C-6). What they learn goes into the session, for `:status`. Example: the first third-party add-on's network driver has a `nova-io-<host>` thread of this kind that receives replies, answers retransmits, pings every 100 ms and blanks when the app stops calling.

**Why not interrupt a stuck `:open` (1D, G3).** The add-on proposed that Stop interrupt the streaming thread. Rejected: an interrupt closes any NIO channel the thread is using (`InterruptibleChannel`), which can break the next open's socket, and native calls (libusb) don't expect it. `:cancelled?` is checked by the driver between its own steps instead, and each step stays below `STUCK_MS`.

### 1.2 `:send-frame!` result

| Returned | Stats use |
|---|---|
| `{:transfers 5 :duration-us 23960}` | as given |
| `nil`, or a key missing | `:transfers` 1. `:duration-us` is the frame interval for `[]` (as `streaming_engine.clj:177-178`), otherwise `(stream/scan-duration-us n scan-rate)` (`idn/stream.clj:570-578`) |
| `{:transfers -1}` or a non-number | treated as missing, and a warning is logged once per engine |

`:transfers` is added to the existing `:packets-sent` stat (`streaming_engine.clj:51-63`).

A buffered DAC whose driver queues a frame more than once, to keep `lead-ms` of points in the device (the first third-party add-on), returns the time of everything it queued as `:duration-us`. C-2's "as soon as it can" is then once the queued points have played (40 ms at that add-on's default).

### 1.3 Device-paced drivers (`:ready?`)

When `:ready?` is present, after the usual wait the core polls it every NEW `READY_POLL_US` = 500 µs, until it returns true or until one frame interval has passed since the loop woke up. Only then does it call the provider, so the frame that goes out is always the newest one. The existing minimum gap and keepalive rules still apply.

Examples, at 60 fps (16.7 ms interval):

| Case | Tick | `:ready?` calls | Result |
|---|---|---|---|
| A | 5 at t=0 | true | tick 5's frame goes out at ~0 ms |
| B | 6 at 16.7 ms | false (16.7), false (17.2), true (17.7) | tick 6's frame at ~17.7 ms. If tick 7 was already published, tick 7's |
| C | 7 at 33.3 ms | false until 50.0 ms | nothing is sent. NEW stat `:frames-skipped` +1, and tick 7 counts as seen, so the next wait is for tick 8 |
| D | no `:ready?` (IDN, LSPS) | — | exactly today's behaviour. Every existing `streaming_engine_test` passes |
| E | `:ready?` throws | — | handled like a failed send (§1.1, error) |

### 1.4 Invariants

The tests in `02` check these. C-8 to C-12 came with 1D (`02` § 1D).

- **C-1.** A driver only ever receives frames that went through the provider and then `frame-rules/sanitize-frame` (`idn/frame_rules.clj:19-31`): a vector of `[x y r g b]` doubles, all finite, either empty or at least two points with a dark first point. Values are **not** guaranteed to be in range, so a driver clamps them (the encoder in §2 does).
- **C-2.** `[]` means "dark now". A driver must blank the beam as soon as it can.
- **C-3.** `:send-frame!` and `:ready?` are never called at the same time for one session, and are only called from that engine's thread. A session is opened once and never used after `:close!`. (`:status` is the one call that may overlap them, C-9.)
- **C-4.** `:close!` is called at most once per session. It must work even while a `:send-frame!` is still stuck on the streaming thread (after the join timed out), for example by closing the socket or handle, which unblocks the send.
- **C-5.** While the engine is running, if its last attempt is more than NEW `STUCK_MS` = 1000 ms old, or an `:open` has been in progress for more than `STUCK_MS` (NEW stat `:opening-since-ns`), the projector's status is `{:state :error :error "The output driver is not responding"}` (NEW stat `:last-attempt-ns`, read by `engine-status`, `output_runtime.clj:172-186`). Z1's safety error stays the first branch there (`:178-179`), since it says why the projector is dark. Other projectors keep streaming (one thread each).
- **C-6.** Drivers get no access to app state or events. `laser-show.output.api` exposes none.
- **C-7.** The core never calls into a driver on the `output-lifecycle` thread: `:open`, `:ready?`, `:send-frame!`, `:on-error` and `:describe` run on the engine's own thread, `:close!` on its `output-close-<id>` thread, `:status` on `output-status` (C-9). A driver that blocks anywhere holds up only its own projector, never the lifecycle thread's queue, so Stop always gets through. IDN keeps its wire identical under this rule: its `:open` only makes the socket and state and never blocks, its host is still resolved lazily on the first send and again after an error (`:on-error`), and its close pair is still skipped when the host was never resolved.
- **C-8.** (G1) After a throwable with `:reopen?` from `:send-frame!` or `:ready?`, that session gets nothing but its one `:close!` (C-4), on `output-close-<id>` within `CLOSE_MS`; no `:on-error` and no `[]`. The next session comes from a new `:open`, no sooner than `REOPEN_MS` later. Without `:reopen?` (or with `false`), errors are handled exactly as before 1D.
- **C-9.** (G2) `:status` is called only by the output-status thread, once per `STATUS_INTERVAL_MS` per engine that runs and has a session, through `with-loader`. It may overlap `:send-frame!` or `:ready?`, so it reads atoms only, and it **must never block**: one thread serves every projector's status. A throwable from it is caught, logged once per start and ignored; what it returns reaches app state only through `sanitize-status`.
- **C-10.** (G3) ctx's `:cancelled?` is false while the engine runs and true from the moment Stop clears `running?`, and never turns back. An `:open` that throws once it is true isn't reported; a session it returns anyway is closed at once and never sent to. The core never interrupts a driver's thread.
- **C-11.** (G4) A driver's own threads start in `:open`, are daemon, stop in `:close!`, and never send lit content: only frames from `:send-frame!` (which went through Z1 and `sanitize-frame`, C-1) are lit. They never touch app state (C-6).
- **C-12.** (G6) A throwable with `:busy?` from `:open` or a send shows as `{:state :error :error "In use by <holder>" :busy? true}` (or "In use by another program" without a string `:holder`); the retry cadence is C-7's.

### 1.5 Driver status (`:status`, 1D)

`driver/sanitize-status` (`driver.clj:260-276`, pure) keeps:
- `:level` only if it is `:ok`, `:warning` or `:error`;
- `:message` only if it is a non-blank string, cut to `MAX_STATUS_STRING` = 200 characters;
- `:info` only if it is a non-empty map, made plain EDN by `plain-edn` (`:240-258`): `nil`, booleans, keywords, strings (cut to 200), integers, other finite numbers as doubles (a ratio, a decimal), and vectors and maps (keyword or string keys) of those, at most `MAX_STATUS_ENTRIES` = 64 entries each and `MAX_STATUS_DEPTH` = 4 levels including `:info`. Anything else is dropped: functions, atoms, Java objects, byte arrays, sets, lists and lazy seqs (never realized), NaN and infinities (NaN in app state would make every status differ from the last, so it would be dispatched every tick), symbol keys.
- A result that isn't a map, or has nothing valid, is nil.

| `:status` returns | `sanitize-status` |
|---|---|
| `{:level :warning :message "Interlock open" :info {:scan-rate 30303 :fifo [1 2 {:free 3}] :cb inc :rtt ##NaN :set #{1}}}` | `{:level :warning :message "Interlock open" :info {:scan-rate 30303 :fifo [1 2 {:free 3}]}}` |
| `{:level :purple :message ""}` | nil |
| `"fine"`, or it throws | nil |

`engine-status` (`output_runtime.clj:234-261`) uses it after the core's own branches:

| Driver status | Projector status |
|---|---|
| `:level :error :message "E-stop active" :info {:estop? true}` | `{:state :error :error "E-stop active" :driver-info {:estop? true}}`, below safety, not responding and the core's errors (`:249`). Without a message, "The output driver reports an error" |
| `:level :warning :message "Interlock open"` | `{:state :streaming :fps 59.8 :warning "Interlock open"}`, after a skipped calibration effect's warning: `"Corner Pin skipped; Interlock open"` (`:252-259`). Without a message, "The output driver reports a warning". Only while streaming |
| `:level :ok`, or none | unchanged; `:message` isn't shown |
| any `:info` | `:driver-info`, in every state (`:261`), for wave 2's UI (scan rate, buffer ms, interlock). No view renders it yet |

The core keeps streaming whatever the level. A driver that must go dark blanks the device itself, or throws.

## 2. The point encoder (NEW `laser-show.output.sample-format`)

A driver can describe its sample layout as data and have frames encoded into a `java.nio.ByteBuffer`:

```clojure
{:byte-order :big                     ; or :little
 :fields [[:x :s16] [:y :s16] [:r :u16] [:g :u16] [:b :u16]]}
```

- **Sources:** `:x :y :r :g :b`; `:i` (intensity, the largest of r, g and b); `:dark?` (1 when `t/blanked?`, `animation/types.clj:94-102`, else 0); `:lit?` (1D, G9: its inverse, 1 when not blanked, for a DAC whose flag is lit-high, set for a lit point; `sample_format.clj:200`); `:zero` (padding). `:dark?`, `:lit?` and `:zero` take any type.
- **Types:** `:s8` (1 byte), `:s16` (2), `:u8` (1), `:u12` (2 bytes, values 0–4095), `:u16` (2).
- **Scaling:** round half away from zero, after clamping x and y to [-1, 1] and colours to [0, 1]:
  - x and y as **signed** n-bit: `v × (2^(n−1) − 1)`, so ±32767 or ±127. This is the same range as IDN's `normalized->output-xy` (`idn/output_config.clj:99-111`);
  - x and y as **unsigned**: `(v + 1)/2 × (2^bits − 1)`;
  - colours as unsigned: `c × (2^bits − 1)`.
  - Signed colour types are a layout error.
- **Functions:**
  - `compile-layout [layout]`: validates and throws an `ex-info` listing every problem;
  - `bytes-per-point [compiled]`;
  - `encode-points! [compiled ^ByteBuffer buf frame start end]`: writes points `[start, end)` at the buffer's position and returns how many it wrote;
  - `scale-xy [v type]` and `scale-color [c type]`, for drivers that pack bits themselves.

Examples:

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
| `[[:r :s16]]` | — | `compile-layout` throws `{:problems ["colour :r can't be signed"]}` |

A frame example, using an illustrative little-endian layout (not a claim about any real DAC's format), `{:byte-order :little :fields [[:x :u12] [:y :u12] [:r :u8] [:g :u8] [:b :u8] [:i :u8]]}`, 8 bytes per point. The point `[0.0 1.0 0.2 0.9 0.1]` becomes `00 08 FF 0F 33 E6 1A E6`.

Invariants:
- **E-1.** Each encoded point takes exactly `bytes-per-point` bytes, and the buffer moves forward by `count × bytes-per-point`.
- **E-2.** The mapping never decreases as the input grows, and the endpoints are exact.
- **E-3.** A dark point (C-1) encodes 0 in every colour field, in `:i` and in `:lit?`, and 1 in `:dark?`; a lit point 1 in `:lit?` and 0 in `:dark?`.
- **E-4.** Out-of-range values clamp. Nothing throws on a finite value.
- **E-5.** Encoding allocates nothing per point, checked by counting allocated bytes as in `test/laser_show/vibes/rotation_test.clj:97-115`. Its speed is *unverified* until D2's test has run.

### 2.1 Why the encoder takes today's frames

Frames are vectors of vectors (`animation/types.clj:8-12`). Review 04 #17 proposes flat arrays. If that lands, only the encoder's input loop changes, and drivers that use the encoder don't notice.

## 3. LSPS v1: Laser Show Point Stream (built-in driver `:lsps`)

This is our own protocol: one-way UDP, big-endian, one stream per projector. It is meant for bridges (a separate program that drives hardware) and as the reference for add-on authors. Its spec goes into NEW `docs/OUTPUT_DRIVERS.md` too.

**Header** (20 bytes):

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `"LSPS"` (`4C 53 50 53`) |
| 4 | 1 | version, `1` |
| 5 | 1 | flags: bit 0 `LAST` (the last packet of this frame), bit 1 `CLOSE` (the stream ends). The other bits are 0 |
| 6 | 2 | packet sequence. +1 for every packet, close included. Wraps at 0xFFFF |
| 8 | 2 | frame number. +1 for every frame sent (keepalive resends included). Wraps |
| 10 | 2 | total points in the frame |
| 12 | 2 | index of this packet's first point |
| 14 | 2 | points in this packet |
| 16 | 4 | frame duration, µs (§1.2) |

**Points** (10 bytes each): layout `{:byte-order :big :fields [[:x :s16] [:y :s16] [:r :u16] [:g :u16] [:b :u16]]}`.

**Splitting a frame:** each packet carries up to `floor((max-packet-bytes − 20) / 10)` points: 138 at the default of 1400, and at least 10 at the minimum of 120. A frame longer than 65,535 points is cut to 65,535 (its duration counts only what was sent).

**Config fields:**
- `:host` (`:host`, required);
- `:port` (`:int` 1–65535, default 7266, Q-5);
- `:max-packet-bytes` (`:int` 120–65507, default 1400).

No discovery in v1.

**Examples:**

1. Frame `[[0 0 0 0 0] [0.5 -0.5 1 0 0] [1 1 0 0 1]]`, sequence 41, frame 7, at 25,000 pts/s (duration `(3−1)/25000 s` = 80 µs). This is one 50-byte packet:
   ```
   4C 53 50 53 01 01 00 29 00 07 00 03 00 00 00 03 00 00 00 50
   0000 0000 0000 0000 0000
   4000 C000 FFFF 0000 0000
   7FFF 7FFF 0000 0000 FFFF
   ```
2. 600 points at the default size: 5 packets.

   | Packet | flags | first index | count |
   |---|---|---|---|
   | 1–4 | 0x00 | 0, 138, 276, 414 | 138 each |
   | 5 | 0x01 | 552 | 48 |

   Each packet says total 600 and duration 23,960 µs.
3. `[]`: one packet, flags 0x01, total 0, count 0, duration = the frame interval (16,666 µs at 60 fps). For the receiver this means **go dark**.
4. Stop: one packet, flags 0x02, total, count and duration all 0.

**Receiver rules** (for bridge authors):
- Draw a frame only once all of its packets have arrived. A frame with a missing packet is dropped, and the previous one keeps drawing.
- Frame numbers may skip.
- With no packet for more than 1 s, or on `CLOSE`, go dark.

**Invariants:**
- **L-1.** Within a frame the indexes are contiguous from 0, the counts add up to the total, and only the last packet has `LAST`.
- **L-2.** The sequence goes up by exactly 1 per packet.
- **L-3.** Every packet is at most `max-packet-bytes` bytes.
- **L-4.** Points are encoded by §2's encoder, so E-1 to E-4 hold.

## 4. Add-on jars

### 4.1 Descriptor

The descriptor is `laser-show-addon.edn` at the jar root, read with `clojure.edn/read`, so nothing in it is evaluated:

```clojure
{:id :acme/nova-bridge         ; qualified keyword, unique across add-ons
 :name "Acme Nova output"
 :version "0.3.0"
 :api 1                        ; the output API it was built for
 :drivers [acme.nova.driver/driver]   ; vars holding driver maps
 :platforms [:windows [:macos :aarch64]]}  ; optional: where it can run (§4.4). Absent = everywhere
```

The jar holds the add-on's own namespaces and dependencies, including native libraries for the platforms it lists. **It must not bundle Clojure or laser-show**: those are provided by the app. **Add-on namespaces must not start with `laser-show.`** (1D, G8): any jar entry under `laser_show/` counts as bundling laser-show (`addons.clj:182-189`), so `laser-show.acme.nova.driver` is refused; use a root of the add-on's own (`acme.nova.driver`, `com.example.nova.driver`). A root that only starts with the same letters, such as the fixtures' `laser_show_test_addon/`, is fine.

### 4.2 Loading (NEW `laser-show.output.addons/load-all!`, on the startup thread in `app/start!`, before `output-runtime/open!` at `app.clj:206`)

0. Make sure the add-ons folder exists (`Files/createDirectories`), so it is there to open and drop jars into from the first start.
1. Rename every `*.jar.new` in the folder over its `*.jar` (`Files/move` with `REPLACE_EXISTING`). Nothing is loaded yet, so nothing holds the file open. An install writes `.new` because Windows locks a jar while a classloader has it open.
2. Collect the sources:
   - `laser-show-addon.edn` resources on the app classpath (for development: `clj -M:dev:laser-show -Sdeps '{:deps {acme/nova {:local/root "../nova"}}}'`);
   - then the folder's jar files (§4.4 says which files count), sorted by file name.
3. For each source, read and check the descriptor: `:api` must be in `#{1}`, and `:id` must be qualified and not seen before. A jar is also refused if it contains `clojure/core__init.class` or `laser_show/`. A jar whose `:platforms` leaves out this machine is **skipped** before anything in it is loaded.
4. Give each jar its own `URLClassLoader`, with its URL made by `File.toURI().toURL()` (§4.4). Its parent is the startup thread's context classloader, and lookups go to the parent first, so the core always wins. With that loader as the context classloader, `require` each driver var's namespace and deref the var. `Compiler/LOADER` is bound to a `DynamicClassLoader` over the jar's loader while the namespaces load: a REPL binds it, and then Clojure looks there before the context classloader, so `load-all!` also works when called from the REPL. A classpath descriptor's namespaces load with the classpath's own loader, which is also their `::loader`.
5. Validate every driver map: the required keys and functions, valid `:fields`, a qualified `:id` not already in the registry.
6. Register **all** of the add-on's drivers, each with `::loader`, or **none**.
7. Return a status for every source, logged with `:load-ms`. Then `app/start!` dispatches `:addons/loaded {:addons [...] :drivers (driver/descriptors)}`.

### 4.3 Status examples

| Source | Status entry |
|---|---|
| A good jar | `{:id :acme/nova-bridge :name "Acme Nova output" :version "0.3.0" :source ".../addons/acme-nova.jar" :state :loaded :drivers [:acme/nova] :load-ms 840}` |
| No descriptor | `{:source ".../broken.jar" :state :failed :error "No laser-show-addon.edn in broken.jar"}` |
| `:api 2` | `{… :state :failed :error "Built for output API 2; this app has API 1"}` and nothing is required |
| A driver id `:idn`, or one another add-on has | `{… :state :failed :error "Driver id :acme/nova is already taken by :other/addon"}` and none of its drivers is registered |
| `require` throws | `{… :state :failed :error "Could not load acme.nova.driver: …"}` |
| A missing `:send-frame!` | `{… :state :failed :error "Driver :acme/nova: missing :send-frame!"}` |
| The same `:id` twice | the second one is `{… :state :failed :error "Add-on :acme/nova-bridge is already loaded from …"}` |
| Bundles Clojure | `{… :state :failed :error "acme-nova.jar bundles Clojure; build it with Clojure as a provided dependency"}` |
| Has files under `laser_show/` (laser-show bundled, or an add-on namespace `laser-show.acme…`) | `{… :state :failed :error "acme-nova.jar has files under laser_show/: add-on namespaces must not start with laser-show., and laser-show must be a provided dependency, not bundled"}` (1D; before, "… bundles laser-show; build it with laser-show as a provided dependency") |
| `:platforms [:windows]` on a Mac | `{:id :acme/nova-bridge … :state :skipped :error "Not available on macOS (aarch64); it supports Windows"}`. Nothing in it is loaded |

Only `:loaded` entries have `:load-ms`; every source's time is logged. The checks run in this order: the jar can be read, it has a descriptor, the descriptor reads as EDN, its shape (`"Bad laser-show-addon.edn in acme-nova.jar: :id must be a qualified keyword"`, also for `:api` that isn't a number, `:drivers` that isn't a non-empty vector of qualified symbols, `:platforms` that isn't a vector), `:api`, unknown platforms, the bundling check, the platform skip, then the add-on id. So a jar that bundles Clojure fails on every OS (A-5). An add-on id only counts as taken once an add-on with it has **loaded**, so `acme-win.jar` with `:platforms [:windows]` and `acme-mac.jar` with `[:macos]` can share one id. Other rows the code has:

| Case | `:error` |
|---|---|
| A driver var that isn't there | `"Driver var acme.nova.driver/driver is not defined"` |
| A driver that isn't a map | `"Driver acme.nova.driver/driver: a driver must be a map"` |
| Two drivers with one id in an add-on | `"Driver id :acme/nova appears twice in this add-on"` |
| A built-in's driver id | `"Driver id :idn is already taken by a built-in driver"` |
| Several drivers wrong | their errors joined with `"; "`; one driver's problems are joined with `", "` |
| A `.jar.new` that can't be renamed | `{:source ".../acme-nova.jar.new" :state :failed :error "Can't install acme-nova.jar.new: …"}`, and the old jar loads |
| The folder can't be made | `{:source ".../addons" :state :failed :error "Can't create the add-ons folder: …"}`, and only classpath add-ons load |

**Invariants:**
- **A-1.** Loading is all-or-nothing per add-on.
- **A-2.** No add-on can replace a built-in or another add-on's driver.
- **A-3.** A failed or skipped add-on never stops the app from starting, and never stops the other add-ons from loading.
- **A-4.** No descriptor content is evaluated.
- **A-5.** The same folder of jars loads the same way on macOS, Windows and Linux, apart from `:platforms` (§4.4).

### 4.4 Cross-platform rules (OD-12)

**The folder.** `addons-dir` is the `laser-show.addons-dir` JVM property if it is set. Otherwise it is `(io/file (System/getProperty "user.home") "LaserShowProjects" "addons")`, which is the same parent as `persistent/default-projects-dir` (`state/persistent.clj:26-28`, which joins strings; the add-ons code doesn't reuse it for that reason). Paths are always built with `io/file` or `java.nio.file.Path`, never by joining strings. The Settings view shows the path as the OS writes it (`.getAbsolutePath`).

| OS | `user.home` | The add-ons folder |
|---|---|---|
| macOS | `/Users/zubair` | `/Users/zubair/LaserShowProjects/addons` |
| Windows | `C:\Users\Zoë Smith` | `C:\Users\Zoë Smith\LaserShowProjects\addons` |
| Linux | `/home/zubair` | `/home/zubair/LaserShowProjects/addons` |

**Which files count.**

| File in the folder | Treated as |
|---|---|
| `acme-nova.jar`, `Acme-Nova.JAR` | an add-on (the extension is matched without regard to case) |
| `acme-nova.jar.new` | swapped in by step 1 |
| `._acme-nova.jar` (macOS AppleDouble, made when files are copied to FAT or exFAT drives), `.hidden.jar` | ignored: the name starts with `.` |
| `notes.txt`, `.DS_Store`, `Thumbs.db`, a sub-folder | ignored |
| A `.jar` the app can't read (permissions) | `{:state :failed :error "Can't read acme-nova.jar: …"}` |

**Jar URLs.** `File.toURI().toURL()` escapes spaces and characters a URI can't hold (`C:\Users\Zoë Smith\…` → `file:/C:/Users/Zoë%20Smith/…`). It leaves non-ASCII letters as they are: only `URI.toASCIIString` would write `Zo%C3%AB`, and `URLClassLoader` reads both (checked in D3's `path-with-spaces-and-non-ascii-loads-test`). A URL built by hand from a string breaks on spaces, `#` and `%`.

**Locked files (Windows).** A loaded jar can't be replaced or deleted while the app runs. That is why Install writes `.jar.new` (step 1). The Settings list tells you to quit Laser Show before deleting a jar by hand. Each add-on's loader opens its resource URLs (the sources Clojure reads) with the JDK's jar cache off, so closing the loader really closes the file. With the cache on, the JVM keeps the jar open after `URLClassLoader.close` (seen with `lsof` on macOS at D3). The loader closes a failed add-on's loader, so on Windows its jar can be fixed or deleted without quitting.

**Opening the folder.** The `:system/open-folder` effect tries, in order:
1. `Desktop/open` when `Desktop$Action/OPEN` is supported (macOS Finder, Windows Explorer, most Linux desktops);
2. `xdg-open <dir>` on Linux;
3. failing both (or when the folder can't be made), the effect's `:on-failed` event, `:addons/open-folder-failed {:path}`, whose handler shows an `:info` notice with the full path. The calls are replaceable for tests (`events/set-folder-opener!`).

**Platforms.** NEW `addons/current-platform` reads `os.name` and `os.arch`:
- `os.name` "Mac OS X" → `:macos`; a name starting with "Windows" → `:windows`; "Linux" → `:linux`; anything else → `:other`;
- `os.arch` "aarch64" or "arm64" → `:aarch64`; "amd64" or "x86_64" → `:x86-64`.

An entry in `:platforms` is either an OS keyword or an `[os arch]` pair.

| `:platforms` | This machine | Result |
|---|---|---|
| absent | any | loads |
| `[:windows :linux]` | `{:os :macos :arch :aarch64}` | skipped |
| `[[:macos :aarch64]]` | `{:os :macos :arch :x86-64}` | skipped |
| `[:macos]` | `{:os :macos :arch :x86-64}` | loads |
| `[:beos]` | any | failed: `"Unknown platform :beos"` |

Each add-on picks the right native library for the platform itself. The loader only decides whether to load the add-on at all.

**Line endings and encoding.** The descriptor is read as UTF-8 with `clojure.edn/read`, so CRLF and LF both work.

## 5. Projectors, ids, discovery, fields

### 5.1 Projector shapes

```clojure
;; IDN: unchanged (projector.clj:91-104; the id from rig/projector-id, rig.clj:436-440)
:projector-10-0-0-5-1 {:name "Laser L" :host "10.0.0.5" :port 7255 :service-id 1 :service-name nil
                       :unit-id "0A 1B …" :stable-id [:unit "0A 1B …" 1]
                       :zone-groups [] :exclude-from-all? false :tags #{} :enabled? true
                       :scan-rate 25000 :output-config {:color-bit-depth 8 :xy-bit-depth 16}}
;; LSPS, added by hand (id as projector.clj:231 makes it today; its stable id names it, §5.3)
:projector-1763560000000 {:name "Bridge" :driver :lsps
                          :driver-config {:host "127.0.0.1" :port 7266 :max-packet-bytes 1400}
                          :stable-id [:manual :lsps :projector-1763560000000]
                          :zone-groups [] :exclude-from-all? false :tags #{} :enabled? true :scan-rate 25000}
;; An add-on's driver, discovered (disabled, as every discovered output is, projector.clj:134)
:projector-acme-nova-NV-00123-0 {:name "Nova 00123" :driver :acme/nova
                                 :driver-config {:serial "NV-00123" :output 0}
                                 :stable-id [:device :acme/nova "NV-00123" 0]
                                 :zone-groups [] :exclude-from-all? false :tags #{} :enabled? false :scan-rate 30000}
```

A Z1 safety zone (`:safety`) and Z3's `:fit`, when set, sit next to these keys for every driver.

### 5.2 `driver/projector-config [projector]` → `[driver-id config]`

| Projector | Result |
|---|---|
| `{:host "10.0.0.5" :port 7255 :service-id 1 :output-config {:color-bit-depth 8 :xy-bit-depth 16}}` | `[:idn {:host "10.0.0.5" :port 7255 :service-id 1 :channel-id 1 :output-config #OutputConfig{8 16}}]`, exactly what `multi_engine.clj:289-297` builds today |
| `{:host "10.0.0.5"}` | `[:idn {:host "10.0.0.5" :port 7255 :service-id 0 :channel-id 0 :output-config output-config/default-config}]` (16/16, `:297`) |
| `{:driver :lsps :driver-config {:host "h" :port 7266}}` | `[:lsps {:host "h" :port 7266 :max-packet-bytes 1400}]`: the field defaults filled in |
| `{:driver :acme/nova …}`, not installed | throws `ex-info "Output driver :acme/nova is not installed"` (OD-10) |

The `:idn` rows need no registered driver, so step 0 builds them before D1 registers `:idn`.

### 5.3 Discovery and identity

A driver's `:discover` returns devices like this. The core adds `:driver`:

```clojure
{:device-key "NV-00123"                        ; unique and stable within the driver
 :name "Nova 00123"
 :outputs [{:output-id 0 :name "Output A" :driver-config {:serial "NV-00123" :output 0}}]}
```

IDN devices keep their shape and get `:driver :idn` (`output/discovery.clj:62-82`). For another driver, the device key and each output id must be nonblank strings or integers, the output config a map of plain data. Names are optional. An invalid device (including duplicate output ids), or a second device with the same key within a driver, is dropped with a warning; valid siblings still reach the rig.

Every new output becomes a card on the rig strip; the scan matches against existing projectors by stable id (`routing/rig.clj:690-778`). The keys per device shape:

| Device | Stable id (`:stable-id`, `match-discovered`) | Found-output key (`rig/output-key`, `routing/rig.clj:179`; `subs/found-outputs`, `subs.clj:1131`) | Device grouping (`subs/rig-cards`, `subs.clj:1143`; card menu, `rig_strip.clj:345`) |
|---|---|---|---|
| IDN `{:address "10.0.0.5" :unit-id "0A 1B …" :services [{:service-id 1}]}` | `[:unit "0A 1B …" 1]`, or `[:host "10.0.0.5" 1]` without a usable unit id (`routing/rig.clj:160-167`) | `["10.0.0.5" 1]` | by host `"10.0.0.5"` |
| `{:driver :acme/nova :device-key "NV-00123" …}` | `[:device :acme/nova "NV-00123" 0]` | `[:acme/nova "NV-00123" 0]` | by `[:acme/nova "NV-00123"]` |
| A non-IDN projector added by hand | `[:manual :lsps :projector-1763560000000]`: its own id, so it never matches a scan (`rig/settled-ids`, `routing/rig.clj:650`) | none; neither a manual driver projector nor one whose driver lacks `:discover` is marked missing | not grouped |

`rig/identity-label` (`routing/rig.clj:189`) already knows both forms from D2: "Device NV-00123 · output 0" and "Added by hand: a scan doesn't look for it". `rig/settled-ids` gives a hostless projector without a stored id its `rig/manual-stable-id`, and when two share a `[:manual …]` id (a copied file), the projector it names keeps it and the other falls back to its own `[:manual driver id]`, never `[:host nil 0]`. The card shows `driver/summary-parts`, `["LSPS" "127.0.0.1:7266"]`, on two lines; `summary` joins them with " · ".

`rig/driver-projector-id [driver device-key output-id]` (`routing/rig.clj:632`) makes a base id from `"projector-"`, the driver's namespace and name joined by `-`, the key and the output. Every character outside `[A-Za-z0-9-]` becomes `-`. Both drivers' base ids use `rig/unique-projector-id` (`:623`) for `-2`, `-3` … suffixes when taken. IDN keeps `rig/projector-id`.

| Args | Id |
|---|---|
| `:acme/nova "NV-00123" 0` | `:projector-acme-nova-NV-00123-0` |
| `:zeta/beam "0x1A2B" 0` | `:projector-zeta-beam-0x1A2B-0` |
| `:acme/nova "10.0.0.9" 2` | `:projector-acme-nova-10-0-0-9-2` |

A scan never replaces an existing projector. As `match-discovered` does for an IDN unit at a new address (host and port only, `routing/rig.clj:754-758`), a known non-IDN output whose discovered config differs moves: its `:moves` entry is `{:driver-config new-config}` (`:690-699`). This replaces the opaque connection map, not other projector settings.

**The scan runs every discoverer at once** (`output/discovery.clj:154-195`). Results are collected in deterministic order: IDN first, then drivers by id. The 3000 ms budget is shared; an overdue future is cancelled and late results are ignored. Validation and realization take place under the add-on's loader, within that same budget.

**What a discoverer gets (1D, G10; built in D4).** `{:timeout-ms 3000 :broadcast-address "255.255.255.255" :broadcast-addresses ["10.0.0.255" "192.168.1.255"]}`. Once per scan the core takes the first available IPv4 `getBroadcast` on each up, non-loopback interface. Interfaces with none are skipped, and addresses are deduplicated in interface order (`output/discovery.clj:29-57`). The vector can be `[]`. The singular `:broadcast-address` is the scan's selected address (`events/handlers/projector.clj:127-133`). Some OSes send a limited broadcast out of one interface only; the vector lets a network driver reach the others too.

| Machine | `:broadcast-addresses` |
|---|---|
| Wi-Fi 192.168.1.20/24 and Ethernet 10.0.0.2/24, both up | `["192.168.1.255" "10.0.0.255"]` (in `NetworkInterface` order) |
| Ethernet 10.0.0.2/16 only | `["10.0.255.255"]` |
| Only loopback, or no IPv4 | `[]` |

The outcomes:

| Outcome | Event |
|---|---|
| All succeed | `:projectors/scan-complete {:devices [...] :warnings []}` |
| IDN ok, `:acme/nova` throws "libnova not found" | `scan-complete` with the IDN devices and `:warnings ["Acme Nova: libnova not found"]`, shown as a `:warning` notice |
| `:zeta/beam` times out | `scan-complete`, `:warnings ["Zeta: no answer within 3 s"]` |
| Every discoverer fails | `:projectors/scan-failed {:error <IDN's message>}`, as today with IDN alone |

### 5.4 Field specs

```clojure
{:key :port :label "Port" :type :int :min 1 :max 65535 :default 7266 :summary? true}
```

- **Types:** `:host` (a non-blank trimmed string), `:string`, `:int` (`:min`, `:max`), `:choice` (`:options [[value "Label"] …]`), `:boolean`.
- **Optional keys:** `:required?`, `:default`, `:summary?` (shown after the driver name in the projector list: "LSPS · 127.0.0.1:7266"), `:hint`.
- **Coercion:** `driver/coerce-field [spec input]` (D2) → `{:ok v}` or `{:error text}`. A blank input is `{:ok nil}` (unset: the `:default` applies) unless the field is `:required?`; other messages as built: "Port must be a whole number", "Size must be at least 120", "Fast must be on or off". `driver/coerce-config [fields inputs]` → `{:config :errors}` over all of them, the defaults filled in (Add Manual's check). `:projectors/update-driver-config` applies `:ok` values only, so an invalid value leaves the stored one as it was. This is the same rule as host and port today (`projector.clj:272-297`).

| Spec | Input | Result |
|---|---|---|
| `:int 1–65535` | `"7266"` | `{:ok 7266}` |
| `:int 1–65535` | `"99999"` | `{:error "Port must be 1–65535"}` |
| `:host`, required | `"  "` | `{:error "Host is required"}` |
| `:host` | `" 10.0.0.9 "` | `{:ok "10.0.0.9"}` |
| `:choice [[:a "A"] [:b "B"]]` | `:c` | `{:error "Not one of A, B"}` |
| `:boolean` | `true` | `{:ok true}` |

### 5.5 Fingerprint and wanted (`backend/output_runtime.clj:35-50`)

`fingerprint` becomes `(select-keys p [:driver :driver-config :host :port :service-id :output-config :scan-rate])`. IDN projectors have no `:driver` and no `:driver-config`, so their fingerprints are identical to today's.

| Enabled projector | `wanted` |
|---|---|
| IDN, blank host | left out (as today) |
| IDN, valid | `{id {:host … :port … :service-id … :output-config … :scan-rate …}}` (as today) |
| `:lsps` | `{id {:driver :lsps :driver-config {…} :scan-rate 25000}}` |
| `:acme/nova`, not installed | wanted. The start fails, and the status says `Output driver :acme/nova is not installed` |
| Any disabled one | left out |

`wanted` stays pure (it never reads the registry). It runs in `output-sync/sync-needed?` on the event agent (`events/handlers/output_sync.clj:13-23`), and in Z1's `safety-guard/outputting?` (`events/safety_guard.clj:13-18`): keeping non-IDN projectors in `wanted` is what makes Z1 ask before a live LSPS or add-on projector's safety zone gets larger.
