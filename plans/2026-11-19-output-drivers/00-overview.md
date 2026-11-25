# Output drivers: more than IDN, from add-ons

**Written:** 2026-11-19   **Status:** In progress. Step 0 through D3 and the live UI pass landed at `5a9a41c`. **D4 landed** at `4b22368`; D5's hardware acceptance is pending. Every question is answered (§9).
**Last verified against:** `0d673ea` for D4 (`02` § D4). The historical integration and pre-driver citations in §1 and §7 retain their original revisions.
**Supersedes / Superseded by:** none
**Related findings:** review 04 LE2 (one IDN-RT link per DAC, which stays inside the IDN driver), 04 #17 (flat point arrays: not needed here, see `01` §2.1). This plan needs to fit with the zones plan (`plans/2026-11-19-zones/`): Z1 safety and Z2 stable ids and the rig strip (both on master), Z3 routing core and ZR rig file (both on master, merged into `output-drivers` at `6f6f4df`). See §7.

Paths are under `src/laser_show/` unless they start with `test/`, `plans/` or `docs/`. **NEW** marks anything that doesn't exist yet.

| Doc | What |
|---|---|
| `00-overview.md` | This doc: current behaviour, design, decisions, state, events, coordination, questions |
| `01-driver-contract.md` | **The contract**: the driver map (API 1), the point encoder, the LSPS point protocol, the add-on jar format, invariants, data examples |
| `02-phases.md` | Phases D1–D5: changes per namespace, the tests for each, how to check each is done |

## Start here

Today a projector can only be an IDN DAC on the network. After this plan:

