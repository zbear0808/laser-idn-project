# Phase 1 — A fake DAC, and tests of the wire behaviour we keep

**Written:** 2026-11-16   **Written for commit:** `70e049e` (`master`)
**Status:** Proposed. O1 answered A (2026-11-16); handed off for implementation   **Last verified against:** `70e049e`
**Supersedes / Superseded by:** none   **Related findings:** review 04 LE4 and finding 28; this folder's `00-overview.md` §1 (probes)

Every later phase changes code that drives lasers. Today nothing checks what actually reaches the wire end to end: which engines send, what they send, and what arrives after Stop. This phase adds a loopback "fake DAC" for tests, and pins the wire behaviour that must survive every later phase. It changes no app code. About 1 day.

## 1. Current behaviour (file:line)

| What | Where | Today |
|---|---|---|
| Engine loopback tests | `test/laser_show/backend/streaming_engine_test.clj:10-26` (`receive-all`, `header`), `:28-51`, `:54-71` | One receiver socket per test, drained **after** the engine stopped. Two tests: close packets continue the engine's sequence; the address is resolved once. |
| Byte layout | `test/laser_show/backend/idn_stream_test.clj`, `test/laser_show/idn/fragmentation_test.clj`, `hello_test.clj`, `output_config_test.clj` | Encoders checked byte by byte, without a socket |
| Multi-engine | `test/laser_show/backend/multi_engine_test.clj:10-16` (state fixture), `:284-303` (idle provider) | Engine creation and the provider only. `start-engines!`, `stop-engines!` and `refresh-engines!` (`multi_engine.clj:271-378`) have no tests. |
| Lifecycle effects | `events/core.clj:339-383`, `connection.clj:30-76` | No tests |
| `app/stop!` streaming part | `app.clj:190-198` | No tests |
| Integration | `test/integration/idn_hello_test.clj:12-34` | Sends to 127.0.0.1 with no receiver and asserts nothing about what arrived |
| Decoding samples | – | No decoder exists, in `src/` or `test/`. The probes have a 16/8-only one (`probes/output_probes_test.clj`, `chunk-of`). |

## 2. Root cause

Tests were written per layer: bytes in `idn_stream_test`, one engine in `streaming_engine_test`. Nothing receives and decodes the stream as a DAC would, so the lifecycle and every multi-projector path have no tests at all.

## 3. Reuse / extend

| Existing thing (file:line) | How it's reused |
|---|---|
| `hello/parse-packet-header` (`hello.clj:346-357`) | Hello command and sequence. The fields are signed, so mask them as `streaming_engine_test.clj:22-26` does. |
| `stream/packet-info`, `parse-channel-config-header` (`stream.clj:483-508`), `CHUNK_TYPE_*` (`:19-27`) | Message header, CCLF, channel, chunk type, timestamp, SCWC, service and mode |
| `stream/get-tags-for-config` (`stream.clj:126-134`), the tag vectors (`:89-121`), `output-config/bytes-per-sample` (`output_config.clj:83-93`), `default-config`, `standard-config`, `compact-config` (`:35-48`) | Sample layout per config. The decoder walks the same tag vector the encoder writes. |
| `se/create-engine`, `start!`, `stop!`, `running?`, `get-stats` (`streaming_engine.clj:49-103,270-343`) | Engine-level tests with a fixed provider |
| `events/event-handler` (`events/core.clj:398-460`) | Runs effects synchronously in the calling thread, so lifecycle tests can drive `:idn/start-multi-streaming` and the effect futures without an app (as the menu tests do, `menu_file_handlers_test.clj:191`) |
| Support-namespace precedent `test/laser_show/input/fixtures.clj`; the runner loads only `…-test` namespaces (`test/laser_show/test_runner.clj:36-47`) | `fake-dac` is a plain namespace under `test/`, never selected as a test |
| `^:timing` tag, excluded from `:test` (`deps.edn:142-143`) | Rate assertions |
| Probes p1–p13 (this folder) | The decoder and the senders/frames queries are lifted from there |

## 4. Changes per namespace

- `laser-show.idn.fake-dac` (NEW, `test/laser_show/idn/fake_dac.clj`):
  - `start! []`: binds 127.0.0.1 on port 0 with a 4 MB receive buffer. A daemon thread named `fake-dac` keeps receiving, with a 50 ms socket timeout, and records `{:port source-port :ns arrival :bytes data}`. Returns `{:port … :received atom …}`.
  - `stop! [dac]`: closes the socket and joins the thread.
  - `messages [dac & {:keys [since-ns]}]`: parsed datagrams (shape in §7). Samples are decoded with the last config seen on that channel, so 16/8, 16/16 and 8/8 all work.
  - `frames [dac & opts]`: frame-sample messages only.
  - `senders [dac & opts]`: the set of source ports. Each engine has its own socket, so a source port identifies an engine.
  - `wait-for! [pred timeout-ms]`, `quiet? [dac ms]`: deadline waits instead of fixed sleeps.
