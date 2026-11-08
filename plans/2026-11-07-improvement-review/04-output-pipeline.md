# 04 — Output Pipeline (IDN / backend / routing) Improvement Plan

**Date:** 2026-11-07
**Scope:** `src/laser_show/idn/**`, `src/laser_show/backend/**`, `src/laser_show/routing/**`, `src/laser_show/services/**`, `src/laser_show/profiling/**`, `java/**`, `docs/ILDA-IDN-Stream-rev002.pdf`, `docs/IDN-Hello-2022-03-27-draft.pdf`, `plans/zone routing arch/*`
**Status:** Review only. No source changes were made.

Spec citations below come from `pdftotext -layout` of the two PDFs in `docs/`. "Stream §x" means ILDA IDN-Stream rev 002 and "Hello §x" means the IDN-Hello 2022-03-27 draft. Claims marked *(verified)* were checked in a plain Clojure 1.12 REPL.

---

## 1. Summary

The pipeline is laid out clearly: `idn/stream` (encoding), `idn/fragmentation`, `idn/hello` (transport and discovery), `backend/streaming-engine` (one thread per projector), `backend/multi-engine` (lifecycle and zone→projector routing), and `services/frame-service` (rendering). It also has good unit coverage of header bytes.

The main problems are protocol edge cases that the tests don't exercise:

- Any IDN message over 32,767 bytes throws. Fragmentation is off by default, so large frames are silently dropped.
- 8-bit XY is encoded unsigned, but the spec says signed.
- Sequel fragments carry an extra chunk header.
- Timestamps use the wall clock.

Timing and threading are fragile:

- Cue timing only advances on the 30 Hz preview timer, but streaming runs at 60 Hz.
- Pacing uses `Thread/sleep` on wall-clock milliseconds.
- Start, stop and refresh run in unsynchronised futures that can orphan engines.

The hot path does avoidable allocation and work on every frame for every projector:

- Exceptions used as control flow.
- Eager `format` calls inside routing debug logs.
- A `take-last 1000` copy in the profiler.
- A per-packet DNS lookup and two byte-array copies per packet.
- Per-item track indexing.

Network errors never reach the UI. `:connected?` is set optimistically, and a failing `send` logs at ERROR 60 times per second per projector.

The `plans/zone routing arch/01-backend-zone-routing-engine.md` work is largely implemented: recursive partitioning, folder inheritance, multi-entry cache, per-cue timing, and no double global effects. This plan builds on it and does not repeat it.

---

## 2. Findings

### Protocol correctness

1. **Packets > 32,767 bytes throw, so large frames never reach the DAC (High).**
   - `stream.clj:240` and `fragmentation.clj:183` call `(.putShort buf (short total-size))`. Clojure's checked `short` throws `IllegalArgumentException: Value out of range for short: 40000` *(verified)*.
   - Fragmentation is **disabled by default** (`streaming_engine.clj:78`, `fragmentation-enabled? false #_true`), so every frame goes through `frame->packet[-with-config]`.
   - This throws once a frame has more than about 3,270 points at 16/16 bit (10 B/sample) or about 4,090 points at the 16/8 default (8 B/sample).
   - The exception is caught at `streaming_engine.clj:247-248`, so the projector shows nothing and the log fills with errors.
   - `MAX_POINTS_PER_PACKET 9000` (`stream.clj:82`) is also wrong on its own terms. Stream §2.1 says Total Size "SHALL be in range 0x0008 to 0xFF00", but 9000 × 10 B is about 90 KB, and `MAX_PACKET_SIZE` (`stream.clj:204-209`) is 90,028.
   - The cap should be computed per config from 0xFF00.

2. **8-bit XY is encoded unsigned, but IDN defines it as signed (Med, latent).**
   - `output_config.clj:106-110` maps -1→0, 0→128, 1→255.
   - Stream §3.4.7: X/Y "octet values are interpreted as left aligned signed numbers in range [-128 .. 127]". So centre (128 = 0x80) decodes as −128, the far left, and the whole image wraps.
   - `output_config_test.clj:18-32` locks in the wrong mapping.
   - Exposure today is limited: new projectors default to 16-bit XY (`events/handlers/projector.clj:101-102`). But `compact-config` and `tags-8bit-xy-*` exist and any persisted `:xy-bit-depth 8` reaches this code.

3. **Sequel fragments write a new 4-byte chunk header (Med; High if fragmentation is turned on).**
   - `fragmentation.clj:281` calls `write-frame-chunk-header!` in every sequel fragment.
   - Stream §6 says: "In case of fragmentation, the chunks can be split and spread across data sections of multiple messages." The chunk header (flags and duration) belongs once, at the start of the chunk. A conforming receiver will read the 4 sequel header bytes as sample data, which shifts every later sample by 4 bytes.
   - The rationale for `MIN_POINTS_PER_FRAGMENT` (`fragmentation.clj:40-42`, "spec requires at least 2 for start/end") applies to the whole frame, not to each fragment.
   - `MAX_FRAGMENTS_PER_FRAME` is only applied in `fragment-count` (`:122`). `split-frame-into-fragments` (`:148-159`) ignores it.
   - Confirm the layout against a reference receiver (e.g. IDN-Toolbox / `idn-tools` linked in README) before changing it.

