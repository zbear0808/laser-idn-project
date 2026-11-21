# Output drivers: phases D1–D5

**Status:** In progress. Step 0 through D3 and the live UI pass landed at `5a9a41c`. **D4 done** on `output-d4-discovery` (`8b6d3aa`, `0d673ea`), not merged. D5 is pending. **Last verified against:** `0d673ea` for D4; earlier phase sections cite the code at their own revisions. Re-check the current tree before starting a phase.
The contract is in `01-driver-contract.md` (referred to as `01` §n). Test commands follow the `:test` alias (`deps.edn`), e.g. `clj -M:mac-arm:test streaming-engine output-lifecycle`.

Every phase starts the same way: write the failing tests first, against the fake DAC (`test/laser_show/idn/fake_dac.clj`), the recording driver (D1) or the LSPS receiver (D2).

---

## Step 0 · The shared skeleton (no behaviour change)

**Goal:** a small base, on the integration branch `output-drivers`, that D1, D2's encoder and LSPS, and D3's loader branch from, so they can be built side by side. Its public API doesn't change after this step; later phases only add to it.

### Changes per namespace

- **`laser-show.output.driver`** (NEW; a separate namespace because `multi-engine`, `output-runtime`, the add-on loader and the app all need it):
  - `!drivers`, a private atom `{driver-id driver}`. A plain `def`, so a reload of the namespace starts it empty and the built-ins register again as their namespaces load. Add-ons load only at startup (OD-4);
  - `register! [driver]`: validates, then adds it. It throws an `ex-info` with `:problems` when the driver is invalid or its id is taken. Returns the driver;
  - `unregister! [id]` (for tests), `lookup [id]` (the driver or nil), `registered []` (every driver map);
  - `validate [driver]` → a vector of problem strings, empty when it is valid (`01` §1, §5.4): the required keys, `:api` 1, `:transport`, the functions, `:fields`;
  - `validate-addon-driver [driver]`: `validate`, plus "add-on driver ids must be qualified keywords" (OD-9);
  - `descriptors []` → `{id {:id :name :transport :fields :capabilities :discover? :addon}}`, pure data (`:addon` from the driver's `::addon`, else nil);
  - `field-defaults [fields]` → `{key default}` of the fields that have a `:default`;
  - `projector-driver-id [projector]` is `(:driver projector :idn)`;
  - `projector-config [projector]` → `[driver-id config]` (`01` §5.2). The `:idn` config is exactly what `multi-engine/create-engine-for-projector` builds (`multi_engine.clj:287-297`, which step 0 doesn't change), even before `:idn` is registered;
  - `with-loader [driver f]`: calls f with the driver's `::loader` as the thread's context classloader, restored afterwards; with no loader it just calls f.
  - No driver is registered yet: D1 adds the `register!` of `:idn`, D2 the one of `:lsps`.
- **`laser-show.state.domains`**: the `backend` defstate (`:450-496`) gains `:output {:default {:drivers {} :addons []}}` (`00` §5).
- **`laser-show.events.handlers.addons`** (NEW): `handle` with `:addons/loaded {:addons [...] :drivers {...}}`, which writes `[:backend :output :addons]` and `[:backend :output :drivers]`. Routed by adding `"addons"` to `events/handlers.clj:39-69`. D3 adds the rest.
- **`laser-show.app`**: `start!` dispatches `{:event/type :addons/loaded :addons [] :drivers (driver/descriptors)}` right after the dispatch fn is set (`:213-221`), before the startup scan (`:237`). `start-test!` (`:326-353`) dispatches the same after mounting, so UI tests see the built-ins. D3 calls the loader first.
- **`laser-show.subs`**: NEW `driver-descriptors [context]` (the map) and `driver-descriptor [context id]` (one).

### Tests

- **NEW `laser-show.output.driver-test`:** `validate-reports-missing-keys-test`, `validate-checks-field-specs-test`, `unqualified-addon-id-is-invalid-test`, `register-rejects-taken-id-test`, `descriptors-are-pure-data-test`, `projector-config-test` (the rows of `01` §5.2; the LSPS row with a test driver registered by a fixture), `missing-driver-throws-test`, `with-loader-sets-and-restores-context-classloader-test`.
- **NEW `laser-show.events.addons-handlers-test`:** `loaded-writes-descriptors-test`.

**Commit:** `feat(output): the output driver registry and its checks, the drivers' descriptors in app state (:addons/loaded); no driver yet, nothing changes`

---

## D1 · The driver seam (no behaviour change): done (3adc96f)

**Goal:** IDN runs as the `:idn` driver behind `01` §1. On the wire, nothing changes.

### Changes per namespace

- **`laser-show.output.driver`** (step 0): D1 adds the `register!` of `idn-driver/driver` when the namespace loads.
- **`laser-show.idn.driver`** (NEW). Holds the IDN code that moves out of `streaming_engine.clj`:
  - the constants `:29-45`;
  - the session state built in `create-engine` (`:123-145`) and `start!` (`:421-428`): socket, packet buffer, `:max-samples`, target and last address, `start-ns`, `last-config-ns`, sequence counter. As built, the session keeps the old engine's key names (`:target-host`, `:target-port`, `:channel-id`, `:service-id`, `:output-config`, …), so the moved functions are unchanged apart from taking the session, and a test's stand-in for `target-address!` still reads `:target-host`;
  - `current-timestamp-us` (`:151-155`), `should-resend-config?` (`:157-164`), `wire-duration-us` (`:170-183`), `create-idn-stream-packets` (`:185-216`), `get-next-sequence!` (`:218-222`), `target-address!` (`:224-230`), the IDN `send-frame!` (`:236-265`, now returning `{:transfers n :duration-us d}`), the stream log counter (`:232-234`);
  - `send-close-pair!` (`:452-470`) plus closing the socket (`:476-478`) become `:close!`;
  - resetting the target address on an error (`:388`) becomes `:on-error`;
  - the start log line's details (`:349-352`) become `:describe`;
  - the config/sequence reset (`:354-355`) moves into `:open`.
  - `:fields`: host, port and service, so that D2 can describe IDN too. The IDN UI keeps its own hand-built fields.
- **`laser-show.backend.streaming-engine`** (existing):
  - NEW `create-driver-engine [driver config frame-provider & opts]`. It builds the engine map below.
  - `create-engine` (`:72-145`) keeps its signature and its defaults. It becomes a thin wrapper that builds IDN's config and calls `create-driver-engine`, so the ~15 tests that call it don't change.
  - `streaming-loop` (`:310-401`) keeps all of its pacing. The changes inside it:
    - it calls the driver through `with-loader`;
    - it adds the `:ready?` branch (`01` §1.3);
    - it records `:last-attempt-ns`, and `:frames-skipped` in `initial-stats` (`:51-63`);
    - it fills in the default result (`01` §1.2).
  - `start!` (`:413-446`) only starts the thread (no socket any more). The thread calls `:open` first and retries it every NEW `REOPEN_MS` while it throws (C-7, `01` §1.1). IDN's `:open` never blocks, so its first frame goes out as soon as before.
  - `close!` (`:472-481`) takes the session (at most once, C-4) and, if there is one, calls `:close!` on an `output-close-<id>` daemon thread and joins it for up to `CLOSE_MS`. The private `send-frame!` stays, as `[engine frame session]`, so `slow-projector-does-not-delay-others-test`'s stand-in for it still works.
  - `thread-name` (`:407-411`) takes the driver id: `idn-streaming-<id>` stays for IDN.
- **`laser-show.backend.multi-engine`** (existing): `create-engine-for-projector` (`:275-308`) uses `driver/projector-config`, then `create-driver-engine`. It still returns nil for an IDN projector with a blank host (`test/laser_show/backend/multi_engine_test.clj:56-84`). For a driver that isn't installed it throws `01` §5.2's `ex-info`. The frame provider half of the namespace (`:99-269`) doesn't change; Z3 rewrites it (`00` §7).
- **`laser-show.backend.output-runtime`** (existing):
  - `fingerprint` and `wanted` (`:35-50`) follow `01` §5.5;
  - `start-engine!` (`:116-129`) turns that `ex-info` into the projector's `:failed` message, and keeps `"No host"` for nil;
  - `engine-status` (`:172-186`) adds C-5 right after the safety branch, through NEW `stuck?`, which reads `:opening-since-ns` and `:last-attempt-ns` from the stats itself, so the destructuring line Z3 changes is left alone.
- **`laser-show.animation.types`** (existing): the docstring at `:14-16` says the conversion happens "at the output driver".

The engine map after D1 (IDN shown):

```clojure
{:id :projector-10-0-0-5-1
 :driver-id :idn
 :driver {...}                                   ; the driver map from the registry
 :driver-config {:host "10.0.0.5" :port 7255 :channel-id 1 :service-id 1
                 :output-config #OutputConfig{:color-bit-depth 8 :xy-bit-depth 16}
                 :target-mtu 1400 :fragmentation-enabled? false}
 :session (atom nil)                             ; what :open returned, set by the streaming thread
 :frame-provider f :frame-signal show-time/frame-signal :fps 60 :scan-rate 25000
 :running? (atom false) :thread (atom nil) :stats (atom initial-stats)
 :result-warned? (atom false)}                   ; a bad :send-frame! result is logged once
```

### Tests

- **Unchanged, and they pass:** every test in `test/laser_show/backend/engine_wire_test.clj`, `streaming_engine_test.clj`, `output_lifecycle_test.clj`, `multi_engine_test.clj`, `test/laser_show/events/output_sync_test.clj` and `test/laser_show/idn/fake_dac_test.clj`, and the `^:timing` ones (`clj -M:mac-arm:test-timing output-lifecycle engine-wire streaming-engine`). Only these lines changed, because the IDN state moved into the session (the plan listed the first five; the code had more):
  - `engine_wire_test.clj:51-56` `port-of`: the socket is read from the session, after waiting for the streaming thread to open it (C-7);
  - `output_lifecycle_test.clj:310`, `:345` and `:501` (`#'se/target-address!` → `#'idn-driver/target-address!`, plus its require); `:312` stays, since the session keeps `:target-host`;
  - `:508`: `(nil? @(:session e))`, since `close!` clears the session as it did the socket;
  - `streaming_engine_test.clj:96`, `:103` (`(:target-host engine)` → `(get-in engine [:driver-config :host])`), `:219`, `:340` (the socket, through a NEW `local-port` helper that waits for the session);
  - `multi_engine_test.clj:54`, `:139-140`, `:151-152`, `:185` (`:target-host`, `:service-id`, `:channel-id` → under `:driver-config`);
  - `test/laser_show/ui_test/streaming_status_test.clj:41-45` (its stand-in for `target-address!`, as in the lifecycle test; whitespace only below the first line);
  - step 0's `output/driver_test.clj` `projector-config-test`: it checked `(nil? (driver/lookup :idn))`, which D1 makes false, so the IDN rows run with `lookup` stubbed to nil, and it compares with the engine's `:driver-id` and `:driver-config`.
  - The thread-name assertions at `:456-458` stay as they are.
- **NEW `test/laser_show/output/recording_driver.clj`** (a helper, not a `-test` namespace): `(recording-driver opts)` returns `{:driver … :calls (atom []) :frames (atom []) :threads (atom [])}`. It answers `:ready?` from a sequence or a fn, and can throw from or block in an open or a send (by index), or block in a close.
- **`laser-show.output.driver-test`** (step 0's): adds `idn-driver-is-valid-test` and `idn-driver-is-registered-test`.
- **`laser-show.backend.streaming-engine-test`** (existing):
  - `driver-gets-sanitized-frames-test` (C-1): the provider returns `[[0 0 1 1 1] [##NaN 0 1 1 1] [0.5 0.5 1 0 0]]`, and the driver receives `[[0 0 0.0 0.0 0.0] [0 0 1 1 1] [0.5 0.5 1 0 0]]`, on `output-streaming-rec`;
  - `driver-error-calls-on-error-then-blanks-test`: the calls are `[:send 2] [:on-error] [:send 0]`;
  - `device-paced-driver-waits-for-ready-test` (case B);
  - `device-paced-driver-skips-a-tick-test` (case C: `:frames-skipped` 1, nothing sent for that tick, not asked again before the next);
  - `driver-result-defaults-test` (`01` §1.2's three rows, the warning logged once);
  - `close-runs-once-test` (C-4: `stop!` twice and `close!` again give one `:close`, on `output-close-rec`);
  - `open-runs-on-the-streaming-thread-test` (C-7: `start!` returns while `:open` blocks 300 ms).
- **`laser-show.backend.output-lifecycle-test`** (existing):
  - `stuck-driver-shows-not-responding-test` (C-5: a send blocks 1.5 s, and the status says not responding while a second projector keeps streaming);
  - `blocking-close-does-not-hold-up-stop-test` (a close blocks 5 s, but `stop-all!` returns within `STOP_JOIN_MS + CLOSE_MS + 200 ms`, the close thread is left behind and the other engine's close still ran);
  - `missing-driver-reports-error-test` (`{:state :error :error "Output driver :acme/nova is not installed"}`, the projector kept as it is);
  - `driver-config-change-restarts-the-engine-test`, patterned on `host-edit-restarts-the-engine-test` (`:256`), through `:projectors/update-settings` with `:driver-config` until D2's own event;
  - `failing-open-is-reported-and-retried-test` (C-7: open throws twice, the status shows the error, then it streams);
  - `stop-does-not-wait-for-a-blocking-open-test` (C-7: open blocks 2 s; Stop returns within `STOP_JOIN_MS + CLOSE_MS + 200 ms`, the other projector stops normally, and the session that opens after Stop is closed and never sent to);
  - `stuck-open-shows-not-responding-test` (C-5 for an `:open` that takes 1.5 s).
  - Each new test was checked to fail with its behaviour taken out (no `:ready?` branch, no `stuck?`, an unbounded close join, no not-installed catch, `:open` on the caller's thread).
- **Performance** (2 IDN engines on loopback at 60 fps, 500-point frames, self-paced, the profiler's last 1,000 `record-idn-streaming!` samples per 10 s run, 6 runs each, before and after interleaved, on a shared machine at load 2–6): mean 459–640 µs per frame before (p50 344–551), 470–640 after (p50 364–707). The difference is inside the run-to-run noise. The seam alone (`with-loader` plus the result defaults, a no-op driver) costs about 60 ns a frame.
- **As built, beyond the text above:** a stopping engine skips `:on-error` and the blanking frame after a failed send, so a session is never used after `:close!` (C-3; the close follows anyway); an `:open` that returns nil counts as failed; `multi-engine/create-engine-for-projector` also throws the not-installed `ex-info` if `:idn` itself were unregistered; `stop-all!` closes engines one after another, so N stuck closes take N × `CLOSE_MS`.
- Suites at 3adc96f: targeted 122 tests, 724 assertions; full unit 1,333 tests; `streaming-status-test` 7 tests; kondo 0 errors (23 warnings, as before).

**Commit:** `refactor(output): the streaming engine sends through a driver; IDN is the built-in :idn driver, nothing changes on the wire`

---

## 1D · Contract additions from the first third-party add-on: done (8a5b4bf)

**Goal:** what the first real add-on needed, added to API 1 as optional keys (OD-13). A third-party DAC add-on (USB and network) was built in its own repo against this contract; its notes list gaps G1–G10. Built-in drivers (`:idn`, `:lsps`) don't change, and nothing changes for a driver that uses none of it. No view renders the new status data: wave 2 does the UI.

### Changes per namespace

- **`laser-show.backend.streaming-engine`:**
  - the engine map gains `:busy?`, `:driver-status` and `:status-warned?` atoms (`:132-137`), reset by `start!`;
  - NEW `report-error!` (`:244-251`) reports every driver error; `{:busy? true}` in its `ex-data` is reported as `busy-message` (`:235-242`), "In use by <holder>" or "In use by another program", and sets `:busy?` (G6). A good frame clears it;
  - `streaming-loop` (`:333-451`): a throwable with `:reopen?` (from `:send-frame!`, `:ready?` or the blanking send) while running ends the loop at once with no `:on-error` and no blanking frame, and the loop returns true (G1);
  - `open-session!` (`:489-531`) takes `wait-ms` (0 at start, `REOPEN_MS` after a reopen) and gives ctx `:cancelled?` (`(not @running?)`, G3); what an `:open` throws while the engine is stopping isn't reported;
  - NEW `drop-session!` (`:533-541`) and `run-engine` (`:543-552`): after a reopen request, close the session on `output-close-<id>` (once: `compare-and-set!`, C-4), clear `:last-attempt-ns`, open again;
  - NEW public `busy?`, `poll-driver-status!` (`:631-649`, G2) and `driver-status`.
- **`laser-show.backend.output-runtime`:** `publish-status!` (`:315-327`) first runs NEW `poll-driver-statuses!` (`:307-313`), before the dispatch check, so engines are polled even without a dispatch fn; `engine-status` (`:234-261`) adds `:busy?`, the driver's `:error` and `:warning` levels and `:driver-info` (`01` §1.5).
- **`laser-show.output.driver`:** `:status` joins the optional functions `validate` checks; NEW `sanitize-status` (`:260-276`), `cut-status-string` and `MAX_STATUS_STRING` 200, `MAX_STATUS_ENTRIES` 64, `MAX_STATUS_DEPTH` 4 (`01` §1.5).
- **`laser-show.output.api`:** NEW `log [level logger-name message]` and `[level logger-name throwable message]` (`:94-115`, G7): `clojure.tools.logging.impl/get-logger` on `*logger-factory*` with the add-on's name, `enabled?`, then `log*`. A plain fn, so add-ons need no macros and no tools.logging dependency; it follows the factory a `binding` puts in place (`with-log` in tests). An unknown level throws `IllegalArgumentException`.
- **`laser-show.output.sample-format`:** the `:lit?` source (`:27`, `:200`, G9).
- **`laser-show.output.addons`:** the `laser_show/` refusal says "add-on namespaces must not start with laser-show." (`bundles-problem`, `:208-213`, G8).
- **Docs:** `01` §1 (table), §1.1 (open, error, NEW reopen and status rows; driver-owned threads, G4; why no interrupt), §1.2 (a buffered DAC's `:duration-us`), §1.4 (C-3, C-7, NEW C-8 to C-12), NEW §1.5 (`:status`), §2, §4.1, §4.3, §5.3 (G10); `00` §3, §4 (OD-13), §5, §8, §10; this section and D4; `docs/OUTPUT_DRIVERS.md` §1.2, §1.4 (which still said `:open` ran on the lifecycle thread), §1.5, §1.7 (C-5, NEW C-7 to C-11), NEW §1.8–§1.11, §2, §4.

The new keys, with data:

```clojure
;; G1: from :send-frame! or :ready?
(throw (ex-info "Nova unplugged" {:reopen? true}))
;; calls: … [:send 2] [:close] (output-close-rec) … ~REOPEN_MS … [:open …] [:send 2] …

;; G6: from :open (or a send)
(throw (ex-info "Nova is in use by another controller" {:busy? true :holder "ShowCtl on 10.0.0.3"}))
;; status: {:state :error :error "In use by ShowCtl on 10.0.0.3" :busy? true}

;; G2/G5: :status
(fn [session] {:level :warning :message "Interlock open" :info {:scan-rate 30303 :buffer-ms 40}})
;; status: {:state :streaming :fps 59.8 :warning "Interlock open" :driver-info {:scan-rate 30303 :buffer-ms 40}}

;; G3: ctx
{:id :projector-nova :scan-rate 30000 :fps 60 :cancelled? #(...)}   ; (cancelled?) true once stopping

;; G7
(api/log :warn "acme.nova" "Nova at 10.0.0.9 is slow")
(api/log :error "acme.nova" e "Nova failed")

;; G9
{:fields [[:lit? :u8] …]}   ; 1 for a lit point, 0 for a dark one
```

### What changed from the add-on's proposals

| Gap | Proposed | Built |
|---|---|---|
| G1 | the error path first (report, `:on-error`, a blank attempt), then close and reopen | report, then close and reopen; no `:on-error` and no blank for a dead session (`00` §10). The first new `:open` waits `REOPEN_MS`, so a driver that asks on every first frame can't spin |
| G2 | `:details`, polled about every 500 ms | `:info` → `:driver-info`, every `STATUS_INTERVAL_MS` (250 ms), sanitized to plain EDN (strings cut, collections capped, NaN dropped); `:warning` only adds to a streaming status; the core never darkens on a driver's `:error` |
| G3 | ctx `:deadline-ns`, and Stop interrupts the streaming thread | ctx `:cancelled?`; no interrupts (`00` §10). The late session was already closed (D1); now also stated as C-10 |
| G4 | the contract statement | as proposed, in `01` §1.1 and the docs, C-11 |
| G5 | `:session-info`, read once after `:open`; the field clamps to it | folded into `:status`'s `:info` (`00` §10); the clamp is wave 2's |
| G6 | its own "In use" state | `{:state :error … :busy? true}`: an error, so the toolbar counts it, with a flag the UI can style. The cadence is C-7's |
| G7 | `(api/log! level name message & [throwable])` | `(api/log level name message)` and `(api/log level name throwable message)`, as `clojure.tools.logging`'s own arg order; `:trace` too |
| G8 | the docs | the docs, and the loader's message |
| G9 | `:lit?` | as proposed |
| G10 | `:broadcast-addresses` in the scan opts | built in D4 (`8b6d3aa`): first usable IPv4 broadcast on each up, non-loopback interface, deduplicated in interface order (`output/discovery.clj:29-57`) |

The add-on's "a driver may queue a frame more than once" note went into `01` §1.2 and the docs' §1.5.

### Tests

- **`test/laser_show/output/recording_driver.clj`:** `:fail-open?` and `:fail-send?` may return a map, the ex-info's `ex-data`; NEW `:on-open (fn [i config ctx])`, `:fail-ready?` and `:status`.
- **`laser-show.backend.streaming-engine-test`:**
  - `reopen-error-closes-and-reopens-test` (G1, from a send and from `:ready?`: the error reported, `:session` cleared, `[:close]` on `output-close-rec`, reopened 850 ms–2 s later, no `:on-error` or `[:send 0]`, two closes in all);
  - `error-without-reopen-keeps-the-session-test` (`{:reopen? false}`: `[:send 2] [:on-error] [:send 0] [:send 2]`, the identical session, one open);
  - `open-sees-cancelled-after-stop-test` (G3: false while running, true within 100 ms of Stop; Stop takes < 300 ms; nothing reported; and a session returned after Stop is closed and never sent to).
- **`laser-show.backend.output-lifecycle-test`:**
  - `driver-status-is-sanitized-into-the-status-test` (G2: not asked before the session opens, on `output-status` with the driver's loader, the plain-EDN result, about 4 calls a second, none after Stop);
  - `driver-status-error-and-warning-levels-test` (`:warning`, with a calibration warning, `:error` with `:info`, the default messages, `:ok` and an unknown level);
  - `throwing-status-is-ignored-test` (an `ex-info`, an `Error` and a string: the status unchanged, no engine error, logged once);
  - `busy-open-says-in-use-test` (G6: with a holder, without, a non-string and a blank one; retried and recovered; from a send; a later non-busy error drops `:busy?`);
  - `lsps-projector-streams-test` (deferred from D2: through `submit-sync!` to `lsps_receiver.clj`, the points as `01` §3 encodes them after `sanitize-frame`, 80 µs, on `output-streaming-bridge`, Stop sends one CLOSE, no L-1–L-3 violations).
- **`laser-show.output.addons-test`:**
  - `addon-driver-runs-on-the-streaming-thread-test` (deferred from D3: the sample fixture built by `jar_builder`, loaded by `load-all!`, run by `create-driver-engine`; its frames in the fixture's atom, sent on `output-streaming-addon-test` with the add-on's loader, its helper required there);
  - `laser-show-namespaces-are-refused-test` (G8: `laser_show/acme/nova/driver.clj` refused by `load-all!` and `install!` with the new message; `laser_show_test_addon/` loads).
- **`laser-show.output.api-test`:** `api-log-goes-through-tools-logging-test` (G7: logger name, level, throwable and message through `with-log`; a disabled level writes nothing; an unknown one throws; resolvable with `requiring-resolve`).
- **`laser-show.output.sample-format-test`:** `lit-source-test` (G9).
- **Existing lines that changed:** `streaming_engine_test.clj` `driver-gets-sanitized-frames-test` compares ctx without `:cancelled?` and checks it is a fn; `sample_format_test.clj` `invalid-layout-lists-problems-test` (the source list names `:lit?`) and `encode-allocates-nothing-per-point-test` (its layout has `:lit?`, still < 16 KB); `addons_test.clj` `bundled-clojure-is-refused-test` (the new message).
- **Suites at 8a5b4bf:** targeted (`streaming-engine output-lifecycle output.driver output.api sample-format lsps addons engine-wire multi-engine`) 173 tests, 13,466 assertions; full unit 1,392 tests, 476,824 assertions; `streaming-status-test` 7 tests, 29 assertions; kondo 0 errors (23 warnings, as before, none in the changed files). All green on the first full run; no timing failure seen.

**Commit:** `feat(output): contract additions to driver API 1 from the first third-party add-on …`

---

## D2 · Projectors with a driver, the point encoder, LSPS: done

**Goal:** you can add an LSPS projector by hand, edit its fields, stream to it, and save and reopen it. An unknown driver's projector survives a round trip.

### Changes per namespace

- **`laser-show.output.sample-format`** (NEW): `01` §2. It's new because the only scaling that exists is IDN's 8/16-bit signed scaling (`idn/output_config.clj:99-122`), and that one is tied to IDN's `OutputConfig`.
- **`laser-show.output.lsps`** (NEW): the `:lsps` driver (`01` §3). It's registered by `output.driver` next to `:idn`.
- **`laser-show.output.api`** (NEW): the facade for add-ons:
  - `api-version` (1);
  - `X Y R G B` and `blanked?` (from `animation/types.clj:58-63, 94-102`);
  - the `sample-format` functions;
  - `scan-duration-us` (`idn/stream.clj:570-578`).
- **`laser-show.output.driver`**: `coerce-field` and `summary [projector descriptors]` (for example "LSPS · 127.0.0.1:7266"; IDN keeps today's address line, `subs.clj:880`). `field-defaults` came with step 0.
- **`laser-show.state.domains`**: the projectors docstring (`:285-319`) documents `:driver`, `:driver-config` and the two new `:stable-id` forms (`01` §5.3). (`[:backend :output]` came with step 0.)
- **`laser-show.events.handlers.projector`**:
  - `make-projector-config` (`:77-105`) takes `:driver` and `:driver-config`. The host precondition (`:91`) and the IDN defaults apply only to `:idn`. Any other driver gets no `:host`, `:port` or `:output-config`, and a `:driver-config` with its field defaults filled in from `[:backend :output :drivers]`;
  - `handle-projectors-add-manual` (`:227-243`) passes them through, and gives a non-IDN projector `:stable-id [:manual driver projector-id]` (`01` §5.3), so Z2's matching never mistakes it for a host;
  - NEW `handle-projectors-update-driver-config` (`01` §5.4);
  - `handle-projectors-update-settings` (`:272-297`) drops `:driver` from its updates, and so does the new handler, so a projector's driver never changes after it is created (OD-11);
  - the `handle` case (`:662-716`) gains it.
- **`laser-show.events.show-lock`**: `blocked-events` (`:24-53`) gains `:projectors/update-driver-config`.
- **`laser-show.routing.rig`**: `identity-label` (`:181-192`) says "Added by hand: a scan doesn't look for it" for a `[:manual …]` id (and D4's `[:device …]` line, `01` §5.3).
- **`laser-show.subs`**: `rig-cards` (`:857-889`) reads `driver-descriptors` (step 0): a non-IDN card's address line (`:880`) is `driver/summary`, it is never "Not found in the last scan" while its driver has no `:discover` (`:886`), and it isn't grouped with other projectors by its (missing) host (`:872`, `:887`).
- **`laser-show.views.components.rig-strip`**: the card menu's Identify (`:287`) is on for any live projector, not only one with a host.
- **`laser-show.state.rig-file`** (ZR's, once it lands, `00` §7): `pin-map`'s new id for a taken non-IDN projector id is `:projector-<ms>`, as Add Manual makes, not `rig/unique-projector-id` from a nil host.
- **`laser-show.views.components.driver-fields`** (NEW): renders field specs with `commit-field` (`views/components/commit_field.clj:7`) for text and number fields, a combo box for `:choice` and a check box for `:boolean`. The ids are `driver-field-<key>`. Both the dialog and the settings panel use it, which is why it gets its own namespace.
- **`laser-show.views.dialogs.add-projector-manual`** (`:12-92`):
  - a **Type** combo (`add-projector-driver-combo`) listing every descriptor;
  - for IDN, today's fields (their ids unchanged); for any other driver, `driver-fields`;
  - Add is disabled until every required field coerces;
  - the text at `:28` becomes "Choose the output type and enter its connection details."
- **`laser-show.views.tabs.projectors`**:
  - `projector-basic-settings` (`:54-143`): the IDN branch is unchanged. Otherwise it shows the driver's name as a read-only label (there is no control to change it, OD-11) and `driver-fields` committing `:projectors/update-driver-config`;
  - with no installed driver, it shows "Output driver :acme/nova is not installed. Its settings are kept." and the `:driver-config` as read-only text.
- **`docs/OUTPUT_DRIVERS.md`** (NEW): the contract, LSPS and the add-on format. It points at `test-resources/addons/sample/` (D3) as a working example.

### Event examples

```clojure
{:event/type :projectors/add-manual :driver :lsps :name "Bridge"
 :driver-config {:host "127.0.0.1" :port 7266}}
;; => a new [:projectors :projector-<ms>] as in 01 §5.1 (max-packet-bytes defaulted to 1400), selected, dialog closed

{:event/type :projectors/update-driver-config :projector-id :projector-1763560000000 :key :port :value "99999"}
;; => {:state state}   (unchanged; 01 §5.4)
```

### Tests

- **`laser-show.output.sample-format-test`:**
  - `endpoints-test`, `rounds-half-away-from-zero-test`, `clamps-test`, `intensity-is-the-largest-channel-test`, `dark-point-encodes-zero-colour-test` (E-3);
  - `little-endian-frame-test` (`01` §2's 8-byte example);
  - `invalid-layout-lists-problems-test`;
  - `monotonic-property-test` (E-2: seeded, 2,000 cases, the pattern of `test/laser_show/idn/frame_rules_test.clj:24-57`);
  - `encode-allocates-nothing-per-point-test` (E-5, the pattern of `test/laser_show/vibes/rotation_test.clj:97-117`).
- **NEW `test/laser_show/output/lsps_receiver.clj`** (a helper): a loopback UDP receiver and decoder, built like `fake_dac.clj:23-49`.
- **`laser-show.output.lsps-test`:**
  - `three-point-frame-bytes-test` (`01` §3 example 1, byte for byte);
  - `large-frame-splits-test` (example 2);
  - `empty-frame-is-one-dark-packet-test` (example 3);
  - `stop-sends-close-test` (example 4);
  - `sequence-wraps-test`;
  - `duration-follows-scan-rate-test`;
  - `packets-never-exceed-max-size-test` (L-3, over 64 random frame sizes).
- **`laser-show.backend.output-lifecycle-test`:** `lsps-projector-streams-test`, through `submit-sync!`, received by the LSPS receiver (built in 1D, once D1 had merged).
- **`laser-show.events.projector-handlers-test`:**
  - `add-manual-lsps-projector-test`;
  - `add-manual-idn-unchanged-test` (today's shape exactly);
  - `update-driver-config-validates-test` (`01` §5.4's table);
  - `update-driver-config-is-undoable-test`;
  - `driver-never-changes-after-creation-test` (OD-11: `:projectors/update-settings` with `{:driver :lsps}` on an IDN projector, and `update-driver-config` with `:key :driver`, both leave `:driver` as it was);
  - `manual-driver-projector-has-its-own-stable-id-test` (two LSPS projectors added by hand, then a scan: neither moves, upgrades or gets a `[:host …]` id).
- **`laser-show.events.output-sync-test`:** `driver-config-edit-resyncs-test` and `idn-fingerprint-unchanged-test`.
- **`laser-show.events.show-lock-test`:** the existing `blocked-events-name-real-events-test` covers the new entry; NEW `driver-config-edit-is-refused-while-locked-test`.
- **`laser-show.events.projector-handlers-test`**: NEW `live-lsps-projector-asks-before-a-larger-zone-test` (Z1's question, through `01` §5.5's `wanted`).
- **`laser-show.subs-test`:** NEW `rig-cards-show-driver-summary-test` (the address line, never "not found", not grouped by a nil host).
- **`laser-show.state.persistent-test`:** `unknown-driver-projector-round-trips-test` (OD-10; once ZR lands, through the rig file too).
- **UI** (TestFX, `targeted-ui-testing` first):
  - `laser-show.ui-test.add-projector-driver-test/add-lsps-projector-test` (pick LSPS, type a host, Add, and its card on the rig strip shows "LSPS · 127.0.0.1:7266");
  - `…/idn-fields-unchanged-test`;
  - `laser-show.ui-test.projector-driver-settings-test/edit-lsps-port-test`;
  - `…/missing-driver-shows-warning-test`.

### As built (the model and UI half, branch `output-d2-ui` from `output-drivers` at 350abfe)

The encoder, LSPS, `output.api` and the docs were already in (and `lsps-projector-streams-test`, 1D). This half built the rest of the list above, with what the code had become since (zones Z2's rig strip and ZR's rig file):

- **`output.driver`:** `coerce-field`, NEW `coerce-config` (Add Manual's check: the defaults fill in, `{:config :errors}`), `summary` and NEW `summary-parts` (the card's two lines), NEW `display-name`, NEW `clamp-scan-rate [descriptor rate]` (`stream/clamp-scan-rate`, then the descriptor's `:capabilities :scan-rate`) and NEW `default-scan-rate`. NEW optional `:short-name` (validated; in the descriptor only when present), `"IDN"` and `"LSPS"` on the built-ins.
- **`events.handlers.projector`:** `make-projector-config` and `:projectors/add-manual` as planned; a non-IDN add is refused (state unchanged) when any field doesn't coerce or the driver isn't installed, starts at the driver's default scan rate, and an unnamed one is named after its settings (`"127.0.0.1:7266"`), as an unnamed IDN one is after its host. `:projectors/update-driver-config` as planned: nil unsets a key (its default applies), an IDN projector, an unknown key (`:driver` included) or a driver that isn't installed change nothing; undo label "Change Output Settings". `update-settings` drops `:driver` **and** `:driver-config` (the latter only through the checked event), and clamps the scan rate with the projector's descriptor. NEW `:projectors/set-manual-driver` and `:projectors/set-manual-field` for the dialog's Type and driver fields (UI state only; `:ui/update-dialog-data` merges one level, and the fields are under `:driver-config`).
- **`routing.rig`:** NEW `manual-stable-id`; `settled-ids` and `identity-label` as `01` §5.3 says (as built there).
- **`state.rig-file`:** `identity-keys` gained `:driver` and `:driver-config`, and a laser already here keeps exactly its own identity keys (the show's are dropped first, so an IDN laser here never takes the show's `:driver`); `pin-map`'s free id for a hostless projector is NEW `free-id`: the show's id with `-2`, `-3` ….
- **`subs`:** `rig-cards` gains `:driver`, `:host-label` (`driver/summary`), `:address-lines`, `:identifiable?`; `:found?` is true for any non-IDN projector, `:multi-output?` only counts IDN hosts. NEW `add-manual-types`, `add-manual-form`, `projector-driver`, `driver-info-text` (plain entries of `:driver-info`, by key, " · ") and `projector-driver-info`.
- **Views:** NEW `views/components/driver_fields.clj` (a grid of label and control, `driver-field-<key>` and `driver-field-<key>-error`; `:live` mode for the dialog, `:commit` mode with `commit-field` for the settings). The dialog: `add-projector-driver-combo`, IDN's fields and ids as they were, the window sized to its content as the fields change. The settings: a read-only "Type:" label (`projector-driver-label`), IDN's host and port as they were, other drivers' fields, and for a driver that isn't installed `projector-driver-missing` and `projector-driver-config-text`. The card: the address on two lines for other drivers (`rig-card-address-<id>`, `rig-card-address-detail-<id>`), a `projector-driver-info-<id>` line, a busy error as "In use by …" in amber without "Error: ". The tab's docstring no longer says IDN; the dialog's text is "Choose the output type and enter its connection details."
- **Show lock, history, CSS, docs:** the lock's `blocked-events` and the undo labels gained `:projectors/update-driver-config`; `.label-warning` and `.label-error`; the `domains` projectors docstring; `docs/OUTPUT_DRIVERS.md` (`:short-name`, the coercion messages, what the UI shows, its status line).

**Deviations from the text above:**
- `pin-map`'s new id for a hostless projector is its own id with `-2`, `-3` …, not `:projector-<ms>`: `pin-map` is pure and is worked out again later (`renamed-since` compares the pins of the Open with today's), so a clock in it would rename pins on every call.
- `:driver` and `:driver-config` are rig data (`identity-keys`), the coordinator's decision; the text above didn't say.
- Identify on the card is left out (not disabled) only where it can't reach the laser (an IDN laser with no host); any other driver's laser can be identified while it is outputting, as the text above says, since the test pattern goes through the driver like any frame.
- The card's address shows the driver's `:short-name` (NEW, optional) and its settings on a second line: "LSPS point stream (UDP) · 127.0.0.1:7266" doesn't fit a 136 px card, and one line cut the port off.
- There is no separate projector list since Z2: the cards are it, and the settings panel shows the driver's name.
- The dialog's driver text fields own their text while typed into (set once when created): a controlled `:text` lost keystrokes under fast typing, as the events run on the agent. IDN's own Host, Port and the Name field are left as they were (controlled, so Add's reset still blanks them); the live check showed they scramble under the same machine-speed typing, which a person doesn't reach.
- `output-lifecycle-test/driver-config-change-restarts-the-engine-test` now edits through `:projectors/update-driver-config` (it used `update-settings` with `:driver-config` "until D2's own event").

**Tests (as built):**
- `laser-show.events.projector-handlers-test`: `add-manual-lsps-projector-test`, `add-manual-idn-unchanged-test`, `update-driver-config-validates-test`, `update-driver-config-is-undoable-test`, `driver-never-changes-after-creation-test`, `manual-driver-projector-stable-id-test`, `scan-rate-clamps-to-driver-capabilities-test`, `live-lsps-projector-asks-before-a-larger-zone-test`.
- `laser-show.state.rig-file-test`: `bring-lasers-keeps-this-rigs-driver-test`, `pin-map-hostless-projector-test`. `laser-show.state.persistent-test`: `unknown-driver-projector-round-trips-test` (through `rig.edn` with `rig-store` and through the show's zip and a set-up).
- `laser-show.events.output-sync-test`: `driver-config-edit-resyncs-test`, `idn-fingerprint-unchanged-test`. `laser-show.events.show-lock-test`: `driver-config-edit-is-refused-while-locked-test`.
- `laser-show.output.driver-test`: `coerce-field-test` (`01` §5.4's table and around it), `coerce-config-test`, `summary-test`, `clamp-scan-rate-test`, `short-name-is-checked-and-described-test`.
- `laser-show.subs-test`: `rig-cards-show-driver-summary-test`, `add-manual-form-sub-test`, `projector-driver-sub-test`, `projector-driver-info-sub-test`. `laser-show.views.tabs.projectors-test`: `driver-status-text-test`.
- Red first: against the code before this half (the new test files' other namespaces set aside so the runner loads), 13 of these vars ran with 37 failures and 3 errors; the four that passed are guards of what already held (`add-manual-idn-unchanged-test`, `idn-fingerprint-unchanged-test`, `live-lsps-projector-asks-before-a-larger-zone-test` through 1D's `wanted`, and `unknown-driver-projector-round-trips-test`, since the data already round-tripped).
- NEW UI namespace `laser-show.ui-test.projector-drivers-test` (fixtures `with-toolkit`, `with-app`, and a drain): `add-lsps-projector-test`, `idn-fields-unchanged-test`, `add-button-disabled-until-fields-valid-test`, `edit-lsps-port-test`, `missing-driver-shows-warning-test`, `driver-status-shows-on-card-test` (a recording driver's `:status` through the real runtime: warning, error, `:info`, ok), `busy-driver-shows-in-use-test`, `not-responding-driver-shows-on-card-test` (a send blocked 3 s; Stop under 2.5 s), `lsps-projector-streams-from-the-ui-test` (added in the dialog with the loopback receiver's port, a 300-point circle playing, Stream: frames with no L-1–L-3 violations and "Streaming", Stop: one CLOSE, then silence and no status).
- Existing lines changed: `output_lifecycle_test.clj` `driver-config-change-restarts-the-engine-test` (above; its recording driver gets a `:port` field and the state the descriptors); `lsps_test.clj` `lsps-driver-is-registered-test` (the descriptor has `:short-name "LSPS"`).
- Live check (headless dev REPL with app-repl and plorer, `start-test!`, no rig file): Add Manual → LSPS → a bad port shows "Port must be 1–65535" with Add off; the receiver's port turns it on; Add; a circle playing; Stream: "Streaming · 59.5 fps", 106 frames of 300 points, no violations; Stop: CLOSE last, the card's status gone; an `:acme/nova` projector shows the not-installed text and its kept settings, and while streaming "Error: Output driver :acme/nova is not installed". It found the two fixes in f61ea74 (the typed-field race and the cut-off address).
- Suites at f61ea74: full unit 1,546 tests, 554,903 assertions; full headless UI 203 tests, 927 assertions; kondo 0 errors, 24 warnings (the same 24 as at 350abfe, none in the changed files). All green on the first full run; no timing failure seen.

**Commits:** `feat(projectors): projectors of any output driver, added by hand with a Type picker …` (5c5c189), `test(ui): projector drivers: …` (eab6aa0), `fix(projectors): live-check fixes: …` (f61ea74).

**With D3 (`output-drivers` at d82f9d9 merged into `output-d2-ui` at 55450f5):** the only conflicts were both sides adding subs and sub tests in the same place (both kept), and the docs' and plans' status lines. An add-on's driver is in the Type combo with nothing more: `:addons/loaded` writes its descriptor beside the built-ins'. `addons-settings-test/addon-driver-appears-and-streams-from-the-ui-test` now adds the UDP fixture's projector through the combo and its fields instead of writing it into state, and NEW `projector-drivers-test/addon-driver-is-in-the-type-combo-test` starts the app on a temp add-ons folder with the built `udp` and `broken` jars: the combo is `[:idn :lsps :test/udp-stream]`, with the UDP driver's fields, and nothing of the broken jar. Suites after the merge: full unit 1,558 tests, 554,965 assertions; full headless UI 210 tests, 1,013 assertions; kondo 0 errors, 24 warnings. All green on the first run.

**Commit:** `feat(output): projectors can use other output drivers; built-in LSPS point stream over UDP, with a Type picker in Add Manual`

---

## D3 · Add-on jars: done (7bb72eb)

The loader's core (`laser-show.output.addons`) was built first, on the loader branch; the app wiring, the effects, the handlers and the Settings section came at 7bb72eb, on `output-d3-ui` (from `output-drivers` at 350abfe). "As built" below says how that differs from the text.

**Goal:** drop a jar in the folder, or use Install add-on…, restart, and its driver appears in the Type picker and streams.

### Changes per namespace

- **`laser-show.output.addons`** (NEW):
  - `addons-dir`: the `laser-show.addons-dir` JVM property, else `user.home`/`LaserShowProjects`/`addons`, built with `io/file` (`01` §4.4);
  - `current-platform` and `supports-platform? [platforms platform]` (`01` §4.4);
  - `addon-file? [file]` (`01` §4.4's "which files count" table);
  - `load-all! [{:keys [dir platform classpath-loader parent-loader]}]` returns the status vector (`01` §4.2–4.3). `platform` defaults to `current-platform`, so tests can pretend to be another OS. `classpath-loader` (default the context classloader, `nil` for none) is where classpath descriptors are looked up, so tests stay hermetic. `parent-loader` (default the context classloader) is each jar loader's parent;
  - `install! [src dir]` checks the jar as `load-all!` would (not the platform), then copies it to `<name>.jar.new` through a `.part` file. It returns `{:file … :replaced? bool :descriptor {:id :name :version}}`, so `:addons/installed` can say "Installed Acme Nova 0.3.0". It throws an ex-info with the reason (`"Built for output API 2; this app has API 1"`), writing nothing, for a jar it refuses.
- **`laser-show.app`**: `start!` calls `load-all!` before `output-runtime/open!` (`:206`) and passes its statuses to step 0's `:addons/loaded` dispatch. `start-test!` doesn't load add-ons: tests register drivers directly.
- **`laser-show.events.core`**: NEW effects `:addons/install` (a future) and `:system/open-folder` (`Desktop/open`, then `xdg-open` on Linux, then a notice with the path: `01` §4.4). Both are added to the effects docstring and map (`:505-555`).
- **`laser-show.events.handlers.addons`**: `:addons/pick-install` (returns `:fx/show-file-chooser` with a `*.jar` filter, starting in the user's home folder), `:addons/install-picked` (does nothing when `:file-path` is nil), `:addons/installed`, `:addons/install-failed` and `:addons/open-folder`.
- **`laser-show.views.components.addon-settings`** (NEW, built like `views/components/midi_settings.clj`; wired into `views/tabs/settings.clj` next to `:59-62`):
  - the add-ons folder's path as the OS writes it;
  - for each add-on: name, version, Loaded, Failed or Skipped (with the reason), and its drivers;
  - **Install add-on…** and **Open add-ons folder**;
  - "To remove an add-on, quit Laser Show and delete its file from the folder."
- **`laser-show.events.core/effect-projectors-scan`** and **`streaming-engine`**: already go through `driver/with-loader` (D1). D3 is where the tests prove it.
- **CI** (`.github/workflows/build-and-test.yml:54-109`): the macOS and Windows job gains a step `clojure '-M:${{ matrix.platform }}:test:test-ui-headless' -n laser-show.output.addons-test` (`-n` loads only that namespace, so no other unit test namespace has to load on those OSes; `:test-ui-headless` in case anything pulls in cljfx, which starts JavaFX when it loads). It runs `if: !cancelled()`, so also after a UI test failure. The loader's tests then also run where Windows locks files and macOS has its own paths. The full unit suite stays on Ubuntu only (`:148`).

### Test fixtures

- **NEW `test-resources/addons/sample/`**: `laser-show-addon.edn` (`{:id :test/sample :name "Sample add-on" :version "1.0.0" :api 1 :drivers [laser-show-test-addon.sample.driver/driver]}`) and `laser_show_test_addon/sample/driver.clj`, a driver (`:test/sample-stream`) that appends every frame to an atom in its own namespace and requires its helper namespace on the first frame. It is a test fixture only, never built or shipped as an add-on (OD-8). `test-resources/` is on no classpath.
- **Variants:** `broken/` (the namespace throws), `api2/`, `taken-id/` (a driver with the sample's id; its namespace also has one with `:idn`, one without `:send-frame!` and one that isn't a map), `bundles-clojure/` (contains a fake `clojure/core__init.class`, kept in git past `.gitignore`), `read-eval/` (a descriptor with `#=(…)`), `windows-only/` (`:platforms [:windows]`). Each variant is the sample's files with its own on top.
- **NEW `test/laser_show/output/jar_builder.clj`**: builds jars into a temp dir with `java.util.jar.JarOutputStream`. It renames the placeholder namespace root `laser-show-test-addon` to one of its own for every jar it builds (`laser-show-test-addon-7`), because Clojure namespaces are global and `require` doesn't reload one: each jar's code is really loaded from that jar, and "not in `(all-ns)`" means something (`test-resources/addons/README.md`).

### Tests

- **`laser-show.output.addons-test`:**
  - `loads-driver-from-jar-test`;
  - `api-mismatch-is-not-loaded-test` (nothing required: the namespace isn't in `(all-ns)`);
  - `missing-descriptor-test`;
  - `taken-driver-id-registers-nothing-test` (A-1, A-2);
  - `broken-namespace-does-not-stop-others-test` (A-3);
  - `bundled-clojure-is-refused-test`;
  - `duplicate-addon-id-test`;
  - `pending-install-is-swapped-at-start-test`;
  - `descriptor-is-not-evaluated-test` (A-4);
  - `addon-driver-runs-on-the-streaming-thread-test` (built in 1D): an engine with the sample driver, frames read back through `(.loadClass …)`/`requiring-resolve` under the add-on's loader. Built on the loader branch (before D1's engine) as `addon-driver-runs-on-another-thread-test`: a plain `Thread` calls `:send-frame!` without `driver/with-loader` (it fails: the jar's helper namespace can't be found) and then through it (the frame is in the fixture's atom, and the helper was required on that thread). The engine version is for when D1 and D3 meet;
  - also built: `unreadable-jar-fails-test`, `invalid-driver-registers-nothing-test`, `classpath-descriptors-load-first-test`, `current-platform-test`, `install-writes-a-pending-jar-test`;
  - cross-platform (`01` §4.4; these run on all three OSes in CI):
    - `addons-dir-is-built-from-user-home-test` (with `user.home` set to a temp dir, the result is `(io/file home "LaserShowProjects" "addons")`; the property overrides it);
    - `folder-is-created-on-first-load-test`;
    - `path-with-spaces-and-non-ascii-loads-test` (a temp dir named `Zoë Smith/add ons`);
    - `uppercase-jar-extension-loads-test`;
    - `hidden-and-appledouble-files-are-ignored-test` (`._sample.jar` holding garbage, `.DS_Store`, `notes.txt`, a sub-folder: no statuses for them);
    - `platform-mismatch-is-skipped-test` (`01` §4.4's platforms table, with `:platform` passed in, so every row runs on every OS);
    - `unknown-platform-fails-test`;
    - `install-over-a-loaded-jar-is-pending-test` (install the same name while its loader is open: `.jar.new` is written, then the next `load-all!` swaps and loads the new version);
    - `crlf-descriptor-reads-test`.
- **`laser-show.events.core-test`:** `open-folder-falls-back-to-a-notice-test` (the `Desktop` and `xdg-open` calls stubbed to fail: an `:info` notice with the path).
- **`laser-show.events.addons-handlers-test`:**
  - `pick-install-opens-chooser-test`;
  - `install-picked-runs-install-effect-test`;
  - `cancelled-pick-does-nothing-test`;
  - `installed-shows-restart-notice-test`;
  - `install-failed-shows-error-test`.
- **UI:** `laser-show.ui-test.addons-settings-test/lists-loaded-and-failed-addons-test`.
- **Startup:** `load-all!` logs `:load-ms` for each add-on. Record the sample add-on's time in the commit message.

### As built (7bb72eb)

- **`laser-show.app`:**
  - NEW private `load-addons [opts]`: `load-all!` on opts' `:dir` (else `addons-dir`), one summary log line (each loaded add-on's `:load-ms`, the failed and skipped counts), `{:addons statuses :dir path}`. `dispatch-drivers-loaded!` takes it, so `:addons/loaded` carries `:addons`, `:drivers` and `:dir`;
  - `start!`: `load-addons` is a binding of ZR's `let` next to `rig` (`load-rig` is called as before), so it runs before `init-state!` and `output-runtime/open!`; the dispatch moved inside that `let`, right after `after-rig-loaded!`, where it was, so it is still before the startup scan;
  - `start-test!` gains `[initial-state opts]`: `{:addons-dir dir :addons-platform {:os :arch}}` loads that folder like `start!` (before `open!`, with `:classpath-loader nil`); without `:addons-dir` it loads nothing and `:dir` is `addons-dir`'s (the test runner's temp folder). `initial-state` nil is the starter state;
  - `stop!` calls NEW `addons/unload-all!` after the runtime is drained.
- **`laser-show.output.addons`:** NEW `unload-all!` unregisters every add-on driver and closes the loader of each one from a jar (marked `::jar-loader?` by `load-source`; a classpath add-on's loader is the app's and stays open). Returns the ids.
- **`laser-show.events.core`:** `:addons/install {:file-path :dir}` and `:system/open-folder {:path :on-failed}` (both futures), in the effects map and docstring. `:system/open-folder`'s platform calls are `folder-opener`'s `:desktop-open`, `:xdg-open` (waits up to 5 s for exit 0) and `:os`; NEW `set-folder-opener!` replaces some of them (nil restores), like `set-exit-fn!`. A folder that can't be made, a call that throws or returns false, all fall through to `:on-failed` with `:path`.
- **`laser-show.events.handlers.addons`:** as planned, plus NEW `:addons/open-folder-failed {:path}` (an `:info` notice "Couldn't open the add-ons folder. It is at <path>"). `:addons/installed {:file :replaced? :descriptor}` keeps one pending entry per file, the newest. The install's title is the name, else the id, then the version.
- **`laser-show.state.domains`:** `[:backend :output]` gains `:dir nil` and `:pending-installs []` (`00` §5).
- **`laser-show.subs`:** NEW `addons-dir`, `addon-rows` (`{:title :state :state-text :drivers :file}`, the file name taken from a path with either separator or a classpath `jar:` URL) and `pending-install-rows`.
- **`laser-show.views.components.addon-settings`:** under a new OUTPUT header in `views/tabs/settings.clj`. Ids: `addons-section`, `addons-install-button`, `addons-open-folder-button`, `addons-folder-path`, `addons-empty`, `addons-remove-hint`, `addon-row-<n>` (0-based) with `-title`, `-state`, `-drivers`, `-file`, and `addon-pending-row-<n>` likewise.
- **CI:** the macOS and Windows step for `laser-show.output.addons-test` was already there (`.github/workflows/build-and-test.yml:110`).
- **Deviations, and why:**
  - `[:backend :output :dir]` is new: the view shows the folder, Install copies into it and Open opens it, all from state, so a test's `:addons-dir` is the folder all three use, and the handlers stay pure;
  - `:addons/open-folder-failed` and the effect's `:on-failed`: the notice is made by a pure handler rather than by the effect;
  - `stop!` unloads add-ons (OD-4 has no unload while the app runs): only once nothing streams, so that a REPL restart and each UI test's fresh app load the folder again instead of finding the ids taken. A namespace an add-on loaded stays in the JVM, so a restart in the same JVM reuses its code (the first third-party add-on loaded again in 9 ms);
  - `start-test!`'s `:addons-platform`: so the Windows-only row reads "Skipped" on every CI OS;
  - the live check used `start-test!` with `:addons-dir` instead of `start!`: `start!` scans the network at startup, which the check must not do.

### Tests as built

- **`laser-show.events.addons-handlers-test`:** `pick-install-opens-chooser-test`, `install-picked-runs-install-effect-test`, `cancelled-pick-does-nothing-test`, `installed-shows-restart-notice-test` (installed, replaced, installed twice, no name, not undoable), `install-failed-shows-error-test`, `open-folder-runs-effect-test` (and the failed notice). Changed: `loaded-writes-descriptors-test` (the defaults gain `:dir nil :pending-installs []`; a new `testing` for `:dir` and the cleared pending list).
- **`laser-show.events.core-test`:** `install-effect-dispatches-installed-test` (a real sample jar into `Zoë Smith/addons`, through `:addons/install-picked`, then Replaced), `install-effect-dispatches-failed-for-a-bad-jar-test` (api2, a `.txt`), `open-folder-falls-back-to-xdg-open-test`, `open-folder-falls-back-to-a-notice-test` (also a folder that can't be made). Changed: `effects-and-co-effects-are-shared-maps-test` lists the two new effects.
- **`laser-show.output.addons-test`:** `unload-all-unregisters-and-closes-test`.
- **`laser-show.subs-test`:** `addon-rows-test`.
- **NEW `laser-show.ui-test.addons-settings-test`:** `lists-loaded-failed-and-skipped-addons-test` (sample, broken and windows-only jars; a restart loads them again), `empty-addons-folder-test`, `install-button-installs-and-shows-pending-test` (the chooser effect stubbed through `events/effects` when the app is created; the `.jar.new`, the notice, the pending row, the restart that loads it, a refused jar, a cancel), `open-folder-button-test` (`set-folder-opener!`), `start-loads-addons-before-the-scan-test` (the real `app/start!` with its device and network services stubbed and `dispatch!` recording: `load-all!` before `open!`, `:addons/loaded` first with the statuses and the add-on's driver, before `:projectors/scan-network`), `addon-driver-appears-and-streams-from-the-ui-test` (the NEW `udp/` fixture's driver listed in Settings, its descriptor in state, Stream: datagrams from `output-streaming-projector-udp`, Stop: no more). The projector goes into the state directly, with a TODO to add it through Add Manual's Type combo once 2A's D2 UI is merged: this branch's `:projectors/add-manual` takes no `:driver`.
- **Live check** (the app in dev mode, headless, `-Dlaser-show.addons-dir` at a temp folder with the first third-party add-on's jar, built in its repo): it was Loaded in 138 ms, with its two drivers; an install over it said "Replaced <the add-on> 0.1.0. Restart Laser Show to load it." and listed the `.jar.new`; the restart swapped it in. No Scan, no projector of that add-on's, nothing streamed.
- **Suites at 7bb72eb:** full unit 1,534 tests, 554,798 assertions; full headless UI 200 tests, 953 assertions; kondo 0 errors, 24 warnings (as at 350abfe, none in the changed files). All green on the first full run; no timing failure seen.

**Commit:** `feat(addons): output drivers from add-on jars in the add-ons folder (~/LaserShowProjects/addons), loaded at startup and listed in Settings, on macOS, Windows and Linux`

---

## Live UI pass (D2 + D3, branch `output-ui-pass` from `output-drivers` at 392a84d)

The real app in a headless dev REPL (`start-test!` with a temp `:addons-dir` and a temp rig file, `-Dlaser-show.addons-dir` and `-Dlaser-show.rig-file` at temp folders too), driven with plorer. No Scan, nothing sent but to loopback receivers started in the REPL, no projector of the third-party add-on added or streamed. Screenshots (local, gitignored) under `test-results/output-ui-pass/`, `p1-` for the polish, `f1-`…`f6-` for the flows.

**Polish (7476225):** Add Manual's labels and fields in one grid (`#add-projector-grid`, `driver-fields/grid-cells`; IDN's Host and Port a row each); an unnamed non-IDN projector is "LSPS 1", "LSPS 2" … (NEW `driver/default-projector-name`, also the Name field's prompt), and a card's title has the whole name in a tooltip (`rig-card-name-<id>`); a card's status and driver lines wrap to the card's width, three lines at most, with "Driver not installed" and "Driver not responding" (NEW `:kind :stuck` in the status) for the core's long texts and the whole message in the tooltip (`rig-strip/card-status-text`, `rig-cards` `:driver-installed?`); an uninstalled driver's kept settings as "Serial: NV-00123" lines.

**Flows checked:**
1. Add-ons: the first third-party add-on's jar (built with `clojure -T:build jar` in its repo) and the `udp` and `broken` fixtures: that add-on and udp Loaded, broken Failed with its reason; the Type combo is IDN, LSPS, the add-on's USB and network drivers, UDP test stream, each one's fields shot (`f1-`).
2. LSPS: a bad port ("Port must be 1–65535", Add off), then the receiver's; added as "LSPS 1"; a cue and Stream: 60.7 fps, 6% load, no L-1–L-3 violations; the port to a second receiver restarts the engine (CLOSE to the first); Undo (menu and ⌘Z) puts it back; Stop: CLOSE last, then silence.
3. The udp add-on: the same; Stop in about 230 ms.
4. Failure states (REPL recording drivers): busy ("In use by ShowCtl on 10.0.0.3", amber), stuck send ("Driver not responding"; Stop in about 1.1 s with the send blocked 20 s), `:warning` and a four-entry `:driver-info` (wrapped), a `:reopen?` error that recovers after one new `:open`, a projector whose driver isn't installed; the toolbar's "Streaming to 1 of 5 · 4 errors" with every message in its tooltip.
5. Restart: Install add-on… (the chooser's result stubbed as the D3 tests do) wrote `udp.jar.new`, the notice "Replaced UDP test add-on 1.0.0. Restart Laser Show to load it." and the pending row; `stop!` then `start-test!` on the same folder swapped it in (the new jar's namespace root), Loaded, and the three projectors came back from the rig file and streamed.
6. Show lock: a driver-config edit refused with "Show lock is on: Change Output Settings refused. Unlock it on the Perform tab", and "Change Output Settings refused" under the switch.

**Bugs found and fixed, each with a test that failed first:**
- A driver's choice ended short of its column (b1118a5; `add-manual-fields-share-one-grid-test`'s right edges).
- The projector settings had the driver's fields in a grid of their own (a11dee3; `projector-settings-share-one-grid-test`).
- The dev frame stats called every driver's send time "IDN" (c01fbe9; `status-bar-test/send-time-is-every-drivers-test`).
- The LSPS receiver called a new session's sequence 0 a violation, a test helper's bug (bc76ddd; `lsps-test/a-new-session-starts-its-own-sequence-test`).
- ⌘Z in a field just applied with Enter undid only the field's text (2c9e7eb; `undo-shortcut-in-a-committed-field-undoes-the-edit-test`).
- A driver's ex-info error began "clojure.lang.ExceptionInfo: " and its message didn't fit (16731cd; `output-errors-test/message-says-what-a-driver-said-test`, `reopen-error-shows-the-drivers-message-then-recovers-test`).
- A broken add-on said "No such namespace" on a second load in one JVM (4691de5; `addons-test/broken-namespace-fails-the-same-after-a-restart-test`).
- A field, choice or check box the show lock refused kept showing the refused value, and a field sent it again on focus loss (22073ae; `show-lock-refuses-a-driver-config-edit-test`, `show-lock-refuses-a-driver-choice-or-check-box-test`).
- At 1200 px in dev mode the status bar cut every label off ("Ba…", "Out…"): the bar's HBox shrinks every item by the same amount, its labels had no minimum width, and "60.4–60.7 fps · 3 errors" took it past its width. Labels and values keep their width now; only the output summary and the notice give way, with their whole text in a tooltip (3c728b4; `streaming-status-test/status-bar-labels-are-never-cut-off-test`).

**Left as they are:** the first third-party add-on's drivers have no `:short-name`, so their cards and default names use the long `:name` (the add-on's to add, docs say so now); the projector's Name field (applied per keystroke) still shows text the show lock refused, as before this plan.

**Suites** (at 3c728b4): targeted UI namespaces 71 tests, 355 assertions; full headless UI 219 tests, 1,075 assertions; full unit 1,564 tests, 554,997 assertions; kondo 0 errors, 24 warnings (as at 392a84d). One timing failure in the first full UI run, `cue-load-test/heavy-cue-shows-a-warning-on-its-cell-test` (a cue that didn't start within 3 s on the shared machine): it passed 3 of 3 on its own and in the second full run.

**With `master`'s layout pass (merged at `6f96aaf`):** the inspector's three columns scroll on their own and a divider sits between the strip and the inspector. The pass sized the strip from a guess of a card's height, but a card's status and driver lines wrap here, up to three lines each, so a card is often taller (a streaming laser that says "limited by scan rate" is enough at 1200 × 800), and the divider cut it. NEW `rig-strip/fit-to-cards!` sets the strip's min and max height from its laid-out cards and rows after each layout pass, and `follow-default-divider!` puts the divider back at its default when they change, until it is moved; the guess (`cards-height`) gained D2's address line and the 6 px of a status row a line high. NEW `projectors-layout-test/a-card-that-grows-is-never-cut-test` (failed first without `fit-to-cards!`). Screenshots (local, gitignored) under `test-results/output-merge-layout/`: an LSPS projector's settings grid, the failure cards (not installed, busy, a driver's warning and info) while streaming to a loopback receiver, and the read-only settings of a driver that isn't installed. Suites: targeted UI namespaces 86 tests, 434 assertions; full unit 1,656 tests, 556,178 assertions; full headless UI 242 tests, 1,275 assertions; kondo 0 errors, 19 warnings. All green on the first run.

---

## D4 · Discovery for every driver: done on `output-d4-discovery`

**Built:** `8b6d3aa` (feature, contract docs and unit tests), `0d673ea` (TestFX), from `5a9a41c`. **Scan** discovers IDN and driver outputs together. A new output gets its own disabled card; a known output keeps its projector and calibration when its connection changes.

### As built

- The button and startup (`views/components/rig_strip.clj:113-127`, `app.clj:257`) dispatch `:projectors/scan-network`. Its handler sets the scanning flag and requests the existing `:projectors/scan` effect (`events/handlers/projector.clj:127-133`). The effect queues one completion or failure from a future (`events/core.clj:237-246`). No scan worker writes app state.
- NEW `output/discovery.clj` keeps the OS interface snapshot and pure broadcast selection (`:29-57`), IDN adapter (`:62`), add-on result validation (`:87-149`), futures and deadline (`:154-195`) together. IDN uses the same Hello call (3000 ms, 1000 ms per service map), normalization and IDs as before. Its future gets the same overall 3-second deadline as every other discoverer. Work, including realization of an add-on's outputs, happens through `driver/with-loader` (`output/driver.clj:437`). Results are collected IDN first, then by driver id. One invalid device doesn't hide its valid siblings. Partial failures carry warnings; if everyone fails, the error is IDN's.
- `rig/match-discovered` handles both device shapes (`routing/rig.clj:690-778`), reusing the settled-id index and set of taken projector ids. The new identity is `[:device driver device-key output-id]`. Its readable base id uses `driver-projector-id` and the same `unique-projector-id` suffixes as IDN (`:623-640`). A move writes exactly `{:driver-config new-config}`, including removal of obsolete config keys. Field defaults apply when the driver opens. A scan never overwrites a name, tag, group, placement, enabled state, safety zone or calibration chain on a known projector.
- `scan-complete` (`events/handlers/projector.clj:185-246`) puts new outputs and moves in the rig document, existing undo snapshots and the saved baseline. Scan results and the scanning flag remain transient UI state. Move notices and any discovery warnings share one notice, at warning level if there are warnings. Removing a driver projector takes its output out of the last-scan device list (`:608`).
- `rig/output-key` (`routing/rig.clj:179`) supplies a common key for card lookup, grouping and enable actions. IDN uses `[host service]`; another discovered output uses `[driver device-key output-id]`; another driver added by hand has no key. A saved driver output can be marked missing only after a scan and only if its installed driver still supports discovery (`subs.clj:1131-1195`). The menu dispatches `:projectors/enable-device {:driver d :device-key k :enabled? true}` for a driver device (`rig_strip.clj:345-365`), still by host for IDN. This is one undoable rig edit and the show lock refuses it (`events/handlers/projector.clj:272`, `events/handlers/history.clj:176`, `events/show_lock.clj:46`).

### Deviations from the sketch

- Z2 already provided the rig strip, per-card On box, matcher and backfill path. D2 already provided the `[:device …]` identity label and the collision rules for saved identities. D4 extends those paths; it needs neither a discovery panel nor new persistent identity fields.
- The effect delegates to a discovery namespace rather than doing discovery and validation inline. All futures share **one deadline** so multiple unresponsive drivers cannot multiply the scan time. Timed-out futures are cancelled; a driver still needs bounded I/O. The IDN driver map and the Hello protocol are unchanged.
- `enabled-projector-services` (`subs.clj:610`) now uses the same keys even though no view calls it. The IDN-only setters can no longer match another driver's outputs via a nil host. The `enable-device` event takes an explicit boolean and has an undo/lock label. The old expand-set state is untouched.

### Tests and validation

New tests (namespaces below have the `laser-show.` prefix):

| Namespace | Test vars |
|---|---|
| `output.discovery-test` | `broadcast-addresses-from-interfaces-test`, `interfaces-are-data-test` |
| `events.core-test` | `scan-runs-every-discoverer-test` (barrier and loader, including lazy output data), `discoverers-get-broadcast-addresses-test`, `invalid-driver-device-is-dropped-test`, `failing-discoverer-gives-warning-test`, `slow-discoverer-times-out-test`, `all-discoverers-fail-gives-scan-failed-test`, `idn-only-scan-unchanged-test` |
| `routing.rig-test` | `driver-projector-id-test`, `match-discovered-driver-devices-test` |
| `events.projector-handlers-test` | `scan-creates-driver-projectors-test`, `rescan-keeps-driver-projector-test`, `moved-driver-device-is-matched-test`, `scan-warnings-show-a-notice-test`, `enable-device-test`, `remove-driver-projector-leaves-the-device-list-test` |
| `events.show-lock-test` | `enable-device-is-refused-while-locked-test` |
| `subs-test` | `enabled-outputs-cover-drivers-test`, `rig-cards-find-driver-outputs-test` |
| `ui-test.discovery-drivers-test` | `driver-device-can-be-enabled-test`, `not-found-after-rescan-test`, `turn-on-every-output-of-a-device-test`, `scan-warning-notice-test`, `discovered-driver-streams-test` (fake discovery delegating to the real LSPS sender) |

Existing tests changed: `views.tabs.projectors-test/rig-header-text-test` expects “Scan”; `events.projector-handlers-test/failed-scan-shows-a-notice-test` and `subs-test/rig-cards-show-driver-summary-test` have updated assertion descriptions only. Existing IDN scan fixtures and assertions still run.

**Red first:** the initial six unit namespaces had 70 failing assertions; all five new UI flows timed out on the IDN-only implementation. Contract checks also caught an unbounded IDN wait, a grace period accepting late results, lazy outputs read outside the loader, and filling/merging configs rather than replacing them. A TestFX menu-text wait was a test bug: a popup item is a container, not a label. Fixed the lookup and reran the menu test in isolation (1 test, 3 assertions). No timing failures in either final suite.

| Apple Silicon Mac | Tests | Assertions | Result |
|---|---:|---:|---|
| Targeted unit (the six unit namespaces above plus `views.tabs.projectors-test`, `events.history-handlers-test`) | 225 | 3,876 | pass |
| Targeted new TestFX namespace | 5 | 25 | pass |
| Related TestFX (`rig-strip`, `rig-card`, `projector-drivers`, `zones`, `setup-dialogs`, `rig-report`) | 74 | 368 | pass |
| Full headless UI (`clj -M:test-ui:mac-arm:test-ui-headless`) | 248 | 1,302 | pass |
| Full unit (`clj -M:mac-arm:test`) | 1,681 | 556,337 | pass |

`clj -M:kondo --lint src test --parallel --fail-level error`: 0 errors, 18 existing warnings.

**Live check:** `app/start-test!` in a dedicated headless nREPL, with a temporary rig file and an empty add-ons folder. Before each plorer Scan the check asserted the identity of the IDN Hello stub and the complete set of registered discovery functions. Three new cards appeared off; the menu turned on only the two outputs of `NV-7`. With just the bridge enabled, a starter circle and Stream produced **24 complete 65-point frames** on `127.0.0.1`, no LSPS violations. Stop produced **one CLOSE, then silence**. A second fake scan showed the two missing outputs and the warning. An earlier interactive check left the loopback recorder collecting packets during a pause and exhausted the REPL heap; the final check bounded Stream and Stop in one script and closed the receiver and app in `finally`.

**Network isolation:** new scan tests stub `idn-hello/discover-devices-with-services`, enumerate fake interface data and allow only test discoverers before pressing Scan. The real receiver binds loopback; no private add-ons are loaded. The final full suites and the live REPL additionally ran under a process-local macOS policy denying outbound IP other than loopback.

**Screenshots** (local, gitignored): `test-results/output-d4/d4-1-before-scan.png`, `d4-2-after-scan.png`, `d4-3-card-menu.png`, `d4-4-enabled.png`, `d4-5-streaming.png`, `d4-6-rescan-not-found-and-warning.png` in the same folder.

---

## D5 · Checking the contract against a real USB add-on

**Goal:** prove the add-on path on real USB hardware with a device-paced DAC (`:ready?`).

The USB add-on is built in **its own repo** (OD-8), and its USB library is that repo's choice. Nothing from it lands here. This phase in this repo is limited to:
- fixing whatever the add-on shows is missing or wrong in the contract, `laser-show.output.api`, the loader or `docs/OUTPUT_DRIVERS.md`;
- bumping `api-version` if a fix breaks API 1. Ideally nothing breaks it.

What the add-on is expected to do, which is the contract test:
- **The driver:** a qualified id, `:transport :usb`, `:platforms` listing the OSes it ships native libraries for.
  - `:discover` lists USB devices matching the vendor and product ids from the device's SDK;
  - `:ready?` reads the device's status;
  - `:send-frame!` encodes with `laser-show.output.api` or packs its own format;
  - `:capabilities` (points per frame, scan-rate range) come from the SDK;
  - `:close!` stops the output and leaves it dark.
- **Hardware acceptance, at low brightness, on each OS the add-on supports:**
  - the jar loads from the add-ons folder;
  - Scan finds the device;
  - it streams at 25k and 30k pts/s;
  - Stop goes dark within about one frame;
  - unplugging while streaming gives an error status, and other projectors are unaffected;
  - plugging it back in and pressing Scan resumes;
  - quitting is clean (no stuck `output-close-*` thread in a thread dump).

---

## Done when

- [ ] D1–D4 are merged with the full unit and UI suites green (`targeted-ui-testing`: the targeted tests first, then both full suites), and the add-on loader tests green on macOS, Windows and Linux in CI.
- [ ] D5: a USB add-on from its own repo has passed the hardware checks above.
- [x] `docs/OUTPUT_DRIVERS.md` is current through D4.
- [ ] Then mark this plan Done and delete it (git keeps it).