1. **Every projector has a driver.** IDN is the built-in `:idn` driver, and existing projectors and saved shows don't change.
2. **Drivers are add-ons.** You drop a `.jar` into the add-ons folder (`~/LaserShowProjects/addons/` on macOS and Linux, `%USERPROFILE%\LaserShowProjects\addons\` on Windows) or use **Settings → Add-ons → Install add-on…**, then restart. The jar's drivers then show up in **Add Manual…** and in **Scan**. Other makers' DACs can be supported this way. **No add-on lives in this repo**: each one is built and shipped from its own repo. This repo only has the contract, the loader and a small test fixture.
3. **A driver only handles the wire.** It gets a frame of normalized points and sends it however it likes: UDP, TCP, USB or a native SDK. Pacing, keepalive, stats, error blanking, stop, status and safety stay in the core. A driver can't skip the safety rules and can't hang the app.
4. **Drivers don't need to convert points themselves.** A driver describes its sample layout as data, for example `[[:x :s16] [:y :s16] [:r :u8] …]`, and the core encodes the frame into a byte buffer.
5. **There is a built-in second protocol, LSPS** ("Laser Show Point Stream"). It is our own simple, documented UDP point format, and it does two jobs:
   - it tests the driver seam end to end;
   - it lets a separate program (a "bridge") drive any hardware, in any language.
6. **A projector's driver is fixed when the projector is created** (by Scan or Add Manual), because that's how the app first reached it. For a different connection you add a new projector (OD-11).
7. **Cross-platform from the start**: the folder, the jar handling, Open folder and add-ons with native libraries all work on macOS, Windows and Linux, and CI checks the loader on all three (OD-12).

Build order: **step 0** (the shared skeleton: the driver registry and its checks, the descriptors in app state, `:addons/loaded`) → **D1** (the seam, with no behaviour change) → **D2** (the projector model, the UI and LSPS) → **D3** (add-on loading) → **D4** (discovery for every driver) → **D5** (a check against a real USB add-on built in its own repo). D1, D2's encoder and LSPS, and D3's loader branch from step 0 and can be built side by side; they merge in the order D1 → D2 → D3.

| Phase | What you get | Size |
|---|---|---|
| 0 | `laser-show.output.driver` (registry, `validate`, `descriptors`, `projector-config`, `with-loader`), `[:backend :output]`, `:addons/loaded`, two subs. No driver is registered yet, and nothing changes for users | ~0.5 d |
| D1 | IDN runs as a driver behind a contract. Every existing wire and lifecycle test still passes | ~1.5–2 d |
| D2 | `:driver` and `:driver-config` on projectors, a Type picker in Add Manual, generic driver fields, the point encoder, the built-in LSPS driver | ~2 d |
| D3 | Jar add-ons: loading, checking, the Settings → Add-ons section, Install add-on… | ~2 d |
| D4 | Scan runs every discoverer; each new output becomes a disabled card, and a known output keeps its projector when it moves | Done (`4b22368`) |
| D5 | Proof on real USB hardware: an add-on from its own repo loads, scans, streams and stops cleanly. Only fixes to the contract or the loader land in this repo | ~1 d here (+ the add-on's own work) |

## 1. Current behaviour (file:line)

As it was at `cd10149`, before D1. The citations into `streaming_engine.clj`, `create-engine-for-projector` and `output_runtime.clj` show the IDN-only code D1 replaced, at `cd10149`. Step 2 and the citations into the files zones ZR and Z3 changed (routing, the projector handlers, `events/core.clj`, `subs.clj`, the rig strip, the show lock, `state/*`) are at `6f6f4df`, the merge of `master` into `output-drivers`.

The frame path today:
1. The show clock publishes a tick.
2. Each engine's frame provider (`backend/multi_engine.clj:214-249`) builds the projector's frame (`projector-frame` `:160-186`, `show-frame` `:188-212`): since zones Z3, what the laser draws is composed from the tick's routing (`routing/compose.clj`), then the fit guard (`animation/fit_guard.clj`), then projector effects, then Z1's safety stage (`guard-safety` `:109-124`), and the frame carries the guard's record as `:fit` metadata. While Identify or calibration shows the test pattern, that pattern goes through the same safety stage instead (`test-pattern-frame`, `:145-156`).
3. The streaming loop (`backend/streaming_engine.clj:310-401`) runs `frame-rules/sanitize-frame` (`:373`, `idn/frame_rules.clj:19-31`).
4. The loop calls `send-frame!` (`:236-265`), which encodes IDN-Stream and wraps it in IDN-Hello.
5. The packet goes out over UDP.

IDN is wired into every layer:

| Where | What is IDN-specific |
|---|---|
| `backend/streaming_engine.clj:72-145` `create-engine` | The engine map mixes generic fields (`:id :frame-provider :frame-signal :fps :scan-rate :running? :thread :stats`) with IDN fields: `:target-host/port`, `:channel-id`, `:service-id`, `:output-config`, `:max-samples` (`:126-127`), MTU and fragmentation, `:packet-buffer` (`:130`), `:socket` (`:132`), `:target-address`/`:last-address` (`:134-137`), `:start-ns` (`:140`), `:last-config-ns` (`:142`), `:sequence-counter` (`:144`) |
| `:151-265` | Timestamps, config resend (`:157-164`), wire duration (`:170-183`), packet building (`:185-216`), sequence (`:218-222`), DNS cache (`:224-230`), `send-frame!` (`:236-265`) |
| `streaming-loop` `:310-401` | Generic pacing (show clock or deadline, keepalive, minimum gap), stats and error handling. The IDN-specific bits are the start log (`:349-352`), the config/sequence reset (`:354-355`) and the re-resolve of the host on an error (`:388`) |
| `start!` `:413-446`, `close!` `:472-481`, `send-close-pair!` `:452-470` | A UDP socket (`:421`), and IDN close plus RT close on stop |
| `backend/multi_engine.clj:275-308` `create-engine-for-projector` | Reads `:host :port :service-id :output-config :scan-rate` (`:287-297`). Returns nil for a blank host (`:288`) |
| `backend/output_runtime.clj:35-39` `fingerprint`, `:41-50` `wanted` | The fingerprint is `[:host :port :service-id :output-config :scan-rate]`. Only projectors with a non-blank `:host` are wanted. `start-engine!` (`:116-129`) reports `"No host"` (`:129`) |
| `events/handlers/output_sync.clj:13-23` | Resyncs when `wanted` changes, so it only reacts to the keys above |
| `events/safety_guard.clj:13-18` `outputting?` | Z1's "light can reach places it couldn't before" questions (and Undo's) ask only for a projector that `output-runtime/wanted` has, so one with a host |
| `routing/rig.clj:569-573` `projector-id`, `:575-581` `unique-projector-id` | A scanned output's id is `:projector-<ip>-<service>`, with `-2`, `-3` … while that is taken |
| `routing/rig.clj:172-179` `stable-id`, `:586-599` `settled-ids`, `:616-680` `match-discovered`, `:181-192` `identity-label` | Every projector has a `:stable-id`, `[:unit "0A 1B …" s]` or `[:host address s]`, made from `:unit-id`, `:host` and `:service-id`. A scan matches each output to a projector by it, and moves a known unit to its new host and port. Devices without an `:address` are dropped (`:633`). Loading an older show gives each projector one (`state/migration.clj:29-35`). The inspector and the cards show it as "Unit … · output 1" or "IP … · output 1" |
| `events/handlers/projector.clj:77-105` `make-projector-config` | `{:pre}` needs a host (`:91`). Fills in port 7255, the stable id (`:98`) and the IDN bit depths (`:104-105`) |
| `:111-184` scan (`discovered-projector` `:119-135`, `handle-projectors-scan-complete` `:157-184`), `:227-243` add-manual | Only IDN devices and fields |
| `events/core.clj:233-271` `effect-projectors-scan` | Only `idn-hello/discover-devices-with-services` (`idn/hello.clj:747`) |
| `views/dialogs/add_projector_manual.clj:12-92` | Name, Host and Port only. "Enter the connection details for your IDN laser projector." (`:28`) |
| `views/tabs/projectors.clj:54-143` `projector-basic-settings` | Host and port fields (`projector-host-field`, `projector-port-field`), scan rate, service |
| `subs.clj:847-889` `found-outputs`, `rig-cards`; `views/components/rig_strip.clj:142-159`, `:278-296` | Since Z2 every output a scan finds is a card on the rig strip (there is no discovery panel any more). A card's address line is `host #service` (`subs.clj:880`). "Not found in the last scan" is keyed by `[address service-id]` (`:847-855`, `:886`). The outputs of one device are grouped by host (`:872`, `:887`), for the card menu's "Turn on every output of <host>" (`rig_strip.clj:289-292`, `:projectors/enable-all-by-ip`). Identify is off without a host (`:287`). `enabled-projector-services` and `expanded-discovered-devices` (`subs.clj:605-622`) and `:projectors/toggle-device-expand` (`projector.clj:649-656`) are still there, but no view uses them |
| `events/handlers/projector.clj:460-468` `drop-discovered-output` | Removing a projector drops its output from the scan's device list by host and service id |
| `events/show_lock.clj:24-53` `blocked-events` | The projector edits that the show lock refuses are listed one by one |
| `state/domains.clj:285-315` | The projectors docstring describes only IDN keys |
| `animation/types.clj:14-16` | "Conversion to hardware-specific formats … happens at the IDN output stage" |
| Add-ons | None. `grep -ri "addon\|plugin" src` finds nothing |
| Distribution | An uberjar (`build.clj:54` `uber`, `.github/workflows/release.yml:84`). Users can't add jars to the classpath |
| Licence | GPLv3 (`LICENSE`) |

## 2. Root cause

The streaming engine decides two things at once: **when** a frame goes out (pacing, keepalive, errors, stop) and **how** it goes out (IDN encoding, a UDP socket, close packets). Nothing separates the two, so a second protocol would need a second copy of the timing and safety logic. The projector model and the UI are built around one address shape, host plus port plus service. There's also no way to load code that isn't in the uberjar.

## 3. Design in one page

```
show clock ─► frame provider (zones, projector effects, Z1 safety) ─► CORE LOOP ─► driver
                                         backend/multi_engine.clj         │            │
                                                                          │   :open / :send-frame! / :close!
                                              pacing, keepalive, ready?, │   (+ optional :ready? :on-error :discover)
                                              sanitize-frame, stats,     │
                                              error blanking, stop       ▼
                                              backend/streaming_engine.clj   laser-show.idn.driver  (built-in :idn)
                                                                             laser-show.output.lsps (built-in :lsps)
                                                                             <add-on jar drivers>    (:acme/nova, :zeta/beam …)
```

- **Driver** (`01` §1): a map with descriptor data (id, name, transport, config fields, capabilities) and functions (`:open`, `:send-frame!`, `:close!`, plus optional `:ready?`, `:on-error`, `:discover`, `:describe` and, since 1D, `:status`). Its errors can say more in their `ex-data`: `:reopen?` (close the session and open a new one) and `:busy?` (another program has the device).
- **Registry** (NEW `laser-show.output.driver`): a runtime atom `{driver-id driver}`. The built-ins are registered when the namespace loads, add-ons at startup. A pure-data copy of every descriptor goes into app state, so handlers, subs and views stay pure.
- **Core loop**: `streaming_engine.clj`, made generic. It holds the driver's session in an `:session` atom.
- **Projectors**: an IDN projector stays exactly as it is, with no `:driver` key (meaning `:idn`). Any other driver's projector looks like `{:driver :lsps :driver-config {:host "127.0.0.1" :port 7266} …}` (`01` §5).
- **Add-ons** (`01` §4): a jar with `laser-show-addon.edn` at its root, loaded at startup through its own `URLClassLoader` (parent-first). It is checked against output API 1, and if anything is wrong nothing from it is registered.
- **Public API for add-on authors** (NEW `laser-show.output.api`): the API version, the point constants, `blanked?`, the point encoder, `scan-duration-us` and (1D) `log`. This is the only namespace add-ons may depend on. It is documented in NEW `docs/OUTPUT_DRIVERS.md`.

## 4. Decisions (OD-1 to OD-10 are the plan's own; OD-11 and OD-12 come from your answers of 2026-11-20; OD-13 from the first third-party add-on, 1D)

| # | Decision | Why |
|---|---|---|
| OD-1 | One output path. IDN becomes the `:idn` driver, and the loop stays in `streaming_engine.clj` | The fake-DAC wire tests (`test/laser_show/backend/engine_wire_test.clj`, `streaming_engine_test.clj`, `output_lifecycle_test.clj`) then protect every driver, and the safety logic exists once |
| OD-2 | The contract is a Clojure map of functions plus data (API version 1). It is not a `defprotocol` and not a Java interface | Engines are already maps. Maps survive a dev reload (protocols don't). An add-on needs nothing compiled. A Java author writes a ten-line Clojure wrapper |
| OD-3 | IDN keeps `:host :port :service-id :output-config` at the top level of the projector. Other drivers use `:driver-config` | No migration of saved shows. The zones plans cite these keys throughout |
| OD-4 | Add-ons load once at startup, from the add-ons folder's jars (`01` §4.4) and from classpath descriptors (for development). Installing one means copying the jar and restarting. There is no unload or hot reload | Unloading classloaders while lasers are live is fragile. A restart is honest |
| OD-5 | A driver only ever gets a frame after the provider (projector effects, then Z1's safety stage, `multi_engine.clj:109-124`) **and** `sanitize-frame` | Safety can't depend on add-on code |
| OD-6 | The core keeps pacing. A device-paced DAC adds `:ready?`, and the core waits for it within one frame interval, or else skips that tick | Each driver would otherwise have to redo keepalive, stats and blanking |
| OD-7 | A built-in `:lsps` driver: our own documented UDP point protocol (`01` §3) | It is the second real driver, the reference for add-on authors, and a bridge to out-of-process drivers |
| OD-8 | No add-on lives in this repo. This repo has the contract, the loader, `docs/OUTPUT_DRIVERS.md` and a tiny test fixture add-on under `test-resources/` that is never shipped | Your answer (Q-4). Native USB dependencies stay out of the core jar and its CI. D5 checks the contract against a real add-on built elsewhere |
| OD-9 | Driver ids: built-ins are unqualified (`:idn`, `:lsps`), add-ons must be qualified (`:acme/nova`) | Add-ons can't collide with the built-ins or with each other's namespaces |
| OD-10 | A projector whose driver isn't installed is kept as it is: it loads, saves and round-trips unchanged. Its status says `Output driver :acme/nova is not installed` | Opening a show on another machine must never lose its setup |
| OD-11 | A projector's `:driver` is set once, when Scan or Add Manual creates it, and never changes. There is no UI to change it. `:projectors/update-settings` (`projector.clj:272-297`, which `merge`s whatever it is given, `:297`) and `:projectors/update-driver-config` drop any `:driver` key. The settings panel shows the driver as a read-only label | Your answer (Q-6): a projector is registered by how the app reaches it. Another connection is another projector |
| OD-12 | Cross-platform by construction (`01` §4.4): paths built with `java.io.File` and `java.nio.file`, never by joining strings; jar URLs from `File.toURI`; locked jars swapped at the next start; hidden and AppleDouble files ignored; Open folder falls back on Linux; add-ons may declare the platforms they support. The add-on tests run on macOS and Windows in CI as well as Linux | Your answer (Q-2). Unit tests only run on Ubuntu today (`.github/workflows/build-and-test.yml:147-148`). The macOS and Windows job runs only UI tests (`:55-110`) |
| OD-13 | What the first real add-on (a third-party DAC add-on, USB and network, built in its own repo) found missing goes into **API 1** as optional keys, with no version bump, since API 1 isn't released: `:reopen?` and `:busy?` in an error's `ex-data`, `:status`, ctx's `:cancelled?`, `api/log`, the `:lit?` encoder source, and (for D4) `:broadcast-addresses`. Driver-owned threads are allowed by the contract's text alone. The built-in drivers don't change (`02` § 1D) | A driver that doesn't use them behaves exactly as before; every add-on built against API 1 so far keeps working, and a second API version for things no one has shipped against would only add a check |

## 5. State placement

| Key / path | Lives in | Written by | Undo / dirty? |
|---|---|---|---|
| `[:projectors id :driver]` NEW (absent = `:idn`) | document (`hardware.edn`, `state/persistent.clj:74-78`) | only when the projector is created: `:projectors/add-manual` (`events/handlers/projector.clj:227-243`) or `:projectors/scan-complete` (`:157-184`, backfill). Never changed afterwards (OD-11) | add: yes/yes. Scan: no/no (backfill, as today `:183-184`) |
| `[:projectors id :driver-config]` NEW (map, opaque to the core) | document | add-manual; NEW `:projectors/update-driver-config`; scan (backfill) | yes/yes, except scan |
| `[:projectors id :stable-id]` (existing, Z2, `projector.clj:98`) gains two forms for other drivers: `[:manual driver projector-id]` (Add Manual, D2) and `[:device driver device-key output-id]` (Scan, D4) (`01` §5.3) | document | as `:driver`: set when the projector is created (D4: a scan's match may also write it, as `with-identity` does today, `projector.clj:137-141`) | as `:driver` |
| `[:backend :output :drivers]` NEW `{id {:id :name :transport :fields :capabilities :discover? :addon}}` | app state, `backend` defstate (`state/domains.clj:450-496`). `:backend` isn't a document root (`state/history.clj:52-54`), so it isn't saved, and New and Open keep it (`state/persistent.clj:181-202`) | NEW `:addons/loaded` handler (step 0) | no/no |
| `[:backend :output :addons]` NEW, a vector of add-on status (`01` §4.3) | app state | `:addons/loaded` (step 0) | no/no |
| `[:backend :output :dir]` NEW (D3), the add-ons folder's path | app state | `:addons/loaded` (D3: `app/load-addons`) | no/no |
| `[:backend :output :pending-installs]` NEW (D3), `[{:id :name :version :file :replaced?}]` | app state | `:addons/installed`; cleared by `:addons/loaded` | no/no |
| `[:projector-ui :discovered-devices]` (existing, `state/domains.clj:369`) | UI | scan-complete (`events/handlers/projector.clj:213-246`); entries carry `:driver`, and non-IDN ones have `:device-key` and `:outputs` | no/no |
| `[:projector-ui :expanded-devices]` (existing, `projector.clj:649-656`) | UI | Not used: no view reads it since Z2 replaced the discovery panel with the rig strip. D4 leaves it alone | no/no |
| `[:ui :dialogs :add-projector-manual]` dialog data (existing) | UI | `:ui/update-dialog-data`. Gains `:driver` and `:driver-config` text values | no/no |
| Driver registry `!drivers` NEW | runtime atom in `laser-show.output.driver` (step 0) | built-ins when their namespace loads (D1 `:idn`, D2 `:lsps`); `laser-show.output.addons/load-all!` on the startup thread (D3) | n/a |
| Add-on classloaders NEW | runtime atom in `laser-show.output.addons` | `load-all!` | n/a |
| Engine `:session` NEW | atom inside the engine map (output runtime registry, `backend/output_runtime.clj:71`) | the engine's streaming thread, when `:open` succeeds (C-7, `01` §1.1); cleared by `close!` | n/a |
| Engine stats `:last-attempt-ns`, `:opening-since-ns`, `:frames-skipped` NEW | engine `:stats` atom (`streaming_engine.clj:51-63`) | the streaming thread | n/a |
| Engine `:busy?`, `:driver-status`, `:status-warned?` NEW (1D) | atoms inside the engine map (`streaming_engine.clj:132-137`) | `:busy?` the streaming thread (`report-error!`); `:driver-status` the output-status thread (`poll-driver-status!`), already sanitized to plain EDN | n/a |
| `[:backend :streaming :status :projectors id]` gains `:busy?` and `:driver-info` (1D) | app state, through the existing `:output/status` | the output-status thread's `publish-status!` (`output_runtime.clj:315-327`) | no/no |
| Add-on jars | disk, the add-ons folder (`01` §4.4), next to `persistent/default-projects-dir` (`state/persistent.clj:26-28`) | NEW `:addons/install` effect | n/a |

Nothing is `swap!`ed from outside the event flow. Add-on load results come in as `:addons/loaded`, and runtime status as the existing `:output/status` (`backend/output_runtime.clj:338-351`).

## 6. Events and effects

| Event / effect key | Exists? | Dispatched from (file:line) | Pure? |
|---|---|---|---|
| `:addons/loaded` | NEW (step 0) | `app/start!` right after the dispatch fn is set (`app.clj:213-221`), before the startup scan (`:237`), so the scan sees every driver; `app/start-test!` after mounting (`:350-351`) | handler pure |
| `:addons/pick-install` | NEW | Settings → Add-ons → "Install add-on…" | pure; returns `:fx/show-file-chooser` (exists, `events/core.clj:353-410`) |
| `:addons/install-picked` | NEW | the file chooser's `:on-result` | pure; returns `:addons/install` |
| `:addons/install` (effect) | NEW | the handler above | effect in a future: copies the jar, then dispatches `:addons/installed` or `:addons/install-failed` |
| `:addons/installed`, `:addons/install-failed` | NEW | the `:addons/install` effect | pure; `h/show-notice` (`events/helpers.clj:109`): "Installed Acme Nova 0.3.0. Restart Laser Show to load it." |
| `:addons/open-folder` → `:system/open-folder` (effect) | NEW (`:system/open-url`, `events/core.clj:310-320`, is for URLs) | Settings button | handler pure. The effect creates the folder if it is missing, then tries `Desktop/open`, then `xdg-open` on Linux. If both fail, it dispatches its `:on-failed`, `:addons/open-folder-failed {:path}` (D3), whose handler shows the full path in a notice (`01` §4.4) |
| `:projectors/add-manual` | exists (`projector.clj:227-243`); gains `:driver`, `:driver-config` | `views/dialogs/add_projector_manual.clj:89` | pure |
| `:projectors/update-driver-config` | NEW `{:projector-id :key :value}` | the generic driver fields in `projector-basic-settings` (`views/tabs/projectors.clj:54-143`) | pure. Validated against the field spec in `[:backend :output :drivers]`. The show lock refuses it, like `:projectors/update-settings` (it joins `show_lock.clj:24-53`) |
| `:projectors/scan` (effect) | exists (`events/core.clj:237`); runs IDN and every driver's discovery (`output/discovery.clj:175`) | `handle-projectors-scan-network` (`events/handlers/projector.clj:127`), from Scan (`views/components/rig_strip.clj:127`) and startup (`app.clj:257`) | effect (future) |
| `:projectors/scan-complete` | exists (`events/handlers/projector.clj:213`); devices carry `:driver`, and the event carries `:warnings` | the scan effect (`events/core.clj:237-246`) | pure |
| `:projectors/enable-device` | D4 `{:driver :device-key :enabled?}`: sets every output of this driver's device on or off (`events/handlers/projector.clj:272`) | the card menu (`views/components/rig_strip.clj:358-365`) | pure. The show lock refuses it, as it does `enable-all-by-ip` (`events/show_lock.clj:46`) |
| `:multi-engine/refresh` | exists (`events/core.clj:538`) | `wrap-output-sync` (`output_sync.clj:25-34`). A change of `:driver` or `:driver-config` now changes the fingerprint | effect |
| `:output/status` | exists (`connection.clj:28-34`) | the output-status thread (`output_runtime.clj:338-351`); new states `driver missing` and `not responding` | pure |

## 7. Fit with other plans

Checked against `6f6f4df`, the merge of `master` at `c067030` (zones Z0–Z3 and ZR) into `output-drivers` (step 0, D1, 1B–1D). Z3 and ZR were checked on their branches first (`zones-z3` at `af0fd6c`, `zones-zr` at `74eaaf2`); the rows below say what the merge did. Two later merges of `master` are the last two rows: `be72dd0` (zones Z4 and Decimate) and `6f96aaf` (the Projectors tab's layout pass and Decimate's follow-up, `master` at `4b19812`). The Z3 row's citations of `output_runtime.clj` stay at `6f6f4df`; at `6f96aaf` they are `stuck?` `:201-208`, `load-of` `:214-233`, `engine-status` `:235-262` (`:low?`/`:load` at `:261`), `fingerprint` `:44-51`.

| Plan | Overlap | How it fits |
|---|---|---|
| Zones Z1 safety (`plans/2026-11-19-zones/03-phase-z1-safety.md`), **on master** | Its safety stage is the last step of `multi_engine/projector-frame` (`guard-safety`, `multi_engine.clj:109-124`), the test pattern included (`:145-156`). Its questions ask only for a projector that is outputting, which is `output-runtime/wanted` (`events/safety_guard.clj:13-18`). The show lock refuses projector edits by name (`events/show_lock.clj:24-53`) | No conflict. OD-5 means every driver gets Z1's output, and its invariant "only `sanitize-frame` and the encoder run after the stage" stays true, because a driver *is* the encoder. `01` §5.5 keeps a non-IDN projector in `wanted`, so Z1 asks before widening its zone while it is live. D2 adds `:projectors/update-driver-config` to the show lock's `blocked-events`. The `:addons/*` events aren't blocked: an install only copies a file |
| Zones Z2 rig strip and stable ids (`04-phase-z2-rig-strip-and-groups.md`), **on master** | Every projector has a `:stable-id` (`make-projector-config`, `projector.clj:98`; `rig/settled-ids`, `rig.clj:586-599`; migration, `state/migration.clj:29-35`). A scan matches outputs with `rig/match-discovered` (`rig.clj:616-680`), which drops devices without an `:address`. Scanned ids come from `rig/unique-projector-id` (`:575-581`). The discovery panel is gone: every found output is a card on the rig strip (`subs/rig-cards`, `subs.clj:857-889`; `views/components/rig_strip.clj`), keyed by `[host service-id]` | D4 no longer waits. **D2** has to give the projectors it creates a stable id that isn't a host (`[:manual driver projector-id]`, `01` §5.3), and has to teach `rig/identity-label` and `rig-cards` (address line, "found", the outputs of one device, Identify) about drivers. **D4** extends `match-discovered` with the `[:device driver device-key output-id]` form, rather than adding a matcher of its own. The discovery-panel work the plan had for D4 (grouping, expand keys, `set-output-enabled`) is dropped: the cards already do it |
| Zones Z3 routing core (`05-phase-z3-routing-core.md`), **on master** (`cb28966..298c253`), **merged** at `6f6f4df` | It rewrote the provider half of `multi_engine.clj`: `show-frame` (`:188-212`) composes from the tick's `:routing` (`routing/compose.clj`), then `animation/fit_guard.clj`, then Z1's stage, and the provider (`:214-249`) smooths the fit record and keeps each frame for the card's thumbnail. It didn't touch `create-engine-for-projector` (`:252-279`) or `create-engines`. On its branch it read `:fit` with `:tick :at-ns` from the frame's metadata in the old IDN-only loop and kept it in the stats (`:fit nil` in `initial-stats`). In `output_runtime.clj`, `engine-status` gained `:low?` and `:load` (`load-of`, `:217-232`). It changed only `fingerprint`'s docstring (names, groups, the rig's order, fit and safety settings restart nothing), not its keys, and not `wanted`. `rig/routing` clamps each laser's scan rate with `stream/clamp-scan-rate` for the guard's budget (`routing/rig.clj:406`) | As planned, the merge put Z3's metadata read and stats update into D1's generic loop: `{frame-tick :tick at-ns :at-ns fit :fit} (meta frame)` (`streaming_engine.clj:402`) and `:fit` stored with each send's stats (`:414-416`); a tick a device-paced driver isn't ready for leaves `:fit` alone. Z3's `frame-point-count`, an argument of the old IDN `send-frame!`, stayed out (the IDN driver counts its own points), so nothing IDN-specific came back into the loop. `engine-status` (`output_runtime.clj:234-261`) keeps C-5's `stuck?` (`:204-212`), 1D's `:busy?`, driver `:error`/`:warning` and `:driver-info`, and adds Z3's `:low?` and `:load` in the streaming branch (`:260`). `fingerprint` (`:44-51`) keeps `01` §5.5's keys with both docstrings. D2 did: a driver's `:capabilities :scan-rate` clamps the stored `:scan-rate` when it is edited (`driver/clamp-scan-rate`, in `:projectors/update-settings` and the Scan rate field), and a new projector starts at its `:default`, so Z3's clamp in `rig/routing` sees a value already in range |
| Zones ZR rig file (`09-phase-zr-rig-file.md`), **on master** (`5ec8979..af7ba97`), **merged** at `6f6f4df` | `state/rig_file.clj` and `state/rig_store.clj`: `~/LaserShowProjects/rig.edn` holds `[:projectors] [:virtual-projectors] [:rig] [:chains :projector-effects]` whole (`rig-paths`, `rig_file.clj:26-30`). `state/persistent.clj` marks those paths `:owner :rig` (`:74-78`). `app/start!` and `start-test!` were restructured: `starter-state`, `load-rig` (`app.clj:136-169`) before `init-state!`, and `after-rig-loaded!` (`:171-178`) for its notice after mounting. `rig-file/pin-map` (`rig_file.clj:81-100`) gives a show's laser whose id is taken here a new id with `rig/unique-projector-id` from `:host` and `:service-id`; `bring-lasers` (`:189-211`) keeps only `identity-keys` (`:146-149`: `:host :port :service-id :service-name :unit-id :stable-id`) of a laser already here. The `rig-file` defstate (`state/domains.clj:511`). In `events/core.clj` a `:rig/write` effect (`:544`) and the rig autosave wrapper (`:494`). `test/laser_show/test_runner.clj` points `laser-show.rig-file` at a temp folder | `:driver`, `:driver-config` and `:stable-id` travel with the rig file, so OD-10's round trip has to hold for `rig.edn` as well as for the show's zip (D2's `unknown-driver-projector-round-trips-test` covers both). The merge kept ZR's `start!` and `start-test!`: step 0's `dispatch-drivers-loaded!` (`app.clj:128-134`) runs in `start!` right after the dispatch fn is set (`:213`, the dispatch at `:221`) and before the startup scan (`:237`), and in `start-test!` right after the dispatch fn is set (`:351`), before `after-rig-loaded!`. D3's `load-all!` goes before `output-runtime/open!` (`:206`). Step 0's `[:backend :output]` sits after Z3's `:cue-warnings` in the `backend` defstate (`state/domains.clj:450-496`), and D3's two effects go next to ZR's `:rig/write`. The test runner now points `laser-show.addons-dir` at a temp folder too (`4fc172f`), so no test reaches the real `~/LaserShowProjects/addons`. **D2 did** (branch `output-d2-ui`, `02` § D2 as built): `pin-map`'s new id for a taken id of a hostless projector is the show's own id with `-2`, `-3` … (`free-id`), not `:projector--0`; `:driver` and `:driver-config` are connection data, so they joined `bring-lasers`' `identity-keys`, and a laser here keeps its own (one it lacks, such as an IDN laser's `:driver`, isn't taken from the show either). Add-ons stay per machine, like the rig, and their folder sits next to `rig.edn` |
| Zones Z4 zones, maps and targets (`06-phase-z4-zones-maps-targets.md`) and Decimate (`12-decimate-effect.md`), **on master**, **merged** at `be72dd0` | Z4's routing refs, zone maps and the show lock's new events; Decimate, a calibration effect that thins a laser's points to a budget (`animation/effects/points.clj`, `fit_guard.clj`) | Only tests both sides appended at the ends of `test/laser_show/state/persistent_test.clj` and `subs_test.clj` conflicted; both were kept. No driver code changed: Decimate runs in the provider, before Z1's stage, so every driver gets its output (OD-5) |
| The Projectors tab's layout pass (`13-projectors-layout.md`, `77230f1..0a1c219`) and Decimate's follow-up (`12` Q-D-1 B, Q-D-2 B, `9585cae`, `114e49e`), **on master**, **merged** at `6f96aaf` | The layout pass rebuilt `projector-config-panel` (`views/tabs/projectors.clj:660`) as three `inspector-column`s (`:642`) that each scroll, put a divider between the strip and the inspector (`projectors-tab` `:846`, `default-divider` `:779-793`), and split the strip's size into a min (`rig-strip/cards-height`) and a max (`strip-height`). The follow-up added `:effect-points` to `load-of` (`output_runtime.clj:214-233`) and changed the cue ⚠ (`routing/compose.clj`, `load_warning.clj`) | `engine-status` merged clean: D1's stuck check, 1D's `:busy?`, the driver's `:error`/`:warning` and `:driver-info`, the not-installed text and Z3's `:low?`/`:load` (now with `:effect-points`) are all there; both docstrings kept. D2's settings grid (`projector-basic-settings`, `:176`) sits in the left column unchanged, its labels and fields on one x. D2's extra address line went into `cards-height` (`rig_strip.clj:498-507`), so the min and the max both grow by it. A card's status and driver lines wrap (live pass), so a card can be taller than any guess, and the divider would have cut it: NEW `fit-to-cards!` (`:515-545`) sets the strip's min and max from its laid-out cards and rows after each layout pass, and `follow-default-divider!` (`projectors.clj:800-818`) puts the divider back at its default when they change, until it is moved (`projectors-layout-test/a-card-that-grows-is-never-cut-test`) |
| Review 04 LE2 (one IDN-RT link per DAC) | IDN sessions per host | Stays inside `laser-show.idn.driver`. The contract lets a driver share a link between sessions (counting its users in `:open`/`:close!`) |

## 8. Risks

- **Add-on code on the streaming thread.** A driver that blocks would stall its projector, a blocking `:open` would hold up the lifecycle thread (and every queued command behind it, Stop included) if it ran there, and a blocking `:close!` would stall Stop. Mitigations: the core never calls into a driver on the lifecycle thread, so `:open` runs on the engine's own thread and is retried there when it fails (C-7); `:close!` runs on its own daemon thread with a time limit; the status shows "not responding" after `STUCK_MS`, for a stuck `:open` as for a stuck send (C-5); each engine already has its own thread, so other projectors aren't affected (`01` §1.4). D1's tests cover each case (`02` D1).
- **A `:status` that blocks** (1D). One output-status thread asks every engine's driver in turn, so a `:status` that blocks would freeze every projector's status (never its frames, and never Stop). Mitigations: the contract says it must only read atoms (`01` C-9); it is only asked while its engine runs with a session; a throw is caught. A time limit would need a thread per call (or per engine) four times a second; not worth it while the drivers are ours to review. Revisit if an add-on shows the problem.
- **Reopen loops** (1D). A driver that throws `{:reopen? true}` on its first frame every time would close and reopen forever. Each cycle waits `REOPEN_MS` before the next `:open`, so it is at most one open and one close a second, the same cadence as a failing `:open`, and the status shows the error throughout.
- **Driver-owned threads** (1D). The core can't check that a driver's own thread never sends lit content (`01` C-11) or never touches app state; it is a contract statement for reviewers. Its frames still only come from `:send-frame!`, which only gets what passed Z1 and `sanitize-frame`.
- **Classloading.** Clojure finds add-on namespaces through the thread's context classloader. The loader sets it for `require`, and the core sets it again on every call into a driver from the streaming, lifecycle and scan threads. D3's tests load from a real jar and run on another thread (`02` D3).
- **Startup time** while add-ons compile from source: D3 logs the time each add-on takes to load. Measured at D3: the sample fixture 0–10 ms, the first third-party add-on (two drivers, from source) 138 ms on an Apple Silicon Mac.
- **The extra call per frame for IDN**: one map lookup and one function call per frame at 60 fps per projector. Measured in D1 (3adc96f): the seam costs about 60 ns a frame (a no-op driver), and the profiler's IDN streaming time (`record-idn-streaming!`, 2 engines on loopback at 60 fps) was the same before and after within the shared machine's noise (`02` D1).
- **Platform differences in the loader.** These include Windows file locks on loaded jars, path separators and drive letters, non-ASCII user names, macOS `._*.jar` files on external drives, Linux desktops without `Desktop/open`, and add-ons whose native libraries only exist for some platforms. `01` §4.4 handles each one, and D3's tests run on all three OSes (OD-12).

## 9. Questions

Answered 2026-11-20:

| # | Question | Answer |
|---|---|---|
| Q-1 | Licence of closed add-ons (the repo is GPLv3) | Not a concern for now. Nothing in this plan depends on it. LSPS stays (OD-7) for its own reasons |
| Q-2 | The add-ons folder | `~/LaserShowProjects/addons/`, made to work well on every platform: OD-12, `01` §4.4 |
| Q-3 | Restart to load, or hot loading | Restart to load (OD-4) |
| Q-4 | Where the USB hardware add-on lives | In its own repo, like every add-on (OD-8). Its USB library is that repo's choice. D5 here only checks the contract against it |
| Q-5 | The LSPS default port, and discovery | Port 7266. No discovery in v1 |
| Q-6 | Changing an existing projector's driver | No: the driver is fixed when the projector is created (OD-11) |
| Q-7 | A trust note next to Install add-on… | Left out: no note |

## 10. Alternatives considered

- **A Java SPI (`ServiceLoader` plus an interface).** It would need a published API jar and a Java build for authors, and it doesn't fit maps of functions. It could be added later as an adapter that turns an interface instance into a driver map.
- **`defprotocol`.** Redefining it on a dev reload breaks live sessions. It gives nothing a map doesn't.
- **Moving IDN's keys into `:driver-config`.** That means a migration of every saved show and a rewrite of every zones-plan citation, for no user gain. It could be done later in one step if it's ever wanted.
- **Only out-of-process drivers.** That isn't what was asked for (importable add-ons), and USB drivers would gain a process hop. Kept as an option through LSPS.
- **Hot-loading or unloading jars.** Classloader leaks, and a live laser's code path changing under it.
- **A second engine for new drivers alongside IDN.** That means two loops to keep safe, and two sets of tests.
- **Drivers that own their thread and pacing.** Each driver would have to reimplement keepalive, stats, error blanking and stop. `:ready?` covers device-paced DACs.
- **Switching IDN's encoder (`idn/stream.clj`) to the new point encoder.** It's tuned and covered by the wire tests. Revisit after a benchmark.
- **Letting a projector change its driver and keep its calibration.** Rejected (OD-11): a projector *is* the way the app reaches the laser. Calibration can already be copied between projectors with the projector-effects copy and paste.
- **Interrupting the streaming thread to stop a stuck `:open`** (the first third-party add-on's G3 proposal, with a ctx `:deadline-ns`). Rejected (1D): an interrupt closes any NIO channel the thread is using and can break native calls such as libusb's; a driver checks ctx's `:cancelled?` between its own steps instead (`01` C-10), and each step stays below `STUCK_MS`. A deadline would also tie the driver to the core's timeouts.
- **A separate `:session-info`, read once after `:open`** (the add-on's G5). Folded into `:status`'s `:info` (1D): the same data, kept current (a rate that changes, a buffer level), one key instead of two. Clamping the projector's scan-rate field to a device's reported range is left for wave 2's UI.
- **Running `:on-error` and the blanking frame before a reopen** (the add-on's G1 proposal). Rejected (1D): the session is dead, so a blank to it can't arrive and `:on-error` has nothing to repair; both would only delay the close and the new open.
- **The OS's app-data folder for add-ons** (`%APPDATA%`, `~/Library/Application Support`, `$XDG_DATA_HOME`). Rejected (Q-2): it's harder to find, and `~/LaserShowProjects` already exists on every platform. The `laser-show.addons-dir` JVM property can point anywhere for anyone who wants otherwise.
