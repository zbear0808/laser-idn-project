# 08 — Music-aware and audio-reactive shows

Research for the live laser controller (several IDN projectors, tap tempo and Ableton Link today, no audio analysis yet). Date: 2026-11-21.

## Summary

Musical time and musical sound are two different problems. Time means beats, bars and phrases. The app already has a solid clock (tap and Link), so the cheapest big win is to count bars and phrases on that clock and add a one-press "this is beat 1" fix. Sound means energy, hits, bass/mid/high and drops. That needs a live audio input, and simple DSP (band levels, onsets, loudness) handles it well. Live beat tracking is fine for tempo but weak for downbeats: the best online systems reach about 75–80% beat F1 but only 45–56% downbeat F1. So audio should correct the clock, not replace it. Real-time stem separation exists at 23 ms, but quality is far below offline Demucs; real stems from a DAW or band desk are much better. The richest source is the DJ's own gear: rekordbox phrase analysis (Intro/Up/Down/Chorus/Outro, with fills) can be read off a Pro DJ Link network through Deep Symmetry's Beat Link Trigger. Pros mix automation with hand control: Prydz runs "no pre-sequenced elements", deadmau5 mixes timecode with live cueing, and SoundSwitch tells users to keep auto loops simple. Good UX makes the analysis visible (with confidence), easy to correct, and easy to turn down.

---

## 1. Real-time music information retrieval (MIR)

### 1.1 Beat and downbeat tracking

- **BeatNet** (ISMIR 2021) does joint beat, downbeat, tempo and meter tracking. It runs in four modes, including "stream" from a microphone. It uses a causal CRNN plus particle filtering. https://github.com/mjhydri/BeatNet · https://arxiv.org/abs/2108.03576
- Online results on GTZAN (70 ms tolerance) from the BeatNet+ paper: BeatNet scores beat F1 75.4 and downbeat F1 46.7 at 20 ms latency. BeatNet+ scores 80.6 and 56.5 at 20 ms. BEAST scores 80.0 and 52.2 at 46 ms. Offline systems reach about 88.5 (beat) and 71–76 (downbeat). https://transactions.ismir.net/articles/10.5334/tismir.198
- BEAST trades latency for accuracy. It gets 80.0 beat F1 at 46 ms and 83.7 at 743 ms. https://arxiv.org/html/2312.17156v3
- A real-time PLP tracker claims "zero latency and enhanced controllability", including the choice of pulse level. https://transactions.ismir.net/articles/10.5334/tismir.189
- **madmom** `DBNBeatTracker online` works on live input. One user reported a visible delay of "maybe 100–200 ms" (not verified). The maintainer suggests raising `--min_bpm` to force double-time beats. https://groups.google.com/g/madmom-users/c/yYtp6Y43yWQ · https://github.com/CPJKU/madmom/issues/487
- **Takeaway:** live beat phase is usable. Live downbeat is a coin flip. Treat audio-derived downbeats as suggestions.

### 1.2 Onsets, spectral flux, novelty

- aubio provides causal onset detection. Its methods include energy, hfc, complex, phase, specdiff, kl, mkl and specflux. It has a minimum inter-onset interval (default 12 ms), a silence gate (−70 dB) and a settable delay compensation. https://aubio.org/manual/latest/cli.html · https://aubio.org/doc/latest/onset_8h.html
- Essentia has streaming `SuperFluxExtractor` and `OnsetDetection`. https://essentia.upf.edu/algorithms_reference.html
- Spectral flux is the standard novelty curve: you sum the positive spectral change per frame. Per-band flux (for example, below 150 Hz) is a cheap "kick" detector. This is how BEYOND, Notch and Resolume pick out hits (see §2).

### 1.3 Energy and loudness

- EBU Tech 3341 "EBU Mode" defines three loudness measures. Momentary uses a 400 ms window. Short-term uses 3 s. Integrated is gated from start to stop. https://tech.ebu.ch/docs/tech/tech3341.pdf
- Essentia `LoudnessEBUR128` runs in streaming mode with a hop of 0.1 s or less. https://essentia.upf.edu/reference/streaming_LoudnessEBUR128.html
- For shows, use momentary loudness for swells and short-term loudness for "section energy". Integrated loudness is not useful live.