4. **The default 1,400-byte MTU goes against the spec's advice (Low).**
   - `fragmentation.clj:32-34` and `streaming_engine.clj:45` default to 1,400 bytes.
   - Stream §6.2 says the producer "SHOULD use transport/medium layer fragmentation rather than (IDN) application layer fragmentation... use maximum length IDN messages and rely on IP fragmentation".
   - Application fragmentation is really only needed above 0xFF00.

5. **Timestamps use the wall clock at millisecond resolution (Med).**
   - `streaming_engine.clj:106-111` and `:270` use `(* (System/currentTimeMillis) 1000)`.
   - Stream §2.1 requires timestamps that "increase monotonically". Hello §6 says "timestamps SHALL match the point in time of sending".
   - An NTP step or manual clock change sends timestamps backwards or makes them jump.
   - Millisecond resolution adds up to 1 ms of quantisation jitter, which the consumer may use for clock sync.
   - `stream.clj:514-517` (`microseconds-now`) returns a `clojure.lang.Ratio` *(verified)* and would make `bit-and` throw if anyone used it. Nothing calls it today.

6. **The close packet breaks sequence numbering and never sends IDN-RT close (Med).**
   - `streaming_engine.clj:303` calls `hello/wrap-channel-message` **without `:sequence`**, so it takes the global `sequence-counter` (`hello.clj:222,326`) instead of the engine's counter.
   - Hello §6 says sequence numbers "must then be counted up". Hello §1 (status flags) treats non-monotonic or duplicate numbers as errors, so the close can be discarded.
   - No `CMD_RT_CLOSE` (0x44) is sent either, so the DAC session is only released by the 1 s link timeout (Hello §7.2).
   - There are now two sequence systems: the global counter and per-DAC counters (`hello.clj:286-305`) used by the unused DAC registry.

7. **Frame duration is tied to FPS, not point count, and `:scan-rate` is ignored (Med-High; affects scanner safety and quality).**
   - `streaming_engine.clj:144` always sends `duration = 1e6/fps` (16,666 µs).
   - Stream §6.2 defines Duration as how long the frame takes to draw. A 4,000-point frame in 16.6 ms asks for about 240 kpps, and a 50-point frame asks for 3 kpps.
   - Projectors have `:scan-rate` (default 30000; `projector.clj:89,100`, `domains.clj:257`), but `multi_engine.clj:232-251` never passes it to the engine.

8. **Frames aren't checked against frame-sample rules, and NaN kills the whole frame (Med).**
   - Stream §6.2: "the first sample ... SHALL be invisible" and a non-empty array "SHALL contain at least 2 samples". Nothing in `multi_engine`, `concatenate-frames` (`frame_service.clj:317-341`) or `stream.clj` enforces either.
   - `u/clamp` (`common/util.clj:22-27`) passes NaN through, and `(short NaN)` throws *(verified)* in `output_config.clj:108`. One bad point from an effect therefore throws away the whole packet.
   - Root cause is filed in `03-animation-effects.md` finding 7. This finding covers the output-side consequence and the last line of defence.

9. **IDN-Hello parsing keeps NUL padding and discovery ignores `timeout-ms` (Low-Med).**
   - `hello.clj:380` and `:412` use `clojure.string/trim`, which does **not** strip `\u0000` (`"AB\0\0"` → `"AB  "` *(verified)*).
   - As a result, host and service names keep NUL padding, `str/blank?` in `events/core.clj:169` never sees an empty name as blank, and the generated `dac-id` keywords (`hello.clj:651,802-815`) contain NULs.
   - `discover-devices` (`hello.clj:608-630`) ignores `timeout-ms` and loops until a 1 s receive gap. Continuous responders keep it waiting indefinitely, and it never de-duplicates by unit-id.
   - `parse-scan-response` doesn't check `struct-size`, even though Hello §4.1.2 says it "SHALL be checked upon reception".

10. **Several projectors on one DAC open several IDN-RT connections (Med).**
    - Each engine creates its own `DatagramSocket` on an ephemeral port (`streaming_engine.clj:267`).
    - Hello §7.1: "IDN-RT is connection based and therefore stores the address tuple. A second client port will therefore open a second connection to a second IDN session."
    - So a multi-head DAC with N services gets N sessions and may report OCPD (occupied, `hello.clj:377`).
    - `channel-id` is set to `service-id` (`multi_engine.clj:249-250`) and masked to 6 bits (`stream.clj:239`), so service 64 collides with channel 0.

