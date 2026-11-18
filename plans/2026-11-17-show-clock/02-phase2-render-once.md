# Phase 2 — Render once per tick; every engine sends that tick

**Written:** 2026-11-17   **Written for commit:** `f7fdd79` (`master`)
**Status:** Done 2026-11-17 (`dd106de`, together with Phase 1; as built in §10). S1 and S3 answered A, 2026-11-17, with the audit's changes A1–A9 (`00-overview.md` §9)   **Last verified against:** `dd106de` (§10; §1–9 cite `f7fdd79`, and the audit's additions `fc9d310`)
**Supersedes / Superseded by:** none   **Related findings:** S-1 (rest), S-2, S-8, S-9 (`00-overview.md` §2); review 04 #23 (part), #24 (part), #25 (part), #27, LE1

After Phase 1, show time moves 60 times a second, but every engine still renders the whole show on its own 60 fps deadline. The 16 ms cache only catches some of those renders (c2: 138 a second with 4 projectors), and the two 60 Hz clocks drift past each other, so some ticks go out twice and others not at all.

In this phase the clock renders each active cue once per tick and publishes the result. Each engine wakes when a tick is published, takes its own zones, applies its own projector effects and sends, all on its own thread (S1 A). The preview is built from the same tick, and the cache goes. About 1.5 days, with the fake-DAC checker (A9).

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| Who renders | Each engine: `projector-frame` (`multi_engine.clj:134-188`). Per cue it calls `generate-frames-by-zone-cached` (`:159`), then extracts (`:162`) and combines (`:164,168`) this projector's zones, then applies projector effects (`:187-188`). The preview, `generate-current-frame` (`frame_service.clj:673-772`), renders every cue again, uncached (`:714`) | c2: 30.5 + 59 per projector a second, minus cache hits |
| The cache | `!zone-frame-cache` (`frame_service.clj:62`). Key: `compute-zone-cache-key` (`:64-74`), a 32-bit hash of the chain's id, destination, tracks and items, a 16 ms wall-clock elapsed bucket and the BPM. Valid for 16 ms of wall clock (`:647-649`); pruned above 32 entries (`:654-656`) | Two engines that straddle a bucket both render. A hash collision would serve another chain's frames (review 04 #23) |
| Engine pacing | `streaming-loop` (`streaming_engine.clj:297-363`): park until its own deadline (`:334`), call the provider (`:338`), sanitize (`:340`), wait out the minimum gap (`:342`), send (`:344`), take the next deadline (`:363`) | Each engine keeps its own 60 fps, and doesn't know when show time moved |
| The preview frame | `generate-current-frame` builds `:points` (all cues, blanking between them, `:728-729`), `:zone-frames` (merged **without** blanking, `:741-743`, review 04 #27) and `:cue-destinations`. `get-preview-frame` (`:800-815`) converts every point to a map (`frame->preview-data`, `:794-798`) | A zone view draws a line between two cues' shapes that the laser doesn't draw |
| What's timed | `record-frame-timing!` and the JFR event (`frame_service.clj:757-767`) cover only the preview's render (review 04 #24). Engines record produce plus send (`streaming_engine.clj:348`) | The render behind the hardware is never timed on its own |
| If timing stops | The provider keeps rendering the last positions | Frozen output (c6). Phase 1 keeps the clock running, but a hung render would freeze it the same way |

## 2. Root cause

There is no shared "frame for tick k". Each consumer renders on its own schedule from shared timing that changes underneath it.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `generate-frames-by-zone` (`frame_service.clj:558-621`) | Unchanged. The clock calls it once per active cue per tick |
| `extract-frames-for-zones`, `combine-zone-frames`, `apply-projector-effects` (`multi_engine.clj:62-112`) | Unchanged. They become the engine side, which no longer renders |
| `concatenate-frames` (`frame_service.clj:352-376`) | Also joins the preview's per-zone frames across cues (review 04 #27) |
| The engine loop's error path and its minimum gap (`streaming_engine.clj:315-323,352-362`) | The error path is unchanged and keeps the gap; a new tick's frame no longer waits for it (A2) |
| `current-timestamp-us` (`streaming_engine.clj:135-139`), `update-stats` (`:261-295`) | Timestamps stay the send time (A4); the stats gain `:tick-to-send-us` (A7) |
| `profiler/record-frame-timing!`, `jfr/emit-frame-event!` | Called by the clock for each tick's render, which is the one behind the hardware too |
| `profiler/record-idn-streaming!` and `get-idn-samples` (`frame_profiler.clj:47-61,83-`) | The same API; the samples go into a ring buffer (A8) |
| The fake DAC's decoding, `decode-all` and `messages` (`test/laser_show/idn/fake_dac.clj:132-160`) | The input of the NEW checker (A9) |
| `output-errors` and the projector status (`output_runtime.clj:172-181` reads `[:provider id]`) | A failing cue reports under `[:render cell]`. A stalled clock reports under `[:provider id]`, so each projector's status shows it (S3 A) |
| Phase 1's `show-time/active-runs` and mirror | Unchanged. The mirror's preview frame comes from the tick |

## 4. Changes per namespace

- `laser-show.animation.show-time` (Phase 1's data namespace, which `multi-engine` already reads):
  - NEW `!snapshot` (§5) and `publish! [snapshot]`. `publish!` swaps in the snapshot and calls `notifyAll` on a monitor, both inside one `locking`. The monitor is a `defonce`, so a code reload can't leave the clock and the engines on different monitors (A7). `publish!` numbers the snapshot's `:tick` from a counter that is never reset (a `defonce`), so a tick an engine has seen stays older than every later one, across `show-clock/start!` and `reset-runtime!` too.
  - NEW `latest []` returns the snapshot, or nil before the first tick. `reset-runtime!` clears it.
  - NEW `frame-signal`: `{:tick (fn [] tick) :running? (fn [] bool) :await (fn [seen deadline-ns keep-waiting?] …)}`. `:running?` is true between `show-clock/start!` and `stop!`. `:await` waits on that monitor (`Object/wait` with a timeout, at most 5 ms at a time, like `park-until`, `common/timing.clj:92-104`). It returns once a tick newer than `seen` exists, `deadline-ns` passes, or `(keep-waiting?)` is false because the engine is stopping. It checks the tick and waits inside the same `locking`, so a tick published between the check and the wait can't be missed (A7).
  - NEW `STALE_MS` 250 (S3 A, with the audit's change from 500, A6). Revisit it after the hidden-window check (output runtime `05-phase5-frame-timing.md` §8).
- `laser-show.services.show-clock`:
  - `tick!` renders after the step, while `:playing?`. For each `active-runs` entry:
    - `generate-frames-by-zone` with the run's timing context (`get-cue-timing-context` at the cue's tempo, with the input values, as `frame_service.clj:707-712`);
    - `elapsed-ms` is `(long (:accumulated-ms timing))` instead of the wall-clock elapsed, and `trigger-time` is 0, as for timeline clips (`frame_service.clj:599`). Nothing reads either: every timing context carries its own `:time-ms`, which overrides them (`effects.clj:389,453`). Phase 4 removes both arguments;
    - each cue renders inside its own `try`: a cue that throws renders `{}` and reports `[:render cell]`, and the others still render;
    - the whole render is timed with the profiler and the JFR event, which move here from `frame_service.clj:757-767`.
  - Then `show-time/publish!`, which wakes the engines. The snapshot's `:preview` is a `delay` of `frame-service/preview-data`, so joining every cue and zone again for the preview happens after the engines are awake: in the mirror (every second tick, on this thread) and, from Phase 3, on the FX thread (A3).
- `laser-show.backend.multi-engine`:
  - `projector-frame [projector-id snapshot]` (`:134-188`) no longer renders:
    - for each of the snapshot's cues, in order, it takes this projector's zones and combines them (as `:149-165`);
    - it combines all cues (`:168`);
    - it applies the projector effects with the snapshot's `:projector-ctx`.
    - It returns nil when the snapshot isn't playing, as today, and when there is no snapshot yet (no clock has ticked, as in `start-test!`), without reporting anything (A5). With S3 A, a snapshot older than `STALE_MS` also gives nil: it throws "Show clock stalled", which the provider reports under `[:provider id]` (`:204-211`), so the projector status reads "Error: clojure.lang.ExceptionInfo: Show clock stalled", in the same form as the other output errors.
    - A frame it returns carries the snapshot's `{:tick :at-ns}` as metadata, so the engine knows which tick it is sending without reading the snapshot again (a tick published in between would otherwise be sent twice or skipped).
  - `create-projector-frame-provider` (`:190-211`) calls `(projector-frame id (show-time/latest))`.
  - `create-engine-for-projector` (`:217-249`) passes `:frame-signal show-time/frame-signal`.
- `laser-show.backend.streaming-engine`:
  - `create-engine` (`:64-129`) gets NEW option `:frame-signal`.
  - In `streaming-loop` (`:297-363`), an engine with a signal waits with `(:await signal) seen fallback keep-running?` instead of `park-until` (`:334`). `seen` is the tick it last tried to send: the frame's `:tick` metadata, else the signal's tick read before calling the provider. It advances even when the send fails, so a failing send waits for the next tick, as the deadline makes it wait today.
    - `fallback` is 3 intervals (50 ms) after its last send. Then the engine resends the newest frame as a keepalive (A1). It doesn't fill a gap on screen: in discrete mode the DAC repeats the last frame anyway (Stream §3.3). 50 ms keeps the link alive (Hello §7.2: 1 s) and the config at most 250 ms apart during a stall (the 200 ms resend plus 50 ms, Stream §2.2).
    - A frame for a new tick goes out as soon as it is built, without the minimum gap (A2). The clock never publishes two ticks less than an interval apart unless one was late (`next-deadline`), and the DAC keeps only the newest frame. The error path's empty frame keeps today's half-interval gap (`:352-362`).
    - Timestamps stay the send time (`current-timestamp-us`, `:135-139`), never a tick's time or a deadline (A4).
    - NEW stat `:tick-to-send-us`: the send time minus the frame's `:at-ns`, for frames of a new tick only (a keepalive would count its wait), smoothed like the others in `update-stats` (`:261-295`) (A7).

    The provider, sanitizing and the send are otherwise unchanged.
  - An engine paces itself as today when it has no signal (unit tests) or while no clock is running (`(:running? signal)` false: `start-test!`, which starts no clock, and before `show-clock/start!`) (A5). Tests that stream through real engines without a clock thread therefore keep today's 60 fps, and those that also tick the clock by hand tick at least every `STALE_MS`.
- `laser-show.services.frame-service`:
  - NEW pure `preview-data [cues]` builds `{:points :zone-frames :cue-destinations}` from `[{:cell :zone-frames} …]`, as `generate-current-frame` does today (`:705-743`), except that each zone is joined with `concatenate-frames`.
  - `generate-current-frame`, `get-preview-frame`, the cache (`:56-74`, `:623-659`) and `combine-all-zone-frames` (`:661-671`) go, and with them `get-all-active-cues-data` (`:183-199`), whose only caller is `generate-current-frame`. `multi-engine/combine-zone-frames` stays the one copy (review 04 #25).
  - `frame->preview-data` stays for the mirror until Phase 3.
- `laser-show.profiling.frame-profiler`: `record-idn-streaming!` (`:47-61`) keeps the last 1,000 samples in a ring buffer, instead of `(vec (take-last 1000 (conj s sample)))` inside one shared `swap!` (12.5 µs and 10 KB per call, `00-overview.md` §9). Under S1 A every engine records at the same moment (A8). `get-idn-samples` (`:83-`) returns the same vector as today.
- `laser-show.idn.fake-dac` (test helper, `test/laser_show/idn/fake_dac.clj`): NEW `violations [dac & {:keys [skip]}]` checks every message the fake DAC received against the stream rules (A9). It returns `[{:rule kw :port p :seq n :detail …} …]`, which is empty when all of these hold:
  1. `:hello-header`: a datagram has at least 4 octets, and its command is 0x40 or 0x44 (Hello §2.1, §6.2);
  2. `:sequence`: sequence numbers go up by 1 per packet from each sender (Hello §2.1, §6);
  3. `:message-size`: a channel message's total size is the datagram's minus 4, within 0x0008–0xFF00, and bit 7 of CNL is set (Hello §6.1, Stream §2.1);
  4. `:timestamps`: timestamps never decrease on a sender's channel, modulo 2^32, leaving out sequel fragments (Stream §2.1);
  5. `:config`: a sender's first message on a channel, and its first after a close, carries the channel configuration, and later ones come at most 250 ms apart while it streams (Stream §2.2). The gap is measured with the messages' own timestamps, the sender's clock: on a loaded machine the fake DAC's receiving thread can fall behind and bunch arrival times;
  6. `:frame`: a non-empty frame has at least 2 samples, a dark first sample and a whole number of samples for its configuration (Stream §6.2);
  7. `:unused-bits`: the bits the spec reserves are zero: the upper half of the Hello flags (Hello §2.1), SDM, the CFL bits other than Routing and Close, SCWC, service ID and service mode when Routing isn't set, and the chunk flags other than Once (Stream §1.4, §2.2, §6.2).

  `:skip` names the rules a test breaks on purpose: a provider that stalls skips `:config`, and a send that fails skips `:sequence`.

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `show-time/!snapshot` NEW | runtime atom | the clock thread, once per tick | No / no |
| `[:backend :streaming :current-frame]` | app state (Phase 1's mirror, until Phase 3) | the clock, every second tick, from the snapshot's `:preview` (forcing it after `publish!`) | No / no |

The snapshot, as the clock publishes it:

```clojure
{:tick 18342
 :at-ns 9182736450000                      ; System/nanoTime when it was published
 :playing? true
 :cues [{:cell [0 0] :run r1 :zone-frames {:all [[0.5 0.0 1.0 0.0 0.0] …]}}
        {:cell [1 0] :run r2 :zone-frames {:left […] :right […]}}]
 :projector-ctx {:accumulated-beats 3.5 :accumulated-ms 1750.0 :phase-offset 0.0 :effective-beats 3.5
                 :time-ms 1750.0 :bpm 120.0 :input-values {}}   ; the first cue's, at the global BPM (multi_engine.clj:171-175)
 :preview (delay {:points […] :zone-frames {:all […] :left […] :right […]}   ; built after publish! (A3)
                  :cue-destinations {[0 0] #{:all} [1 0] #{:left :right}}})
 :render-us 182.0}
```

## 6. Events and effects

None. The engines' lifecycle (output runtime Phase 4) is unchanged; only what their providers read changes.

## 7. Data examples and invariants

Frames from the snapshot above:

| Projector's zone groups | Frame sent |
|---|---|
| `[:all]` | `r1`'s `:all`, then this projector's effects |
| `[:left :right]` | `r2`'s `:left`, a blanking jump, `r2`'s `:right`, then this projector's effects |
| `[:all :left]` | `r1`'s `:all`, a blanking jump, `r2`'s `:left` |
| `[:other]` | nothing for its zones: nil, so an empty frame (as today) |
| any, `:playing? false` | nil: an empty frame (as today) |

When each engine sends (S1 A, ticks every 16.7 ms):

| Clock publishes at (ms) | Engine A (a send takes about 1 ms) | Engine B (one host lookup blocks for 40 ms) |
|---|---|---|
| 0, 16.7, 33.3, 50.0 | 0.3, 17.0, 33.6, 50.3: each tick once | 0.3; blocked until 40.3, then it sends the newest tick (33.3's); 50.3 |
| 0, 26.7 (10 ms late), 33.3 | 0.3, 27.0, 33.6: each tick once, in order. With a resend after 1.5 intervals and the half-interval gap it was 0.3, 25.3 (tick 0 again), 33.6 (tick 33.3), and the late tick never went out (A1, A2) | the same |
| 0, then nothing until 60 (a long GC pause) | 0.3; a keepalive resend at 50.3; the new tick at 60.3 | the same |
| nothing for 0.5 s (a hung render) | keepalive resends every 50 ms, then (S3 A) empty frames from 250 ms on, and the status says "Show clock stalled" | the same |
| no clock running (`start-test!`) | paces itself at 60 fps as today; with no snapshot, empty frames and no error (A5) | the same |

**Invariants:**
1. One render per active cue per tick, whatever the number of projectors. c2 should show 60 a second for one cue with 0, 1, 2 or 4 projectors.
2. While ticks arrive, each engine sends every tick once and in order (c1: no repeats), also when a tick is up to two intervals late (A1, A2).
3. For the same app state and timing, each projector's frame equals what `projector-frame` sends today.
4. A preview cell for zone Z shows what an engine on Z sends before its projector effects.
5. Each projector computes its frame (its zones and projector effects) and sends it on its own thread (O6). The clock never sends, and one engine's blocked send never delays another's.
6. A cue whose render throws renders nothing; the other cues still render.
7. (S3 A) No frame from a tick older than `STALE_MS` (250 ms) is sent. While no clock runs, engines pace themselves as today, and with no snapshot yet they send empty frames and report nothing (A5).
8. Timestamps are the time each message is sent, so they still increase on every message (A4; Hello §6, Stream §2.1).
9. A sender sends at least every 50 ms even when no tick comes, with its config at most 250 ms apart (A1; Stream §2.2, Hello §7.2).
10. Every message received in the engine wire, lifecycle and show-clock tests passes `fake-dac/violations` (A9).

## 8. Tests

- `laser-show.services.show-clock-test`:
  - `renders-once-per-tick-test` (c2): counts `generate-frames-by-zone` calls over 60 `tick!`s with 0, 1 and 4 engines registered; each case gives 60 per cue.
  - `failing-cue-renders-empty-test`.
  - `preview-zones-have-blanking-between-cues-test` (review 04 #27).
  - `render-is-timed-test`: one profiler sample per rendering tick.
  - NEW `preview-is-built-after-the-engines-wake-test` (A3): the snapshot's `:preview` isn't realized when `publish!` returns.
- `laser-show.backend.multi-engine-test`:
  - `engine-frame-equals-direct-render-test` (invariant 3). Three scenes: one cue; two cues on two zones; a timeline with a track effect, sent to a projector with an RGB curve. The frame from the snapshot must equal `generate-frames-by-zone` → `extract-frames-for-zones` → `combine-zone-frames` → `apply-effect-chain`, called directly with the same timing.
  - `projector-receives-only-zone-specific-frames-test` (existing, `:255`): built from a snapshot.
  - `stale-snapshot-sends-an-empty-frame-test` (S3 A).
  - NEW `no-snapshot-yet-reports-nothing-test` (A5).
- `laser-show.backend.streaming-engine-test`, with a fake frame signal:
  - `engine-sends-once-per-tick-test`;
  - NEW `engine-sends-a-late-tick-in-order-test` (A1, A2): ticks published at 0, 26.7 and 33.3 ms go out once each, in that order;
  - `engine-resends-when-no-tick-comes-test`: a keepalive every 50 ms, with the config at most 250 ms apart (A1);
  - NEW `frame-signal-loses-no-wakeup-test` (A7): a tick published between the engine's check and its wait wakes it within a millisecond, not at its fallback;
  - NEW `engine-records-tick-to-send-test` (A7);
  - `engine-without-a-signal-paces-itself-test` (today's behaviour, unchanged), and NEW `engine-paces-itself-while-no-clock-runs-test` (A5).
- NEW `laser-show.profiling.frame-profiler-test` (new namespace): `idn-samples-keep-the-last-1000-test` (A8).
- `laser-show.idn.fake-dac-test`: NEW `violations-find-each-broken-rule-test` (A9). One hand-built stream per rule (a lit first sample, a jump in the sequence, a timestamp going back, 300 ms between configs, a wrong total size, a set SDM bit) reports exactly that rule; a real engine's stream reports none.
- Every test in `engine-wire-test`, `output-lifecycle-test` and `show-clock-test` that streams to the fake DAC ends with `(is (empty? (dac/violations …)))` (A9). Those that stall a provider or break sends on purpose (`late-frame-skips-instead-of-bursting-test`, `send-error-keeps-the-engine-alive-test`, the slow-projector tests) skip the rules they break.
- `laser-show.backend.output-lifecycle-test`:
  - `^:timing every-projector-sends-every-tick-once-test` (c1). Three projectors for 2 s: at most 2 % identical neighbours per sender, and every sender's distinct frames come in the same order.
  - `slow-projector-does-not-delay-others-test` and `slow-projector-rate-test` (`:380`, `:467`): their `projector-frame` redefinitions take the new second argument. They pass unchanged otherwise (invariant 5).
- UI, `laser-show.ui-test.streaming-status-test`: passes unchanged. `start-test!` runs no clock, so its engines pace themselves (A5), and it resets the runtime, so a snapshot left by an earlier test can't read as stalled. `(constantly lit)` (`:154`) takes the new argument as it is.
- `frame_service_test`:
  - The `generate-current-frame` tests (`:98-141`, `:383-534`) call `preview-data` with rendered cues.
  - `test-generate-frames-by-zone-cached` (`:294`) goes.
- Probe c1 (`probes/show_clock_probes_test.clj:116-117`) redefines `projector-frame` with one argument; it takes the snapshot too.

Run: `clj -M:mac-arm:test show-clock multi-engine streaming-engine output-lifecycle frame-service frame-profiler fake-dac engine-wire`, `clj -M:mac-arm:test-timing`, `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.streaming-status-test`, both full suites and the probes. c1 should show no repeats and c2 60 a second.

## 9. Out of scope / alternatives considered

- **One app-state snapshot and one input snapshot for every render step.** Today global effects read app state and the input store again for every zone of every cue (`frame_service.clj:259,507,591`). That is Phase 4, with the render context.
- **Rendering only the zones some projector or preview cell uses.** Every cue renders all its zones, as today.
- **Engines at other rates** (`00-overview.md` §7).
- **Moving projector effects to the clock thread.** They are per projector and can be heavy (calibration curves, corner pin), so they stay on each projector's thread (O6). This also keeps `slow-projector-does-not-delay-others-test` meaningful.
- **One IDN link per DAC** (review 04 LE2) stays possible. Each socket is its own IDN session (Hello §7.1), so a multi-head DAC gets one session per projector today. The clock never encodes, and only `send-frame!`'s send loop (`streaming_engine.clj:236-248`) touches the socket, so several projectors' messages can later share one socket and one sequence counter.
- **Stamping each message with its tick's time instead of the send time.** Allowed (a message may go out after its timestamp, Hello §6), and a DAC that buffers by timestamp would see evenly spaced ones. Not done: keepalive resends would repeat a timestamp (valid "but disadvantageous", Stream §2.1), and no DAC is known to use them. Revisit after the hardware check.
- **Waking each engine with `LockSupport/unpark`** instead of one monitor's `notifyAll`. Both measured 20–80 µs at the median (`00-overview.md` §9), and the monitor keeps the fallback timeout simple.

---

## Decisions

> **DECISION S1: Who renders, and when does each engine send?** *(answered 2026-11-17: A)*
>
> Today each engine renders the whole show itself, on its own 60 fps deadline (c1, c2). In every option below, each projector keeps its own streaming thread (O6), and its projector effects run there.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | The clock renders every active cue once per tick. Each engine wakes when the tick is published, takes its zones, applies its projector effects and sends. If no tick comes for 1.5 intervals, it resends the newest | Every projector gets every tick once, within a few ms of the others. One render per tick, however many projectors (c2: 138 → 60 a second with 4). The frame is as fresh as its render | The engines follow the clock's pacing instead of their own deadline: Phase 5's deadline loop now runs in the clock, and the engines wait for it |
> | B | The clock renders once per tick, as in A, but each engine keeps its own 60 fps deadline and sends the newest tick | No change to the engine loop | The two 60 Hz clocks drift past each other, so an engine sometimes sends a tick twice and skips the next (c1's problem, rarer). The frame can be up to one interval older |
> | C | Each engine keeps rendering on its own thread, from the clock's timing for the tick | The smallest change | Every projector still renders the whole show, and the preview renders it again (c2). Projectors can show different ticks. Nothing gets faster for any one projector, because each renders every zone anyway |
>
> **Your answer:** A ("i trust your recommendations", 2026-11-17, after the audit in `00-overview.md` §9), with its changes A1–A5 and A7–A9: a 50 ms keepalive instead of a resend after 1.5 intervals, no minimum gap before a new tick's frame, the preview built after the engines wake, send-time timestamps, engines that pace themselves while no clock runs, a wake-up that can't be lost, a ring buffer for the profiler's samples, and the fake-DAC checker (§4, §7, §8).

> **DECISION S3: What do the lasers show if the clock stops publishing?** *(answered 2026-11-17: A, at 250 ms)*
>
> The clock thread survives errors, but a render could hang (an effect stuck in a loop), or the thread could be starved for a while.
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | After 0.5 s without a new tick, engines send empty frames, and each projector's status says "Show clock stalled" until ticks come back | Never a frozen picture. It's the rule O2 set for errors ("never a stale frame") | A pause longer than half a second (a very long GC pause) blanks the output for that long |
> | B | Keep sending the last tick's frames, as happens today when the preview timer stops (c6) | A stall can't be seen | A hang freezes the picture until someone notices |
>
> **Your answer:** A (2026-11-17, with S1), with `STALE_MS` 250 instead of 500 (A6): half a second of frozen frame is long for audience scanning, and ZGC pauses are well under 1 ms. Revisit it after the hidden-window check (output runtime `05-phase5-frame-timing.md` §8).

---

## 10. As built (2026-11-17)

Committed as `dd106de`, together with Phase 1 (A11); 01 §10 has the change's full verification. §1–9 describe the code before it. Their citations are against `f7fdd79`, and the audit's against `fc9d310`. While it was built, §4 and §8 gained what turned out to be missing: tick numbers that are never reset, the frame's `{:tick :at-ns}`, `:await`'s `keep-waiting?`, the checker's exact rules, and the tests the plan had missed.

**Where it is now** (at `dd106de`)
- `animation/show_time.clj`: `STALE_MS` `:284`, the monitor `:289`, `!snapshot` `:291`, the tick counter `:297`, `publish!` `:301`, `latest` `:312`, `reset-runtime!` `:317`, NEW `stale?` `:327`, NEW `set-clock-running!` `:332`, `await-tick` `:347`, `frame-signal` `:366`.
- `services/show_clock.clj`: `render-cue` `:46`, `record-render!` `:61`, `render` `:70`, `frame-stats` `:93`, `tick!` `:124` (the render, then `publish!`, then the mirror and the reports).
- `backend/multi_engine.clj` (266 → 229 lines): NEW `stalled` `:113`, `projector-frame` `:119`, `create-projector-frame-provider` `:154`, `create-engine-for-projector` `:179` (`:frame-signal` at `:212`). `get-active-cues-data` is gone.
- `backend/streaming_engine.clj`: `initial-stats` `:51`; NEW `KEEPALIVE_INTERVALS` `:65`; `create-engine` `:72` (`:frame-signal`); `update-stats` `:267`, with a NEW 6-arity for the tick-to-send time; `streaming-loop` `:310` (`last-attempt` `:342`, `seen` `:344`); `get-stats` `:509`.
- `services/frame_service.clj`: `concatenate-frames` `:205`, `generate-frames-by-zone` `:412`, NEW `preview-data` `:476`, `frame->preview-data` `:519`, NEW `preview->mirror` `:525`. The cache, `generate-current-frame`, `combine-all-zone-frames`, `get-preview-frame` and `update-preview-frame!` are gone.
- `profiling/frame_profiler.clj`: `idn-capacity` `:19`, `idn-ring` `:21`, `idn-recorded` `:24`, `record-idn-streaming!` `:55`, `get-idn-samples` `:92`.
- `test/laser_show/idn/fake_dac.clj`: `max-config-gap-us` `:200`, `message-violations` `:217`, NEW `check` `:291`, NEW `violations` `:350`.

**Differences from the plan above**

| Plan | As built | Why |
|---|---|---|
| The keepalive is due 3 intervals after the engine's last send (§4, §7) | 3 intervals after its last attempt, sent or not (`last-attempt`) | While every send fails (the network went away), the last send stays in the past, so the engine tried again at once, in a loop: 22,826 tries in 300 ms in `failing-sends-wait-for-the-next-tick-test` |
| A new tick's frame skips the minimum gap; the error path keeps it (§4) | While the clock paces the engine, no frame waits for the gap, keepalives included. The error path, and engines that pace themselves, keep it | A keepalive is due 50 ms after the last attempt, long after half an interval |
| `fake-dac/violations [dac & {:keys [skip]}]` (§4) | NEW pure `check [datagrams & {:keys [skip]}]`; `violations` runs it on everything a fake DAC received | So the rules can be tested on hand-built streams, without a socket |
| Tests that stall a provider skip `:config`; those that break sends skip `:sequence` (§4, §8) | Only `send-error-keeps-the-engine-alive-test` (`breaks! :sequence`) and `failing-sends-wait-for-the-next-tick-test` (`:skip [:sequence]`) leave a rule out. No test that stalls a provider breaks one | The engine resends its configuration with the first message 200 ms after the last one (output runtime Phase 5), so after a 250 ms stall the next message carries it |
| `frame-signal-loses-no-wakeup-test`: woken within a millisecond (§8) | 300 waits with a 2 s fallback, each with a tick published 0–20 µs after the waiter starts; none may take over 500 ms | A lost wake-up waits the whole 2 s. A 1 ms bound would fail on a loaded machine without any lost wake-up |
| The NEW names in §4 | Also NEW: `show-time/stale?`; `set-clock-running!` (`show-clock/start!` and `stop!` set what `:running?` reads); `multi-engine/stalled`; `frame-service/preview->mirror` (the mirror's map form, which `get-preview-frame` built); the 6-arity `update-stats` | – |

**Tests**
- `show-clock-test`: `renders-once-per-tick-test` (60 renders for 60 ticks, with 0, 1 and 4 engines streaming), `failing-cue-renders-empty-test`, `preview-zones-have-blanking-between-cues-test`, `render-is-timed-test`, `preview-is-built-after-the-engines-wake-test`, and NEW `snapshot-shape-test`.
- `multi-engine-test`: `engine-frame-equals-direct-render-test` (the three scenes of §8), `projector-receives-only-zone-specific-frames-test` (built from a snapshot; the frame carries `{:tick :at-ns}`, and a projector on other zones gets nil), `stale-snapshot-sends-an-empty-frame-test` and `no-snapshot-yet-reports-nothing-test`. `idle-frame-provider-returns-nil-without-exceptions-test` publishes snapshots instead of writing app state.
- `streaming-engine-test`, against a fake DAC: the seven tests of §8, and NEW `new-tick-goes-out-without-the-minimum-gap-test` (A2: the frames of two ticks published back to back go out less than 8 ms apart, in 3 of 5 tries) and `failing-sends-wait-for-the-next-tick-test`.
- `frame-profiler-test`: `idn-samples-keep-the-last-1000-test`, which passes on the old profiler too (it pins what the ring buffer must keep), and NEW `recording-an-idn-sample-allocates-only-the-sample-test` (under 512 bytes per call).
- `fake-dac-test/violations-find-each-broken-rule-test`: one stream per rule, the 16- and 32-bit wraps, `:skip`, and a real engine's stream.
- The fixtures of `engine-wire-test` and `output-lifecycle-test` check every stream; `breaks!` names a rule a test breaks on purpose. `host-edit-restarts-the-engine-test` checks its second fake DAC too, and `show-clock-test` and `streaming-engine-test` check theirs.
- `output-lifecycle-test`: `^:timing every-projector-sends-every-tick-once-test` (c1). In the slow-projector tests, `projector-frame` takes the snapshot.
- `frame-service-test`: the preview tests run on `preview-data`, with cues rendered by `generate-frames-by-zone` (the BUG-2026-02-06 cue-destination tests are kept), and NEW `preview->mirror-test`. Gone: `test-generate-frames-by-zone-cached`, and `generate-current-frame-zone-filter-integration-test`, which only checked the shape of `generate-current-frame`'s result.
- UI: `streaming-status-test` passes unchanged (6 tests).
- Red first: against the old engine, which ignores the signal, the new engine tests had 5 failures and 1 error in 16 tests. It sent every tick again on its own 16.7 ms deadline, the frames of two ticks published back to back went out 16.5–17.1 ms apart, and it had no `:tick-to-send-us`. The old profiler allocated 6,008 bytes per sample. With the keepalive counted from the last send, 22,826 sends were tried in 300 ms. The wire and lifecycle tests, with the checker in their fixtures, passed against the old engine (35 tests, 271 assertions): it already kept every rule, so the checker guards the new loop.

**Probes** (before = `e52e865`, after = `dd106de`, load average 3–5; c4–c6 and p11 are in 01 §10)

| Probe | Before | After |
|---|---|---|
| c1 | 120 frames in 2 s show **61 distinct** cue positions; 59 frames repeat the one before | 120 frames, **120 distinct ticks**, 0 repeats |
| c2 | 30.5 / 90 / 99.5 / 93 renders a second with 0 / 1 / 2 / 4 projectors: the preview's 30–30.5 and the engines' 0 / 60 / 69 / 62.5. §1's run at `f7fdd79` had 138 with 4 projectors: the cache's hit rate varies from run to run | **60 / 60 / 60.5 / 60.5**, all on the show clock; the engines render nothing |
| c3 | median 26 and 175 µs (p95 67 and 380) | 40 and 155 µs (p95 98 and 242). The render is unchanged; the run's load differs |
| c7 | 1 and 16 contexts per render: 4 % and 5 % of it | 1 and 16: 5 % and 5 %. Unchanged: Phase 4 is the one that changes it |

With no projector the clock still renders every tick, 60 a second where the preview rendered 30.5, because the preview frame comes from the tick.

**Verification** (at `dd106de`; the full suites are in 01 §10)

| Check | Result |
|---|---|
| `clj -M:mac-arm:test show-clock multi-engine streaming-engine output-lifecycle frame-service frame-profiler fake-dac engine-wire` (§8) | 109 tests, 570 assertions, 0 failures |
| `clj -M:mac-arm:test-timing engine-wire output-lifecycle show-clock` | 5 tests, 25 assertions, 0 failures |
| `clj -M:test-ui:mac-arm:test-ui-headless -n laser-show.ui-test.streaming-status-test` (with `timeline-editor-test`) | 29 tests, 134 assertions, 0 failures |

Not testable here: a real DAC (none available). The hardware check (output runtime `05-phase5-frame-timing.md` §8) covers what this phase relies on from the DAC: that it draws the newest frame rather than queueing (A1), and, through the acknowledge replies, that it accepts the timestamps and sequence numbers.