### 1.4 Tempo estimation and octave errors

- Essentia's `RhythmExtractor2013` "relies on statistics gathered over the whole music track, and therefore it is not suited for real-time detection." Its confidence scale reads: 1.5–3.5 is good, about 80% AMLt. https://essentia.upf.edu/tutorial_rhythm_beatdetection.html · https://essentia.upf.edu/reference/streaming_BeatTrackerMultiFeature.html
- VDMX 6 wrote a new BPM detector from scratch. Users report it shows half BPM after track changes, and the workaround is a "Double Rate Detection" key. https://discourse.vidvox.net/t/clock-and-bpm-detect/2424
- Pangolin's PangoBeats 1.1 (BEYOND 5.8, Sep 2026) has these states: Locked, Coasting (holds the last tempo when confidence drops, e.g. in a bridge), Guided (agrees with user taps) and Input Stalled. It has three user inputs: tap, Clear and New Track. https://wiki.pangolin.com/doku.php?id=beyond:pangobeats

### 1.5 Drop and build detection

- Yadati et al., ISMIR 2014, built the first drop detector. Chroma-based segmentation puts a boundary within 2.5 s of the true drop on average, and misses fewer than 8% of drops. https://archives.ismir.net/ismir2014/paper/000297.pdf
- In MediaEval 2014, drop labelling reached F1 of about 0.70. https://ceur-ws.org/Vol-1263/mediaeval2014_submission_88.pdf
- An open-source XGBoost drop detector uses bass-boost envelope rises, transient snapping, a check for 4/8/16/32-bar grid alignment, and bass ratio. It claims F1 0.95 (not verified). https://github.com/felixmeyer6/drop-detector
- EDMFormer (2026) and its EDM-98 dataset use the labels intro, build-up, drop, breakdown and outro. The authors say generic structure models "perform poorly on EDM". https://arxiv.org/html/2603.08759
- Lightjams claims real-time "break, build-up (tension) and drop" detection (not verified for accuracy). https://www.lightjams.com/musicToDMX.html/osc.html
- Synesthesia scenes can define `HARD_TRANSITIONS`, which fire "every time a transition, or drop is detected". https://synesthesia.live/docs/tutorials/first_scene.html
- **Key point:** live drop detection can only confirm a drop after it lands. A build (riser, rising highs, kick removed) can be detected *before* the drop. Combine that with the phrase grid to predict the drop.

### 1.6 Stem separation