### Timing and threading

11. **Cue and global timing only advance in the 30 Hz preview timer (High).**
    - `update-timing-accumulators!` (`frame_service.clj:79-122`) is only called from `update-preview-frame!` (`:740`), which runs on a `java.util.Timer` at 30 FPS (`app.clj:136`, `frame_service.clj:776-795`).
    - Streaming runs at 60 FPS and `get-cue-timing-context` (`cue_timing.clj:169-191`) doesn't extrapolate. Every beat-driven modulator, keyframe and timeline window therefore steps at 30 Hz on hardware (each value is sent twice), while `elapsed-ms`-driven effects step at 60 Hz.
    - If the preview timer stops or dies, streaming freezes in time. A `java.util.Timer` thread dies permanently on any `Error`, and only `Exception` is caught (`:790`).

12. **Start, stop and refresh can race and orphan engines (High).**
    - `events/core.clj:267-311` runs `start-engines!`, `stop-engines!` and `refresh-engines!` in separate `future`s with no serialisation.
    - Clicking Start then Stop quickly lets `stop-engines!` read an empty `:multi-engine-state` (`multi_engine.clj:303-304`). Then `start-engines!` writes the running engines (`:292-295`). The daemon threads keep streaming and nothing references them.
    - `refresh-engines!` can interleave with either.
    - There are two sources of truth for "running": `[:backend :streaming :running?]` (set optimistically in `connection.clj:40`, read by `streaming-running?` at `multi_engine.clj:386`) and `[:backend :streaming :multi-engine-state :running?]`.

13. **Live engine objects are stored in the cljfx context (Med; project convention).**
    - `multi_engine.clj:292-295,374-377` put engine maps, which contain atoms, `Thread`, `DatagramSocket` and a 90 KB `ByteBuffer`, into app state through `state/assoc-in-state!`.
    - This goes against CONTRIBUTING's "pure data vs Objects" and CLJFX_STATE_MANAGEMENT §"Keep High-Frequency Updates Separate".
    - It also puts these objects within reach of history, persistence and debug dumps (`state/core.clj:261` `save-state-debug!`).

14. **Frame pacing uses `Thread/sleep` on wall-clock milliseconds (Med).**
    - `streaming_engine.clj:204,250-253` sleeps `(long (- 16.67 elapsed))`. The truncation makes it always undershoot.
    - On Windows, the main dev platform (README), sleep granularity can be 1–15.6 ms, so 60 FPS jitters between about 32 and 64 Hz.
    - There's no deadline accumulation, so drift is never corrected.
    - Every engine thread has the same name, `"idn-streaming"` (`:278`), which makes JFR and thread dumps ambiguous.

15. **`refresh-engines!` only reacts to enabled/disabled changes (Med).**
    - `multi_engine.clj:370` keeps running engines unchanged, so edits to host, port, service-id, output-config or zone groups (zone groups are read live, the rest are not) don't apply until a full restart.
    - It also builds engines for *all* projectors just to diff the ids (`:340`), allocating a 90 KB buffer each.
    - Engines that fail `start!` are still added to the state map (`:361-371`).

### Network error handling

16. **Send failures are invisible to the user and flood the log (Med-High).**
    - `streaming_engine.clj:247-248` logs every exception at ERROR, every frame. Unplugging the cable gives about 60 log lines per second per projector, and that I/O happens on the real-time thread.
    - `multi_engine.clj:211,214` log only `.getMessage`, so the stack is lost.
    - Projector `:status {:connected? false}` (`projector.clj:103`) is never updated by the backend.
    - `[:backend :idn :connected?]` is set to `true` before any packet is sent (`connection.clj:42`).
    - IDN-RT acknowledge (0x41/0x47, Hello §6.3), which would give real link status and RTT, isn't used.

17. **There's a DNS lookup and a `DatagramPacket` allocation on every packet (Med, perf/latency).**
    - `hello.clj:77` calls `InetAddress/getByName host` on every `send-packet`. A projector configured by hostname (`*.local`) can block the real-time thread on a resolver call.
    - Resolve the address once when the engine starts and re-resolve on failure.

### Hot-path performance

18. **Two full byte copies per packet, plus lazy seqs (Med).**
    - Packets are built in the reusable buffer, copied to a new `byte-array` (`stream.clj:366-369,403-406`, `fragmentation.clj:244-247,285-288`), then copied again by `wrap-channel-message` (`hello.clj:328-331`).
    - `(take MAX_POINTS_PER_PACKET frame)` (`stream.clj:345,389`) makes a lazy seq and `doseq` walks it.
    - The truncation warning (`:352-353`) can never fire because `point-count` is counted *after* `take`.
    - `send-ping`, `send-rt-*` use `(byte-array (concat header payload))` (`hello.clj:119,164,183,202`), which boxes every byte. These aren't in the streaming path today.