- `laser-show.idn.fake-dac-test` (NEW): the decoder against the real encoder.
- `laser-show.backend.engine-wire-test` (NEW): one engine with a fixed provider, observed on the fake DAC.
- `laser-show.backend.output-lifecycle-test` (NEW): multi-engine started and stopped through `events/event-handler`, with a projector at `127.0.0.1:<fake DAC port>`. An `after` fixture stops every engine created during the test (with `with-redefs` on `se/start!`, as `tracking-engines` in the probes does), so a failing test can't leave a sender running.
- `test/laser_show/backend/streaming_engine_test.clj`: unchanged. Its two loopback tests stay; new wire tests go in `engine-wire-test`.

## 5. State placement

None. This is test code only.

## 6. Events and effects

None changed. The tests dispatch the existing `:idn/start-multi-streaming`, `:idn/stop-multi-streaming` (`connection.clj:30-56`) and `:projectors/toggle-enabled` (`projector.clj:284-294`).

## 7. Data examples and invariants

One decoded message (16/8, the first one after start):

```clojure
{:port 61234 :ns 81234567890 :cmd 0x40 :seq 1
 :channel-id 1 :has-config? true :service-id 1 :service-mode 2
 :chunk-type 0x02 :timestamp 0 :duration-us 16666 :once? false
 :samples [[-16383 0 0 0 0] [16383 0 255 255 255]]}
```

The channel close and the RT close decode as `{:cmd 0x40 :chunk-type 0x00 :close? true …}` and `{:cmd 0x44 :payload nil}`.

Quantization the decoder test pins (16/8). These are today's values, colour truncation included (review 04 finding 19):

| Input | Wire |
|---|---|
| x −1.0, 0.0, 0.5, 1.0 | −32767, 0, 16383, 32767 |
| colour 0.0, 0.5, 0.999, 1.0 | 0, 127, 254, 255 |

**Invariants these tests keep through every later phase:**
1. Each engine numbers its datagrams 1, 2, 3, … with no gaps, close packets included.
2. The first message on a channel carries config: CCLF set, the projector's service id, mode 0x02 and the tags of its output config. Config repeats at least once every 250 ms (the code sends it every 200 ms, `streaming_engine.clj:40,116-122`).
3. Decoded samples equal the quantized input, for 16/8, 16/16 and 8/8.
4. While nothing is playing, every frame message has 0 samples (`multi_engine.clj:156-159`, `streaming_engine.clj:219`).
5. `stop!` ends with a channel close (void chunk, close flag) then an RT close, and nothing arrives afterwards.
6. Two projectors with services 1 and 2 stream on channels 1 and 2 from two source ports.
7. `app/stop!` sends the close pair for every engine it knows about (`app.clj:190-198`).

## 8. Tests

- `laser-show.idn.fake-dac-test/decodes-what-stream-encodes-test`: all three configs, with and without config, and the close message.
- `laser-show.backend.engine-wire-test`:
  - `sequence-counts-up-including-close-test`
  - `first-message-carries-config-test`
  - `config-repeats-within-250ms-test`
  - `samples-match-quantized-input-test`
  - `idle-provider-sends-empty-frames-test`
  - `stop-sends-close-pair-then-silence-test`
- `laser-show.backend.output-lifecycle-test` (fixture `with-fake-dac`, plus the engine-tracking `after` fixture):
  - `start-streams-every-enabled-projector-test`
  - `stop-closes-every-engine-test`
  - `two-services-two-channels-test`
  - `app-stop-closes-engines-test`

The probes that show *broken* behaviour (p1–p13, `00-overview.md` §1) are not turned into tests here. Each later phase starts by writing its probe as a failing test with the new expected behaviour, then fixes the code.

Run: `clj -M:mac-arm:test fake-dac engine-wire output-lifecycle`, then the full unit suite.

## 9. Out of scope / alternatives considered

- **A fake DAC that answers IDN-Hello (scan, ping, ack).** Not needed until Phase 4 option O8-B; a receive-only DAC covers Phases 1–5.
- **Fragment reassembly.** Fragmentation is off (`streaming_engine.clj:79`); add it together with review 04 MR1.
- **Mocking the socket instead of using loopback.** Loopback tests the real send path, and the suite already opens loopback sockets (`streaming_engine_test.clj:30`).

---

## Decisions

> **DECISION O1 — Where do the fake-DAC tests run?** *(A, kept 2026-11-16)*
>
> | | What | For | Against |
> |---|---|---|---|
> | **A (recommended)** | In the normal unit suite (`clj -M:<platform>:test`), every wait bounded by a deadline. Only frame-rate and jitter assertions are tagged `^:timing`, which the default run excludes (`deps.edn:142-143`) | Runs on every change and in CI (`build-and-test.yml:147-148`); the suite already uses loopback sockets | Adds a few seconds to the unit suite |
> | B | In `:test-integration` (`test/integration`, CI step `build-and-test.yml:150-151`) | Keeps the unit suite socket-free | Easy to skip locally; the existing integration tests assert nothing on receipt, so the folder carries no habit of being run |
> | C | A new `:test-output` alias and CI step | Clear separation | One more alias to remember; nothing else needs it |
>
> **Your answer:** A (the recommendation, not changed when O2–O4 were answered on 2026-11-16)