- **Demucs v4** (HT Demucs) is MIT code, but its weights are "provided only for scientific purposes". It is not a streaming model. The original repo was archived on 2025-01-01. https://github.com/facebookresearch/demucs · https://github.com/facebookresearch/demucs/issues/327
- **Real-time models** lose a lot of quality. At 23 ms latency, HS-TasNet scores 4.65 dB SDR, X-UMX 3.93 dB and RT-STT 5.17 dB. Offline Demucs v4 scores 7.52 dB on the same MUSDB-only test (9.0 dB with extra training data). https://arxiv.org/html/2402.17701 · https://arxiv.org/html/2511.13146
- Band-SCNet reaches 7.79 dB at 92 ms latency. https://www.isca-archive.org/interspeech_2025/yang25d_interspeech.pdf
- Products: the GPU Audio SDK ships HS-TasNet at 512 samples (https://sonicstate.com/news/2026/02/02/real-time-source-separation-on-gpu), and AudioShake sells an on-device real-time SDK (https://www.audioshake.ai/products/sdk).
- **DJ software:** Serato Stems (vocals, melody, bass, drums; "requires a lot of CPU"), rekordbox (3 or 4 stems), djay Neural Mix, VirtualDJ, and Traktor (real-time stems since 2025). All of them separate *tracks loaded in the app*, not an arbitrary live input. https://support.serato.com/hc/en-us/articles/7645256113551-Stems · https://rekordbox.com/en/faq-single/what-is-stems-function/ · https://www.digitaldjtips.com/best-sounding-stems-2025
- **For visuals, bleed matters less than for audio.** A smeared drum stem still gives a usable kick envelope. This is our judgement, not verified by testing.

### 1.7 Music structure, energy and key

- **rekordbox phrase analysis** uses these phrase types: Intro, Up (build), Down (breakdown), Chorus, Bridge, Verse and Outro. It marks a "Fill in" of up to 4 beats at the end of an Intro, Up or Chorus. Users can edit phrases. https://cdn.rekordbox.com/files/20241203210634/rekordbox7.0.5_Phrase_Edit_operation_guide_EN.pdf
- rekordbox also gives each track a **Mood**: High, Mid or Low. Mood comes from tempo, rhythm, kick and sound density. High mood uses Intro/Up/Down/Chorus/Outro. Mid and Low use Verse/Bridge/Chorus. https://rekordbox.com/en/support/faq/lighting-7 · https://djl-analysis.deepsymmetry.org/rekordbox-export-analysis/anlz.html
- **Mixed In Key** gives an Energy Level from 1 to 10. Mixed In Key holds a patent on it. It is driven mostly by high-frequency content and transient density. It can also mark energy changes within a track. https://mixedinkey.com/workflows/use-energy-level-detection · https://dj.studio/blog/mixed-in-key-integration
- **SoundSwitch Autoscript** detects phrases: Intro, Outro, Main 1, Main 2, Middle and Bridge. https://www.soundswitch.com/software
- **Spotify Audio Analysis** (bars, beats, sections and segments, each with a confidence value) has been closed to new apps since 27 Nov 2024. https://developer.spotify.com/blog/2024-11-27-changes-to-the-web-api
- **Key and chord detection:** madmom has a key CNN and chord models (references [15]–[18] in its README). Relative major/minor confusion is a common error (https://parrser.com/spotify-collapse). For lasers, key would only drive palette choice, so it is low value.

### 1.8 Library table

| Name | Language | Real-time? | Latency | Licence |
|---|---|---|---|---|
| aubio | C (+Python) | Yes, causal by design | About 1 hop (e.g. 256–512 samples) plus window | GPL-3; commercial licence "contact the author" |
| madmom | Python/Cython | Online mode for beats and onsets | 100 fps frames; users report 100–200 ms (not verified) | BSD code; **models CC BY-NC-SA** |
| BeatNet / BeatNet+ | Python/PyTorch | Yes (stream mode) | 20 ms (algorithmic) | CC BY 4.0 |
| BEAST | Python | Yes | 46–743 ms | Not verified |
| Essentia (+ essentia.js) | C++, Python, JS/WASM | Streaming onsets and loudness; RhythmExtractor2013 is not real-time | Depends on algorithm | AGPL-3 (commercial licence from MTG) |
| librosa | Python | No (batch API) | n/a | ISC |
| Demucs v4 | Python | No | About 7.8 s context | MIT code; weights research-only |
| Spleeter | Python/TF | No | Offline | MIT (weights not verified) |
| Open-Unmix / X-UMX | Python | Offline; a 23 ms variant exists in research | 8 s offline | MIT code (weights not verified) |
| HS-TasNet | Python | Yes | 23 ms | MIT (lucidrains implementation) |
| Band-SCNet | Research | Yes | 92 ms | Not verified |

Licensing matters for a commercial desktop app. aubio is GPL. Essentia is AGPL. madmom's models are non-commercial. Demucs weights are research-only.

---

## 2. Audio-reactive tools and how their UX exposes music

- **Synesthesia** gives each band (whole, bass, mid, midhigh, high) four variables. *Level* is smoothed loudness. *Hits* spike on transients. *Time* is a clock that only advances while the band is loud. *Presence* is a slow "is there bass right now". It also has `OnBeat`, `ToggleOnBeat`, `RandomOnBeat`, `BPM` and `BPMConfidence` (0–1), all sendable over OSC. Its best-practice guide warns that "too much audio reactivity can be a nuisance" and suggests a toggle per scene. https://www.synesthesia.live/docs/ssf/audio_uniforms.html · https://synesthesia.live/docs/ssf/best_practices.html
- **Resolume** lets any parameter use External, Composition or Clip/Layer FFT. You pick the L, M or H band, set in and out points on the spectrum, and tune Gain and Fall. Direction modes are low→high (>), high→low (<), or drive speed (−/+). https://resolume.com/support/parameter-animation
- **TouchDesigner audioAnalysis** outputs low, mid, high, kick, snare, rhythm, spectral centroid and slow/fast spectral density. A community add-on adds "Count Analysis", which triggers on every 4th, 8th or 16th kick. https://docs.derivative.ca/Palette:audioAnalysis · https://derivative.ca/community-post/asset/audio-analysis-20-extended-audio-reactive-components/73946
- **Notch** has a Sound Modifier, where bands double in frequency and you set attack, decay and smoothing. Its Sound FFT Modifier lets you *draw a rectangle* on the live spectrum to make a trigger. Notch advises using a dedicated audio machine and feeding audio into the Block. https://manual.notch.one/2026.2/en/docs/learning/working-with-audio
- **VDMX** has Audio Analysis filters you drag on a spectrum, each published as a data source. Its Clock has BPM Detection, plus a "→|" button that skips to the next downbeat (a true "1" button). It also has MIDI clock and MTC follow. https://docs.vidvox.net/vdmx/vdmx_plugins
- **Magic Music Visuals** supports multichannel inputs, so each instrument can drive different modules. https://magicmusicvisuals.com/
- **Lightjams** offers 120 display bands (from a 2048-band FFT), a "beat" checkbox, BPM, BAR, break, build-up, drop and energy. It takes up to 8 inputs, supports ASIO, and has Link. Its `onbeat(trigger(...), 8)` fires every N beats. Forum users ask how to "reset beat 1 from a foot pedal". https://www.lightjams.com/commandline.html · https://groups.google.com/g/lightjams/c/ZJDh0iCG3Uo
- **SoundSwitch:**
  - *Autoloops* are 8–128-bar loops synced to the DJ beatgrid, with up to 128 slots in 4 banks. The vendor's own advice: "Solid color loops, color transition effects, and slow intensity fades tend to work well". https://support.soundswitch.com/en/support/solutions/articles/69000847100-autoloops-explained
  - Live-audio BPM detection arrived in 2.9, with "Blackout" and "Pause" options for when detection fails.
  - Version 2.11 (Sep 2026) integrates with rekordbox and adds a PRO DJ LINK Bridge. https://cdn.inmusicbrands.com/soundswitch/files/SoundSwitch%20Release%20Notes.pdf
- **rekordbox LIGHTING mode** uses Macro Mapping to assign a scene to each phrase type, per Mood and Bank. It has a *Default scene* "when no phrase information is received" and an Ambient mode for when all players stop. **Lasers are not supported.** https://cdn.rekordbox.com/files/20260825084250/rekordbox-7219_lighting_operation_guide_EN.pdf · https://rekordbox.com/en/support/faq/lighting-7
- **Pangolin BEYOND** runs a 1024-sample FFT into 512 bands, each with a peak detector that raises "Audio Events". Its manual argues that events are more controllable than raw FFT: "the audio simply becomes the starting point". PangoScript has `WaitForAudioBeat`. A slider mixes "time-based and channel-based motion". https://wiki.pangolin.com/doku.php?id=beyond:realtime_audio
- **LaserCube** (firmware 2.2) has 197 sound-reactive visualizers fed from a mic or line in, with gain from 0 to 50 dB. It has Tap BPM plus a "Sync Beat" button for beat 1. LaserOS adds AutoBPM and Link. Its FAQ says loopback beats a mic "in both latency and accuracy". https://wiki.laseros.com/academy/control-modes/ · https://www.xlaser.com/pages/lasercube-faq
- **Philips Hue + Spotify** scripts lights from Spotify track metadata, with no microphone. https://www.signify.com/global/our-company/news/press-releases/2021/20210901-philips-hue-spotify-first-deep-integration-of-lighting-and-music
- **Nanoleaf Rhythm** processes an on-device mic locally (low latency), but reacts to coughs and doors too. https://www.techhive.com/article/583051/nanoleaf-rhythm-review.html
- **Govee:** not researched.
- **MilkDrop** (2001) exposes `bass`/`mid`/`treb` (1 = normal) plus damped `_att` versions. Its beat detector is a threshold that jumps on a hit and decays, and it drives "hard cuts" between presets. https://www.geisswerks.com/milkdrop/milkdrop_preset_authoring.html

**UX patterns that recur across tools:**
- The user picks a band on a visible spectrum.
- Gain, attack and fall are the main tuning controls.
- Hits are separate from levels.
- Some clocks advance with loudness (Synesthesia Time).
- Some tools show confidence.
- A "1" or resync button.
- A per-scene "audio on/off" switch.

---

## 3. Sync and timing infrastructure

- **Ableton Link** shares tempo, beat and phase. A *quantum* (for example 4 beats) aligns bar phase across peers. `requestBeatAtTime` gives quantized launch. Apps must add output latency to host time, so that events line up "at the speaker". Link's test plan expects alignment within 3 ms. Link has no notion of phrase beyond the quantum. https://ableton.github.io/link · https://github.com/Ableton/link
- **MIDI clock** sends 24 pulses per quarter note and carries no bar position. Song Position Pointer and MIDI Time Code (MTC) add position (standard MIDI facts, not re-checked here).
- **OS2L** sends JSON over TCP, found through DNS-SD `_os2l._tcp`. Beat messages carry `pos`, where pos mod 4 is 0 on bar lines and pos mod 16 is 0 on 16-beat phrases. They also carry `bpm`, `change` and an optional `strength`. There are also `btn` and `cmd` messages. VirtualDJ has `os2lBeatOffset` in ms. The community tool rkbx_os2l reads rekordbox's memory and sends OS2L. https://os2l.org/ · https://virtualdj.com/manuals/virtualdj/appendix/optionslist.html · https://github.com/fjel/rkbx_os2l
- **Pro DJ Link / Beat Link Trigger (BLT):**
  - BLT reads beat packets, beat grids and track metadata, including rekordbox song structure (PSSI).
  - *Phrase Triggers* (v7+) run cues on a phrase type, Mood and track Bank, with Start, Loop, End and Fill sections. https://blt-guide.deepsymmetry.org/beat-link-trigger/Shows_PhraseTriggers.html · https://blt-guide.deepsymmetry.org/beat-link-trigger/main/Expressions_PhraseTrigger.html
  - It can bridge to Link through Carabiner. A latency setting defaults to 1 ms and can be negative, because "beat packets arrive before beats are heard". https://blt-guide.deepsymmetry.org/beat-link-trigger/main/Link.html
  - Caveats: CDJ-3000 precise-position packets can jitter. The Opus Quad sends no beat packets, so it can be up to 200 ms off. https://blt-guide.deepsymmetry.org/beat-link-trigger/OpusQuad.html
  - The packet formats are documented. https://djl-analysis.deepsymmetry.org/
- **Timecode in practice:** deadmau5 Cube v3 uses SMPTE, "but not all triggers are SMPTE-based". A custom OSC decoder on the MA2 console receives song names, BPM and bar position, so the set can change without re-programming. https://plsn.com/articles/designer-insights/deadmau5-cube-v3-tour
- **Latency compensation:**
  - Sound travels about 2.9 ms per metre, so a mix position 30 m from the PA hears it about 87 ms late. https://eventools.io/resources/calculators/speaker-delay-calculator
  - ITU-R BT.1359 detectability limits: +45 ms when sound is *ahead of* the picture, −125 ms when sound is *behind* it. https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.1359-0-199802-S!!PDF-E.pdf
  - So light arriving slightly before the sound is safe. Light after the sound is noticed sooner. Grid-predicted events should be sent early by the laser pipeline latency (IDN/DAC buffer plus galvo response, which we have not measured).
  - Reactive onset events cannot be sent early. They always lag by analysis delay plus output delay. Distance hides some of this for the audience, because their sound is late too.

---

## 4. Stems, instruments and visuals in practice

- **Per-track audio is the pro answer.** VDMX shows Ableton sending each track to its own Soundflower channel, with one analysis per track. The same works with "multi-channel audio inputs … receiving each instrument before they are mixed". https://vdmx.vidvox.net/tutorials/analyzing-multiple-audio-tracks-from-live-in-vdmx-with-soundflower
- **Drummers:** Sensory Percussion uses an ML model per hit to classify zone (centre, edge, rim and so on), velocity and timbre. It sends MIDI to Resolume (clip launches, CC to effects) and to Lightkey (the kick drives three fixtures) in Glenn Kotche's PASIC show. https://sunhou.se/blog/glenn-kotche-pasic-2023
  - In Brett Bolton's Notch set, the kick "pushes the particles out" and the snare "adds additional geometry". https://sunhou.se/blog/brett-bolton-ep1-interview
  - Plain trigger inputs respond "a few milliseconds faster" than the timbre-classifying sensors. https://help.sunhou.se/v2/software-overview/hardware-panel/hardware-inputs/trigger-inputs
- **Audio to MIDI:** `aubionotes` emits MIDI-like notes from live audio. Melodyne is an offline editor, not a live tool (not verified for live modes).
- **deadmau5 Cube v3:** real-time TouchDesigner visuals, built ahead and cued live from inside the cube alongside Ableton. Joel "can manipulate the stems in real time". In the later Retro5pective show, from one track onward "the show switches over to timecode and runs itself". https://plsn.com/articles/designer-insights/deadmau5-cube-v3-tour · https://plsn.com/featured/chauvet-professional-helps-erik-mahowald-fire-imagination-at-deadmau5-retro5pective
- **Eric Prydz EPIC/HOLO:** "The Eric Prydz show is live, every time. There are never any pre-sequenced elements." The team does not know the song order in advance. https://plsn.com/featured/eric-prydz-holo-stuns-with-holographic-3d-fx-powered-by-avolites-ai · https://www.wired.com/story/eric-prydz-epic-5-show-london-lasers-holograms
- **Amon Tobin ISAM:** a custom TouchDesigner app mixed pre-rendered per-track animation with "generative, audio-reactive graphics". The team: "We have our show rehearsed, and there's manual control during the show." https://www.commarts.com/project/22824/amon-tobin-isam · https://plsn.com/articles/production-profile/amon-tobins-isam-tour-is-visual-innovation-squared
- **Bonobo:** full band with "immersive visuals". How much is live or reactive: not verified. **Jon Hopkins:** not researched.

**Pattern:** big shows pre-build the looks per song, then trigger them live by hand or by timecode. Raw FFT is a small layer on top. Nobody lets the analysis choose the show.

---

## 5. Failure modes and how pros handle them

| Failure | Cause | Mitigation seen in the field |
|---|---|---|
| False beats / jitter | Busy percussion, crowd noise, mic | Line feed from the mixer, not a mic (PangoBeats, LaserOS); onset min-interval and thresholds (aubio, Lightjams `trigger()`) |
| Tempo halving or doubling | Metrical ambiguity | ×2 / ÷2 buttons (VDMX "Double Rate"); BPM range limits (madmom) |
| Wrong downbeat | Weak downbeat tracking (~50% F1) | "1" / Sync Beat button (VDMX →\|, LaserCube); take downbeat from Link or CDJ |
| Breakdown with no drums | No onsets to track | Coast on last tempo (PangoBeats); switch to level/presence-driven looks; SoundSwitch "Pause" or "Blackout" option |
| Latency | Analysis plus output buffers | Predict from grid and send early; latency offsets (BLT, VirtualDJ `os2lBeatOffset`) |
| Everything looks the same | One mapping all night | Phrase-based scene changes (rekordbox, SoundSwitch); random-on-beat values; scene rotation |
| Over-reactivity / "visualizer look" | Every hit moves everything | Per-scene reactivity toggle (Synesthesia); event-triggered timed effects instead of raw FFT (BEYOND); simple autoloops |
| Mic hears the room | Ambient noise | Line or loopback input; gain staging (BEYOND: −25 to 0 dBFS at chorus) |

---

## 6. Fifteen UX ideas built on music understanding

1. **Phrase ruler.** A strip shows beat 1–4, bar 1–8 and the position in a 16- or 32-bar phrase, on every screen.
   - *Needs:* tempo plus downbeat (tap or Link) and counting.
   - *Reliability:* high once beat 1 is set.
   - *Fallback:* a big "1" button, plus ±1 beat and ±1 bar nudges.
2. **Arm for next phrase.** Press a look and it glows "armed". It fires on the next 8-, 16- or 32-bar boundary, and the performer picks which.
   - *Needs:* bar counting.
   - *Reliability:* high.
   - *Fallback:* "fire now" with a long press. The quantize size is shown on the button.
3. **Drop catcher ("light the next drop").** Arm a drop look. The app watches for a build (rising highs or riser, kick gone, energy climbing). It fires on the first strong kick plus bass at a phrase boundary.
   - *Needs:* band envelopes, onsets and the phrase grid.
   - *Reliability:* medium.
   - *Fallback:* only the timing is automatic. If no drop is seen, it fires at the armed boundary or waits for a manual hit. The choice is per arm.
4. **Instrument lanes ("the kick drives laser 1").** A matrix routes sources to projectors and parameters. Sources are kick, snare, hats, bass, vocal presence and loudness. Targets are projector × parameter (brightness, size, rotation, scan speed).
   - *Needs:* band-limited onsets, or real stems.
   - *Reliability:* high for kick, medium for snare, low for vocals from a full mix.
   - *Fallback:* each lane has a "grid" switch that swaps detected hits for clock pulses (kick → every beat).
5. **Audio depth knob.** One master knob, plus one per look, sets the blend from 0% (pure clock) to 100% (pure audio). This is BEYOND's time/channel mix.
   - *Needs:* any audio feature.
   - *Reliability:* not applicable; it is a safety valve.
   - *Fallback:* 0%.
6. **Energy ceiling.** Short-term loudness (3 s) and band presence pick a tier: calm, groove or peak. The tier caps density, brightness and speed. Looks fill in within the tier.
   - *Needs:* loudness S and presence.
   - *Reliability:* high.
   - *Fallback:* a manual energy fader, which wins when touched.
7. **Confidence HUD.** Shows BPM, a confidence bar and the state: Locked, Coasting or Lost (as in PangoBeats). It also shows the active source (Link, CDJ, audio or tap).
   - *Needs:* tracker confidence.
   - *Reliability:* makes failure visible.
   - *Fallback:* when confidence drops, the app switches to Link or tap automatically and keeps the phase.
8. **One-touch correction.** ×2, ÷2, "tap 4 to re-phase" (keeps tempo, moves beat 1) and "new track" (resets evidence).
   - *Needs:* the tracker.
   - *Reliability:* not applicable.
   - *Fallback:* this *is* the fallback.
9. **Breakdown autopilot.** When kick onsets stop and bass presence falls, the app suggests or switches to "breakdown" looks. These are slow and wide, driven by level, not hits. The clock coasts.
   - *Needs:* kick onset rate and presence.
   - *Reliability:* medium-high.
   - *Fallback:* the default is "suggest", with a manual breakdown toggle.
10. **Phrase-type banks (with CDJs).** rekordbox phrase types map to look banks: Intro, Up, Down, Chorus and Outro, split by Mood and Bank. The app picks within a bank, and the performer overrides.
    - *Needs:* PSSI phrase data over Pro DJ Link.
    - *Reliability:* high for analysed tracks.
    - *Fallback:* a default bank when there is no phrase data, as with rekordbox's Default scene and BLT's raw trigger for tracks without phrases.
11. **Look-ahead timeline.** A scrolling view reads "Drop in 8 bars", "Outro in 16". The performer drags looks onto future phrases.
    - *Needs:* phrase data (CDJ or offline pre-analysis).
    - *Reliability:* high with data.
    - *Fallback:* the performer taps "phrase starts now" and the app projects 16- or 32-bar phrases forward.
12. **Fill accents.** In the last bar of a phrase, or on a detected fill or snare roll (rekordbox "Fill in"), an accent effect plays, such as a chase or strobe roll.
    - *Needs:* the phrase grid, fill flags or onset density.
    - *Reliability:* high from the grid, medium from audio.
    - *Fallback:* an "end-of-phrase FX" button, quantized to the last 1–4 beats.
13. **Variety engine.** Tracks how long each look or palette has been on screen. At phrase boundaries it suggests, or auto-rotates, to a fresh look in the same energy tier. This fights "everything looks the same".
    - *Needs:* bars and energy.
    - *Reliability:* high.
    - *Fallback:* a lock button per projector.
14. **Live players as sources.** Drum triggers, Sensory Percussion MIDI and pads become first-class sources next to "kick" (for example, the drummer's snare fires a laser 2 burst).
    - *Needs:* MIDI or OSC.
    - *Reliability:* very high.
    - *Fallback:* not needed.
15. **Record and re-time loops.** Record the performer's hits against the bar grid. Replay them as phrase-length autoloops (SoundSwitch Autoloops, VDMX Data Looper) that follow tempo changes.
    - *Needs:* bars.
    - *Reliability:* high.
    - *Fallback:* stop or clear the loop.

---

## 7. Feasibility ladder for this app

| Level | What you get | Ideas unlocked | Main risk | Rough effort |
|---|---|---|---|---|
| **0. Tap / Link only** | Beats, bars, phrases by counting (4/4, 8/16/32 bars), quantized launch, "1" button, nudges | 1, 2, 5 (at 0%), 8 (partly), 12 (grid), 13, 15 | Human must set beat 1; odd phrasing breaks counting | Small: days to 2 weeks |
| **1. Live audio input** (line from mixer best) | Band levels, per-band onsets, M/S loudness, presence, BPM + confidence, build/breakdown heuristics | 3, 4 (kick/snare), 6, 7, 8, 9 | Downbeat unreliable; mic noise; licensing (aubio GPL, Essentia AGPL, madmom models NC) | Medium: 2–6 weeks for own DSP (FFT, flux, envelopes, EBU loudness); more for a tracker |
| **1b. Latency calibration** | Measured laser pipeline delay; "send early" offset; audience-distance offset | Better timing for all | Needs a click and a photodiode or camera test | Small: about 1 week |
| **2a. Multichannel stems** (DAW outs, band aux sends) | True kick/snare/vocal envelopes | 4 at full strength, 14 | Needs routing at the gig | Small–medium: N inputs × Level 1 DSP |
| **2b. ML stems on a stereo mix** | Rough drums/bass/vocals at 23–92 ms | 4 (vocal lanes) | GPU load; SDR about 4–8 dB; weight licences | Large: 1–3 months |
| **3. CDJ / rekordbox data** | Master deck, beat grid, bar, phrase types, fills, Mood/Bank | 10, 11, 12 (exact), 3 (predictive) | Tracks must be phrase-analysed; Opus Quad and CDJ-3000 quirks | Medium: receive from BLT over OSC/MIDI in days; embed beat-link (Java; licence not verified) in 2–4 weeks |
| **3b. OS2L in** (VirtualDJ, rkbx_os2l) | Beat pos (bar/phrase), BPM, buttons | 1, 2, 10-lite | Few senders | Small: days |
| **4. Offline pre-analysis of a set list** | Full timeline with drops/sections from offline models | 11 without CDJs | Must align playback (manual start, Link, timecode) | Medium: 2–4 weeks |

Suggested order: 0 → 1b → 1 (levels and onsets first, tracker later) → 3 via BLT bridge → 2a. Keep Link or tap as the master clock at every level.

---

## 8. Open questions

- What audio feed will operators usually have: a mixer line, the venue mix, a mic, or none?
- What is our real laser output latency (IDN buffer plus DAC plus galvos)? How early should grid events be sent?
- Should automation ever *fire* looks, or only *suggest* and *time* them? Pros lean manual.
- **Safety:** audio-reactive brightness, size and scan speed must never break audience-scanning limits or zones. Reactivity should only modulate inside a safe envelope. This needs its own spec.
- Build our own DSP, or license a library (aubio, Essentia), or use BeatNet (CC BY) weights? Legal review needed.
- Bridge to Beat Link Trigger, or embed beat-link? What is beat-link's licence (not verified)?
- Does phrase counting hold for non-EDM sets (live bands, hip-hop, odd meters)?
- How do we show confidence without clutter on a dark stage UI?
- With several projectors, how do instrument lanes map: one instrument per projector, or per parameter across all?