19. **`write-point!` recomputes the config for every point (Low-Med).**
    - `stream.clj:288-315` destructures the record, calls `bytes-per-xy`, `bytes-per-color` and `bytes-per-sample`, and loops `dotimes` for padding on every point.
    - `normalized->output-*` (`output_config.clj:104-121`) use the boxed, 4-arity `u/clamp`. The project already has `clamp-double` (`util.clj:17` docstring).
    - Colour conversion truncates (`int`) instead of rounding, so 0.999 → 254 at 8-bit.

20. **Routing debug strings are built on every item, every frame (Med, perf).**
    - `log-once` is a function (`zone_effects.clj:32-40`), so its `format` argument is evaluated before the debug check. This happens at `zone_effects.clj:140-142` (per item, per projector, per frame, plus a vector key), `:157-160`, `:171-173`, and `frame_service.clj:502-506`, which also formats a `%.2f`.
    - With debugging off, all that work is thrown away.

21. **Track tree re-indexed for every item, every frame (Low-Med).**
    - `resolve-track-effective-zone` calls `index-tracks` (`zone_effects.clj:65-87,104`), which builds two transient maps over the whole track tree, once for every item that has a `:track-id`, every frame, for every projector.
    - `extract-zone-effects` uses lazy `filter` (`:56`), which breaks the eager hot-path rule in `00-zone-routing-architecture-overview.md` §5.
    - The `(string? tracks)` argument-swap branch (`:97-98`) is an undocumented compatibility shim.

22. **Exceptions used as per-frame control flow (Med, perf).**
    - `multi_engine.clj:157,161` throw `ex-info` ("Not playing", "No active cues") on every idle frame for every projector, which means about 60 stack-trace captures per second per projector when idle.

23. **Repeated state reads and duplicate rendering (Med, perf and consistency).**
    - In one frame, `state/get-raw-state` is read at `multi_engine.clj:154`, again in `frame_service.clj:510`, again for every zone inside `get-active-global-effects` (`:224`, called from `:469` per zone), and again in `apply-projector-effects` (`multi_engine.clj:107`). These can be different snapshots.
    - `get-active-global-effects` rebuilds the effects vector for every zone.
    - The cache (`frame_service.clj:537-573`) only helps by chance. Each projector thread computes `elapsed` from its own clock, so threads that straddle a 16 ms bucket miss. With two projectors the hit rate is about 50%.
    - Two threads that miss together both compute. The key is a 32-bit `hash`, so a collision would serve another chain's frames.
    - The preview (`generate-current-frame`, `:627`) renders everything again *uncached* at 30 Hz.

24. **The profiler allocates heavily and only covers preview (Med).**
    - `frame_profiler.clj:45,61` run `(vec (take-last 1000 (conj s sample)))` on every record, a roughly 1,000-element copy per call, and `record-idn-streaming!` runs per frame per projector.
    - IDN samples from all projectors are mixed into one window.
    - `record-frame-timing!` and `jfr/emit-frame-event!` are only called from the preview path (`frame_service.clj:670-680`), so time spent generating hardware frames is never measured. Also `effects-time-us` is hard-coded to 0 (`:665`).
    - `emit-idn-streaming-event!` and `emit-effect-chain-event!` (`jfr_profiler.clj:488-537`) have no callers, so `IdnStreamingEvent.java` and `EffectChainEvent.java` are dead.
    - JFR events go through reflective `Method.invoke` and `to-array` boxing on every frame (`jfr_profiler.clj:478-486`), with no `isEnabled`/`shouldCommit` check.
    - `dev/user.clj:273,285` resolve `async-profiler/profile-alloc!` and `profile-section!`, which don't exist, so `(profile-alloc 30)` from `docs/PROFILING.md` calls `nil` and throws an NPE.

### Structure, duplication and docs

25. **Duplicated or dead code (Low).**
    - `multi_engine/get-active-cues-data` (`:118-136`) duplicates `frame_service/get-all-active-cues-data` (`:147-163`).
    - `combine-zone-frames` (`multi_engine.clj:82-95`) duplicates `combine-all-zone-frames` (`frame_service.clj:575-585`).
    - The deprecated `get-active-cell-data` and `get-timing-context` (`frame_service.clj:132-145,197-216`) are kept only for tests.
    - `ze/group-items-by-zone` (`zone_effects.clj:145-174`) is only used in tests.
    - The DAC registry (`hello.clj:228-305,528-594,634-676`) is only used in tests.
    - The `concatenate-frames` `_elapsed-ms` parameter is unused.

26. **Behaviour doesn't match the docstrings (Low-Med).**
    - `frame_service.clj:22-25` promises that "the calibrating projector receives a boundary box test pattern". No output code reads `[:projector-ui :calibrating-projector-id]`, and the provider throws "Not playing" when stopped, so calibration while stopped outputs nothing.
    - Default bit depths disagree:
      - `output_config.clj:11,35-38`: 16/16 by default.
      - `streaming_engine.clj:13,62`: says 8/16.
      - `multi_engine.clj:241-243`: falls back to 8/16 only when `:output-config` is present, otherwise 16/16.
    - The debug-log guard `(= service-id (or service-id 0))` (`streaming_engine.clj:224`) is always true.
    - README line 46 links to `src/laser_show/backend/idn_stream.clj`, which doesn't exist. The tests for `laser-show.idn.stream` live in `test/laser_show/backend/idn_stream_test.clj`.

27. **Preview zone-frames are joined without blanking (Low, preview only).**
    - `frame_service.clj:654-656` uses `(merge-with into ...)` across cues, so a zone-filtered preview cell draws a lit line between shapes from different cues. Use `concatenate-frames` there as well.

### Test gaps

28. **No tests for the failures above (Med).**
    - No test sends a frame larger than 32 KB, or one near 0xFF00, through `frame->packet`.
    - No test decodes a fragmented frame back into samples (round trip).
    - No signed 8-bit XY test.
    - No timestamp monotonicity or sequence continuity test across frame, fragments and close.
    - No streaming-loop test with a local UDP receiver (`streaming_engine_test.clj` has 4 construction-only tests).
    - No lifecycle race test.
    - No multi-projector cache-sharing test under concurrency.
    - `test/integration/idn_hello_test.clj` only tests the unused DAC registry.

---

## 3. Proposed Improvements

### Quick wins (each S effort)

**QW1 — Fix packet size encoding and limits** (findings 1, 18)
- Use `(unchecked-short total-size)` in `stream.clj:240` and `fragmentation.clj:183`.
- Replace `MAX_POINTS_PER_PACKET` with `(max-points-per-message output-config include-config?)`, derived from `0xFF00`. Size `MAX_PACKET_SIZE` to `0xFF00 + 4`.
- Use `subvec` instead of `take`, and count before truncating so the warning can fire (rate-limit it).
- Have `parse-channel-message-header` read Total Size unsigned (`bit-and 0xFFFF`).
- Files: `idn/stream.clj`, `idn/fragmentation.clj`, `test/laser_show/backend/idn_stream_test.clj`.
- Risk: low.

**QW2 — Signed 8-bit XY** (finding 2)
- Map `-1..1 → -127..127` and write with `unchecked-byte`. Update `output_config_test.clj:18-32` to match the spec.
- Files: `idn/output_config.clj`, test.
- Risk: low. Only affects 8-bit XY users, who currently get broken output anyway.

**QW3 — Monotonic µs timestamps** (finding 5)
- Store `start-nanos` and compute `timestamp-us = (quot (- (System/nanoTime) start-nanos) 1000)`.
- Delete or fix `microseconds-now` (use `quot`).
- Files: `backend/streaming_engine.clj`, `idn/stream.clj`.
- Risk: low.

**QW4 — Correct shutdown packet** (finding 6)
- Send the close using the engine's own `get-next-sequence!`, then send `CMD_RT_CLOSE` with the next sequence number.
- Remove the global `sequence-counter` default from `wrap-channel-message` by making `:sequence` required.
- Files: `backend/streaming_engine.clj`, `idn/hello.clj`.
- Risk: low.

**QW5 — Make `log-once` a macro and remove idle exceptions** (findings 20, 22)
- Rewrite `log-once` as a macro that checks `@debug-enabled?` before evaluating the message.
- Replace the `throw`-for-skip in `create-projector-frame-provider` with `cond`/early `nil`.
- Files: `routing/zone_effects.clj`, `services/frame_service.clj`, `backend/multi_engine.clj`.
- Risk: very low.

**QW6 — Rate-limited error logging with stack traces** (finding 16, part)
- Add a per-engine "last error class + count" and log the first occurrence plus a summary every N seconds.
- Use `(log/error e ...)` instead of `.getMessage`.
- Files: `backend/streaming_engine.clj`, `backend/multi_engine.clj`.
- Risk: low.

**QW7 — Resolve the address once and reuse the packet object** (finding 17)
- Resolve `InetAddress` in `start!`, store it on the engine, and add a `send-packet` arity that takes an `InetAddress`.
- Files: `idn/hello.clj`, `backend/streaming_engine.clj`.
- Risk: low.

**QW8 — Strip NUL padding and honour the discovery timeout** (finding 9)
- Trim NUL bytes, e.g. `(str/replace s #"[\x00\s]+$" "")`, or cut the string at the first 0 byte.
- Check `struct-size` (≥ 40).
- Bound `discover-devices` by an absolute deadline equal to `timeout-ms` and de-duplicate by `:unit-id`.
- Files: `idn/hello.clj`, `test/laser_show/idn/hello_test.clj`.
- Risk: low.

**QW9 — Profiling fixes** (finding 24, part)
- Replace `take-last` with `(let [v (conj s x)] (if (> (count v) 1000) (subvec v (- (count v) 1000)) v))`, or with a primitive ring buffer.
- Implement `profile-alloc!` and `profile-section!` in `async_profiler.clj`, or remove them from `dev/user.clj` and `docs/PROFILING.md`.
- Give each engine a thread name: `"idn-" projector-id`.
- Files: `profiling/frame_profiler.clj`, `profiling/async_profiler.clj`, `dev/user.clj`, `backend/streaming_engine.clj`.
- Risk: very low.

**QW10 — Fix docs and defaults** (findings 25, 26)
- Pick one default (`standard-config`, 8/16, which matches what new projectors get) and use it in `output_config`, `streaming_engine` and `multi_engine`.
- Fix the README path and remove the always-true log guard.
- Move `idn_stream_test.clj` to `test/laser_show/idn/`.
- Risk: very low.

### Medium refactors (each M effort)

**MR1 — Fragment layout that follows the spec** (findings 3, 4)
1. Get a reference capture first (IDN-Toolbox receiver, or the Wireshark IDN dissector if available) to confirm the sequel layout.
2. Treat the chunk as one byte stream, `[flags|duration][samples...]`. The first fragment carries the header; sequels carry raw continuation bytes split on sample boundaries.
3. Enforce `MAX_FRAGMENTS_PER_FRAME` in `split-frame-into-fragments`.
4. Change the default threshold: fragment only above the 0xFF00 limit, following Stream §6.2. Keep `:target-mtu` as an opt-in for networks that drop IP fragments.
5. Turn `fragmentation-enabled?` on by default, since it is now only a safety net.
6. Add a decoder test helper that reassembles fragments and checks `decode(encode(frame)) == quantise(frame)`.
- Files: `idn/fragmentation.clj`, `backend/streaming_engine.clj`, `test/laser_show/idn/fragmentation_test.clj`.
- Risk: medium. This changes the wire format, so verify on real hardware.

**MR2 — Duration from point count, plus an output sanitiser** (findings 7, 8)
1. Pass `:scan-rate` from the projector into the engine and compute `duration-us = (* 1e6 (/ (max 1 (dec n)) pps))`. Stream §6.2 says the first sample isn't timed. Optionally warn when a frame's points at the scan rate exceed the frame period.
2. Add one final `sanitize-frame` stage before encoding. It replaces non-finite values with a blanked point, prepends a blanked copy of the first point if that point is lit, duplicates a lone point to give 2 samples, and returns `[]` for empty input.
3. Coordinate with `03-animation-effects.md` MR5 so effect-side clamping and output-side safety aren't done twice. Proposed split: effects own range clamping and blanking out-of-range points; output owns the NaN→blank failsafe and the frame-structure rules.
- Files: `backend/streaming_engine.clj`, `backend/multi_engine.clj`, new `idn/frame_rules.clj` (or a section in `idn/stream.clj`), tests.
- Risk: medium. Changes in scan speed are visible on hardware, so ship it behind a per-projector toggle at first.

**MR3 — Real-time loop with deadline pacing** (finding 14)
- Keep `next-deadline-ns += interval-ns`, park with `LockSupport/parkNanos` until about 1 ms before the deadline, then spin or yield.
- If more than one interval behind, skip ahead instead of bursting.
- Record measured jitter (p95/max) into the profiler per engine.
- Files: `backend/streaming_engine.clj`.
- Risk: low-medium.

**MR4 — Serialised engine lifecycle, with runtime objects kept out of app state** (findings 12, 13, 15)
1. Add `defonce ^:private !runtime (atom {:engines {}})` in `multi_engine`, plus a single-thread `ExecutorService` (or a `locking` monitor) that runs every start, stop and refresh in order.
2. Keep only pure data in the cljfx context: `{:running? :engine-count :projector-status {id {...}}}`.
3. Remove `:multi-engine-state` from state, and keep one `:running?` flag.
4. Make `refresh-engines!` diff on a projector **config fingerprint**: `(select-keys p [:host :port :service-id :output-config :scan-rate :enabled?])`. Restart engines whose fingerprint changed, and keep only engines that started successfully.
- Files: `backend/multi_engine.clj`, `events/core.clj:267-311`, `events/handlers/connection.clj`, `events/handlers/projector.clj:195-287`, `test/laser_show/events/projector_handlers_test.clj`.
- Risk: medium. Cross-area with events/state.

**MR5 — Surface link health to the UI** (finding 16)
- Each engine keeps an `:health` atom: `{:last-ok-ms :last-error :consecutive-errors :packets-sent :actual-fps}`.
- A low-rate (2–4 Hz) publisher writes a `projector-status` snapshot into state (pure data).
- Optional: every 500 ms, send one packet as `CMD_RT_CHANNEL_MESSAGE_ACK` (0x41) and read the 0x47 ack without blocking, using the same socket with a short `SoTimeout` on a separate reader thread, to get real `connected?`/RTT and status flags (Hello §6.3).
- Stop setting `[:backend :idn :connected?] true` optimistically.
- Files: `backend/streaming_engine.clj`, `backend/multi_engine.clj`, `idn/hello.clj` (ack parser), `events/handlers/connection.clj`, plus the views/UI status bar and projector tab.
- Risk: medium.

**MR6 — Zero-copy packet path** (findings 18, 19)
- Reserve 4 bytes at the start of the engine buffer for the IDN-Hello header and write the header in place.
- Send with a reused `DatagramPacket` (`.setData buf-array 0 len`) or a connected `DatagramChannel` on a direct `ByteBuffer`. No `byte-array` copies.
- Precompute a per-config "writer" once per engine: `xy16?`, `color16?`, `pad` bytes, and tags.
- `write-point!` becomes a branch-free primitive function using `clamp-double` and `Math/round`.
- Keep the existing byte-array-returning functions as thin wrappers for tests and debugging.
- Files: `idn/stream.clj`, `idn/fragmentation.clj`, `idn/hello.clj`, `backend/streaming_engine.clj`.
- Risk: low-medium. Golden-byte tests already exist and protect the format.

**MR7 — Cheaper routing** (findings 21, 23)
- Memoise `index-tracks` on the identity of `tracks`: a one-slot `{:tracks t :index i}` atom, or compute it once per `generate-frames-by-zone` call and pass it down through `render-item-tree-by-zone`.
- Make `item-zone-effects` eager (`u/seek`).
- Remove the `string?` shim.
- In `generate-frames-by-zone`, read the global effect chain, global timing and input values **once** and pass them to `apply-global-effects-to-frame`.
- Pass `raw-state` into `apply-projector-effects`.
- Cache key: use the vector itself, not its `hash`, to rule out collisions, and include the cue cell (as `01-backend…` §3.3 specifies).
- Files: `routing/zone_effects.clj`, `services/frame_service.clj`, `backend/multi_engine.clj`, tests.
- Risk: low.

**MR8 — Real profiling of the streaming path** (finding 24)
- Record per-engine `{generate-us, encode-us, send-us, jitter-us}` into per-projector ring buffers.
- Call `emit-idn-streaming-event!` from the engine.
- Replace reflective invocation with a `MethodHandle` resolved once, gated on whether the event type is enabled.
- Files: `profiling/*.clj`, `java/laser_show/profiling/*.java`, `backend/streaming_engine.clj`.
- Risk: low.

### Larger efforts (each L effort)

**LE1 — One frame producer, many senders** (findings 11, 23, 14)
- Replace "each projector thread renders and hopes for a cache hit" with one **frame clock** thread at the output rate (60 Hz, or the highest projector FPS). Each tick it:
  1. advances cue and global timing accumulators. This moves `update-timing-accumulators!` out of the preview timer, and the accumulators can live in a separate high-frequency atom per CLJFX_STATE_MANAGEMENT §5, which is events/state's call.
  2. takes one state snapshot.
  3. renders zone frames once per active cue.
  4. publishes an immutable `{tick-ns, zone-frames, timing}` snapshot into an `AtomicReference`.
- Projector senders read the latest snapshot, combine zones, apply projector effects, encode and send. They never render.
- Preview samples the same snapshot at 30 Hz instead of re-rendering. This removes `generate-current-frame`'s duplicate pass and the 16 ms-bucket cache entirely.
- Fixes the 30 Hz timing stepping, the duplicated CPU work, inconsistent snapshots and cache races in one change.
- Files: `services/frame_service.clj` (split into `frame_clock.clj` + pure `render.clj`), `backend/multi_engine.clj`, `app.clj:136`, `dev/reload.clj:136`, the preview views, `animation/cue_timing.clj` call sites.
- Risk: medium-high. It touches timing semantics, so build it on the `03-animation-effects.md` MR1 timing fix and land it behind a flag with an A/B comparison test (same inputs → same frames).

**LE2 — One IDN-RT link per DAC** (finding 10)
- Introduce a `link` per `(host, port)`: one socket, one sequence counter, one sender thread. Projectors on that DAC become channels (`channel-id` 0..63 assigned by the link, not copied from `service-id`), each with its own `service-id` in its config header.
- Send each tick's channel messages back-to-back, so sequence numbers stay strictly +1 per connection.
- Files: `backend/streaming_engine.clj` (becomes a link engine), `backend/multi_engine.clj`, `idn/hello.clj`.
- Risk: medium. Needs a multi-head DAC to verify.

**LE3 — Calibration output mode** (finding 26)
- When `[:projector-ui :calibrating-projector-id]` is set, that projector's sender outputs a boundary/test pattern, with its projector effects applied, whether or not playback is running. All other projectors carry on as normal.
- Fail closed (blank frame) if a calibration effect throws, following `03-animation-effects.md` MR4. Today `multi_engine.clj:110-115` falls back to the uncalibrated frame.
- Files: `backend/multi_engine.clj`, `services/frame_service.clj` (or a new `services/calibration.clj`), plus a UI toggle that already exists.
- Risk: medium.

**LE4 — Protocol test harness**
- An in-process `DatagramSocket` "fake DAC" that parses IDN-Hello + IDN-Stream: it reassembles fragments, decodes samples using the config tags, and checks sequence and timestamp monotonicity, Total Size ≤ 0xFF00, signed XY and the ≥2-sample rule.
- Use it for engine loop tests (start → N frames → stop → close plus RT close observed), lifecycle race tests (start/stop × 100 with no leftover threads named `idn-*`) and large-frame tests.
- Rewrite `test/integration/idn_hello_test.clj` against it.
- Files: new `test/laser_show/idn/fake_dac.clj` and new engine/multi-engine tests. Coordinate with testing/build on where it runs (unit vs integration alias).
- Risk: low.

---

## 4. Suggested Order of Work

1. **LE4 (harness skeleton) + QW1, QW2, QW3, QW4.** Byte-level correctness first, each with a failing test written beforehand. QW1 alone fixes "big frames show nothing".
2. **QW5, QW6, QW7, QW9, QW10.** Cheap fixes for the hot path and the log flood. No behaviour change.
3. **MR4 (serialised lifecycle, runtime state out of the context)**, then **MR5 (health/status)**. MR5 depends on MR4's pure-data status shape.
4. **MR1 (fragment layout)**, after a hardware or reference-receiver capture. Then switch fragmentation on as a safety net.
5. **MR2 (scan-rate duration + sanitiser)**, coordinated with animation/effects MR5.
6. **MR6, MR7, MR3, MR8.** Performance work, measured with MR8's per-engine metrics before and after.
7. **LE1 (single frame producer).** After animation/effects' timing fix (03 MR1) and once events/state agrees on where high-frequency timing lives.
8. **LE2 (one link per DAC)** and **LE3 (calibration mode)**, as hardware becomes available.

### Dependencies on other areas

- **events/state**
  - MR4 changes `events/core.clj:267-311` and `connection.clj`, and removes `:multi-engine-state` from the context.
  - LE1 needs agreement to move timing accumulators and `[:backend :streaming :current-frame]` (written at 30 Hz, `frame_service.clj:756-760`) out of the cljfx context, per CLJFX_STATE_MANAGEMENT §5.
- **animation/effects** (`03-animation-effects.md`)
  - Its MR1 timing-ctx fix changes what `frame_service.clj:621-627,473-477` and `multi_engine.clj:175,193` pass in. Land it before LE1.
  - Its MR4 (fail closed for calibration) and MR5 (NaN/clamp stage) overlap with this plan's MR2 and LE3. Agree on owners: effects own value clamping, output owns the NaN failsafe and frame-structure rules.
  - Its finding 12 (re-running global effects per zone) is handled here by MR7 and LE1.
- **views/UI**
  - MR5 needs status-bar and projector-tab rendering of `projector-status`.
  - MR2 needs a scan-rate field (the data already exists).
  - QW2 means 8-bit XY becomes safe to expose.
  - LE3 uses the existing calibration toggle.
- **input/triggers**
  - LE1 should snapshot `input-store/get-all-continuous-values` once per tick. Today it is read once per cue per projector (`multi_engine.clj:166`, `frame_service.clj:472,623`), which yields inconsistent values within a frame.
- **testing/build/tooling**
  - LE4 placement (unit vs `test/integration`), a CI alias for the fake-DAC tests, the `dev/user.clj` profiler wrappers (QW9), and keeping `clj -T:build compile-java` in step if MR8 changes the Java events.


---

## Carried over from old plans (2026-11-08 audit, see 07-old-plans-audit.md)
- **Zone precedence is specified two different ways** (from zone routing arch/01): §2 says item → parent group → ancestors → track → folder → chain; §3.1 says item → track → inherited (group) → default. The code follows §3.1 (`zone_effects.clj:135-139`). Decide which is intended and add a precedence test with data examples.
- **The frame cache key** uses `(:id cue-chain)` and leaves out `timing-ctx` (`frame_service.clj:63-69`). Revisit this together with the frame-clock work, which replaces the wall-clock 16 ms bucket.
- **Missing test:** the multi-cue concurrent cache test (old §5.5) was never written.
- **Zone resolution uses positional arguments** with an argument-swap hack (`zone_effects.clj:97-98`, `(string? tracks)`). Switch to a parameter map.
