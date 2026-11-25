# How people perceive and feel music, and what that means for a laser-show instrument

Research note, 2026-11-21. Part of the live-performance UX study.

## Summary

People do not hear music as a list of sounds. They lock an internal clock to the beat, at a favourite rate near 2 Hz. They group beats into bars and phrases. They predict what comes next, and they get pleasure both from being right and from a well-timed surprise. They feel music in the body: rising pitch feels "up", loud feels "big and bright", and some rhythms make the body want to move. In a crowd, moving together feels good in itself. For a laser controller this points one way. The performer should not fire cues on a grid. They should play the show with things the body already knows: a clock that keeps running, gestures whose shape matches the sound, held tension that is released on the drop, and a sense of where they are in the phrase. Timing matters most at beat level and slower. Lights that land late are noticed sooner than lights that land early. Fast flashing has hard safety limits.

---

## 1. Beat, meter and timing

**Key findings**

- **People tap early.** When non-musicians tap along to a metronome, their taps land before the click. This is the "negative mean asynchrony". It is usually tens of ms, and in some people up to ~100 ms. It is small or gone with real music, with trained musicians, and when beats are subdivided. People do not notice they are doing it. Repp 2005, *Psychonomic Bulletin & Review* 12(6), DOI [10.3758/BF03206433](https://doi.org/10.3758/BF03206433). [PDF](http://users.df.uba.ar/anita/f1_labo/clase1/repp%20psycho%20bull%20rev%202006%20synchro%20tapping%20review.pdf)
- **Preferred tempo is about 120 bpm (2 Hz).** Both spontaneous tapping and the tempo spread of popular music peak at 120–130 bpm. Moelants 2002, [Ghent biblio](https://biblio.ugent.be/publication/159578); see also Hine et al. 2022, DOI [10.3389/fpsyg.2022.952488](https://doi.org/10.3389/fpsyg.2022.952488).
- **Meter has limits.** The shortest interval that works as a rhythm element is about 100 ms (10 per second). The longest beat people can still feel as a beat is about 1.5–2 s. London, *Hearing in Time* (OUP 2004/2012), summarised [here](https://mmtfinland.wordpress.com/2020/03/28/how-to-talk-about-musical-metre/) and [here](https://mtosmt.org/issues/mto.10.16.4/mto.10.16.4.polak.html).
- **The beat is an oscillation in the brain, not a list of clicks.** Neural resonance theory says brain rhythms lock to the music and keep going. People feel a pulse even when no sound falls on it. Large, Herrera & Velasco 2015, DOI [10.3389/fnsys.2015.00159](https://doi.org/10.3389/fnsys.2015.00159).
- **Phrase structure.** In EDM, sections come in 8, 16 and 32 bars, and producers and dancers count them. This is a genre convention, not a lab finding (Butler, *Unlocking the Groove*, 2006; not verified here).

**So for a light-show instrument…**

- Run an internal beat clock (a phase-locked loop) that keeps going through breakdowns. Pads act on clock phase, not on "now".
- Read a press made just before a beat as "on the beat". Snap an early press forward to the beat it was aiming at. For a late press, fire at once if it is within ~40 ms of the beat. If it is later than that, fire at once but weaker, or (in quantise mode) hold it for the next subdivision. The values are starting points to tune by testing.
- Show the phrase position (bar 5 of 16) as a big, glanceable ring. People feel phrases, so the UI should too.

## 2. Audio-visual sync and latency

**Key findings**

- **Late lights are noticed sooner than early lights.** In broadcasting, viewers detect sound *leading* the picture at about 45 ms. They only detect sound *lagging* the picture at about 125 ms. So a visual event that comes slightly early is much more tolerated than one that comes late. ITU-R BT.1359, [PDF](https://www.itu.int/dms_pubrec/itu-r/rec/bt/R-REC-BT.1359-1-199811-I!!PDF-E.pdf).
- **The binding window is wide but uneven.** For simple flash and beep pairs, the window in which people still judge them "simultaneous" spans a few hundred ms. It is wider on the visual-first side, and it differs a lot from person to person. Stevenson et al. 2012, [PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC3795069/); van Eijk et al. 2008, DOI [10.3758/pp.70.6.955](https://doi.org/10.3758/pp.70.6.955); Vroomen & Keetels 2010 review, DOI [10.3758/app.72.4.871](https://doi.org/10.3758/app.72.4.871).
- **Above ~4 Hz, people cannot judge sync.** For repeating flash and beep trains faster than about 4 Hz, people cannot tell whether the two are in sync. Fujisaki & Nishida 2005, [PubMed](https://pubmed.ncbi.nlm.nih.gov/16032402/).
- **Distance matters.** Sound travels about 2.9 ms per metre, but light arrives instantly. The brain's slower visual processing roughly cancels this at ~10 m, the "horizon of simultaneity" (Pöppel; King 2005, DOI [10.1016/j.cub.2005.04.022](https://doi.org/10.1016/j.cub.2005.04.022)). At 40 m from the speakers, sound arrives ~115 ms after the light.
- **Instrument latency.** Wessel & Wright propose that a computer instrument should react to a gesture within 10 ms, with no more than ~1 ms of jitter. [PDF](https://opensoundcontrol.stanford.edu/files/p1-wessel_0.pdf). For touch screens, Jota et al. 2013 found that people notice and are slowed by latencies well under 100 ms. DOI [10.1145/2470654.2481317](https://doi.org/10.1145/2470654.2481317). The exact thresholds (~25 ms) are not verified.
- **Synchresis.** When a sound and an image happen at the same moment, the mind welds them into one event, "free of logic". Chion, *Audio-Vision*, [chapter PDF](https://ccrma.stanford.edu/courses/32n/Chion_Audiovision_Chapter1.pdf).

**So for a light-show instrument…**

- Set a global "visual lead" offset, default about +10 to +30 ms (light slightly before sound at the mix position), and let the operator tune it. Erring early is safe; erring late is not.
- Let the operator enter the stage depth. Most of the crowd is 10–40 m away, so they already see lights before they hear the sound. Do not "correct" this.
- Spend the timing budget on beat-level and slower hits. Fast 1/16 strobes above 4 Hz are texture; no one can check if they are in sync.
- Treat jitter as worse than latency. A steady 15 ms is better than 5 ms ± 10 ms.

## 3. Groove and the urge to move

**Key findings**

- **Groove means "wanting to move", and it feels good.** Music rated high in groove made people move more on their own, and tapping in sync felt pleasant. Janata, Tomic & Haberman 2012, DOI [10.1037/a0024208](https://doi.org/10.1037/a0024208).
- **Inverted U.** Medium syncopation gives the most urge to move and the most pleasure. Too little is boring; too much loses the beat. Witek et al. 2014, DOI [10.1371/journal.pone.0094446](https://doi.org/10.1371/journal.pone.0094446). Harmony also plays a part (Matthews et al. 2019, DOI [10.1371/journal.pone.0204539](https://doi.org/10.1371/journal.pone.0204539)). One recent replication found a null effect, so the curve is not settled. [PLOS ONE 2024](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0311877)
- **Microtiming is not magic.** Shifting drums off the grid by up to 25 ms *lowered* groove ratings. Expert-played timing was fine, but exaggerating it hurt. Senn et al. 2016, [PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC5050221/), citing Frühauf et al. 2013 and Davies et al. 2013.
- **Bass you cannot hear still makes people dance.** At a live electronic concert, very-low-frequency speakers that the audience could not consciously detect increased dancing. Cameron et al. 2022, DOI [10.1016/j.cub.2022.09.035](https://doi.org/10.1016/j.cub.2022.09.035). The size of the effect (~12%) is not verified.

**So for a light-show instrument…**

- Add a "syncopation" knob for automatic patterns. At 0 it hits only on downbeats, in the middle it adds off-beats and anticipations, and at full it is busy. Default to the middle.
- Keep the main hits tight on the clock. Groove in the lights should come from *which* beats get hits, not from sloppy timing.
- Drive the "floor" of the show (beam swells, slow pulses) from the sub-bass, because the body responds to low end even when it is not heard as such.

## 4. Expectation, tension and release

**Key findings**

- **ITPRA.** Huron splits expectation into five responses: Imagination and Tension *before* an event, then Prediction, Reaction and Appraisal *after* it. Tension rises when an outcome is both uncertain and important. Being right is rewarded, so pleasure from predictable music is real. Huron, *Sweet Anticipation* (MIT Press 2006), [front matter PDF](https://sites.evergreen.edu/natureofmusic/wp-content/uploads/sites/105/2015/09/Sweet-Anticipationpdf.pdf); [review](https://mtosmt.org/issues/mto.09.15.3/mto.09.15.3.aversa.html).
- **Meyer** (1956) first argued that emotion in music comes from expectations being delayed, thwarted or fulfilled.
- **Tension can be modelled.** Lerdahl's tonal-tension model predicts how listeners rate tension rising and falling. Lerdahl & Krumhansl 2007, DOI [10.1525/mp.2007.24.4.329](https://doi.org/10.1525/mp.2007.24.4.329).
- **Anticipation and peak are separate rewards.** Dopamine is released both while waiting for a peak moment (caudate) and at the peak itself (nucleus accumbens). The study defined "anticipation" as the 15 s before a chill. Salimpoor et al. 2011, DOI [10.1038/nn.2726](https://doi.org/10.1038/nn.2726).
- **Chills are personal.** Chills are linked to new voices entering, sudden loudness changes and expectation, but they do not fire reliably for everyone. Grewe et al. 2007, DOI [10.1525/mp.2007.24.3.297](https://doi.org/10.1525/mp.2007.24.3.297) (detail not verified).
- **The EDM "break routine".** A track goes breakdown → build-up → drop. In the build-up, producers remove the bass and kick, add risers ("uplifters"), sweep filters and speed up snare rolls. The drop brings the bass and beat back. Dancers rate the build-up and drop as the most pleasurable parts and move most at the drop. Solberg 2014, DOI [10.12801/1947-5403.2014.06.01.04](https://doi.org/10.12801/1947-5403.2014.06.01.04); Solberg & Jensenius 2016, [EMR](https://emusicology.org/article/id/4725/); Solberg & Dibben 2019, [PDF](https://eprints.whiterose.ac.uk/id/eprint/145911/1/Solberg%26Dibben2019.pdf).
- A short silence or cut-out right before the drop is a common tactic. Huron's theory explains why it works: it raises uncertainty right before the expected event. I found no lab study measuring the pause itself (not verified).

**So for a light-show instrument…**

- **A build is a held gesture; letting go is the drop.** While held, the show narrows, rises, speeds up and drains colour. On release (snapped to the next downbeat), everything opens at once.
- Give the performer a "cut" (a short blackout or freeze) that can be armed to land one beat or a half-beat before the drop.
- Save the biggest look for drops. If every chorus gets the "big look", it stops being a reward (prediction effect plus contrast).
- Keep repeated motifs consistent so the crowd learns them. A recurring "signature" look then pays off when it returns.

## 5. Emotion models

**Key findings**

- **Valence–arousal circumplex.** Feelings sit on two axes: pleasant–unpleasant and calm–excited. Russell 1980, DOI [10.1037/h0077714](https://doi.org/10.1037/h0077714).
- **GEMS.** Music-specific feelings fall into nine groups: wonder, transcendence, tenderness, nostalgia, peacefulness, power, joyful activation, tension and sadness. These collapse into sublimity, vitality and unease. Zentner, Grandjean & Scherer 2008, [PubMed](https://pubmed.ncbi.nlm.nih.gov/18729581/); [GEMS page](https://musemap.org/resources/gems).
- **Industry axes.** Spotify's "energy" (loudness, onset rate, timbre, entropy) and "valence" (positiveness) are essentially arousal and valence. [Spotify docs](https://developer.spotify.com/documentation/web-api/reference/get-audio-features) (the API is now deprecated). Mixed In Key gives tracks a 1–10 "energy level" that DJs use to plan set arcs (source page not verified).
- **Set arcs.** DJs talk about warming up, peaking and cooling down. I found no strong lab study of how audiences perceive a whole set's intensity curve (gap).

**So for a light-show instrument…**

- Use a 2D "mood pad": x is valence (cool and sparse ↔ warm and lush), y is energy (slow, dim, few beams ↔ fast, bright, many beams). It is one touch to steer the whole rig.
- Add GEMS-style "presets of feeling" (Wonder, Power, Tenderness, Tension) as macro scenes, not just colours.
- Show a set-level energy timeline so the performer can see how long they have been at peak and plan a valley.

## 6. Embodiment, gesture and cross-modal mapping

**Key findings**

- **The body mediates music.** Leman says we understand music through action. Good mediation technology should feel transparent, like an extension of the body. Leman 2008, *Embodied Music Cognition and Mediation Technology*, DOI [10.7551/mitpress/7476.001.0001](https://doi.org/10.7551/mitpress/7476.001.0001).
- **Sound-tracing.** Asked to "draw" sounds in the air, people move their hands up for rising pitch and down for falling pitch. Noisy sounds get more jagged, less precise tracings. Godøy et al. 2006, [Semantic Scholar](https://www.semanticscholar.org/paper/Exploring-Music-Related-Gestures-by-Sound-Tracing-A-God%C3%B8y-Haga/ad3e62cc1cda2fcffd0934155dec15ba2c25b550); Nymoen et al. 2013, DOI [10.1145/2465780.2465783](https://doi.org/10.1145/2465780.2465783).
- **Musical change becomes imagined motion,** but not symmetrically: increases and decreases map differently (e.g. a fade-out evokes falling and slowing more than a crescendo evokes rising). Eitan & Granot 2006, DOI [10.1525/mp.2006.23.3.221](https://doi.org/10.1525/mp.2006.23.3.221) (asymmetry details not verified).
- **Cross-modal correspondences.** Pitch–elevation is one of the most robust: high is up. High pitch also goes with small, bright and angular shapes. Loudness goes with brightness. **No reliable pitch–hue link was found.** Spence 2011, DOI [10.3758/s13414-010-0073-7](https://doi.org/10.3758/s13414-010-0073-7).
- **Different body parts carry different meter levels.** In free movement, the beat level shows up as vertical bounce. Slower levels (2 and 4 beats) show up as side-to-side sway and torso movement. Toiviainen, Luck & Thompson 2010, [PDF](https://users.jyu.fi/~ptoiviai/pdf/Toiviainen_et_al_MP2010.pdf). Clear pulse makes people move more; low-frequency flux makes heads and hands move faster. Burger et al. 2013, DOI [10.3389/fpsyg.2013.00183](https://doi.org/10.3389/fpsyg.2013.00183).
- **The beat in a gesture is a peak in acceleration,** not the lowest point of the gesture. Luck & Sloboda 2009, DOI [10.1525/mp.2009.26.5.465](https://doi.org/10.1525/mp.2009.26.5.465). Musicians' cueing nods work the same way, and sharp ("marcato") gestures are easier to follow than smooth ones. Bishop & Goebl 2018, [PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC5718341/).

**So for a light-show instrument…**

- Map hand height to beam elevation, and the hand's pitch-like "rise" to tilt up. Pitch–height is robust and needs no teaching.
- Trigger from a gesture at its peak acceleration (the "snap"), not at its endpoint. Endpoints come late.
- Map gesture size and speed to beam count and brightness (loudness ↔ size/brightness). Do not map pitch to hue; hue is free for mood.
- Use a two-layer body map: small fast moves (hands, head) play beat-level hits, while slow sway and torso play bar-level motion and colour.

## 7. Visual rhythm, flashes and safety

**Key findings**

- **Motion carries beat better than flashes.** People synchronise to a flashing light much worse than to sound. A *moving* visual (a bouncing ball) works almost as well as sound. Hove et al. 2013, [APA](https://psycnet.apa.org/record/2013-20826-002); Iversen et al. 2015, [PDF](https://escholarship.org/content/qt47f9j39s/qt47f9j39s.pdf); Gan et al. 2015, [Sci Rep](https://www.nature.com/articles/srep11974).
- **Photosensitive epilepsy limits.** Most guidelines allow no more than 3 flashes in any one second. Risk spans roughly 3–60 Hz and is highest around 16–25 Hz. Saturated red flashes are treated as extra risky. [Ofcom guidance PDF](https://www.ofcom.org.uk/siteassets/resources/documents/tv-radio-and-on-demand/broadcast-guidance/gn_flash.pdf); international review, [PMC](https://pmc.ncbi.nlm.nih.gov/articles/PMC11872230/); [Epilepsy Society](https://epilepsysociety.org.uk/understanding-epilepsy/photosensitive-epilepsy). These rules are written for screens. How they apply to large-field laser and strobe effects is not verified; venue rules and laser safety (MPE) are a separate topic.
- **What makes a hit feel "on the beat".** Combining the above: a sharp onset (fast attack), landing at or slightly before the beat, and a motion that peaks in acceleration on the beat. This is my synthesis, not a single tested finding.

**So for a light-show instrument…**

- Make "motion hits" (a beam that bounces or snaps to a new position) the default accent. Use flashes sparingly as punctuation.
- Build a flash limiter into the engine. Count full-field on/off transitions over any rolling 1 s window, and cap them at 3 unless a "venue strobe" mode is unlocked. At 128 bpm, quarter notes (2.1 Hz) are fine and eighth notes (4.3 Hz) are not.
- Give hits a fast attack (one frame or less) and a decay shaped by velocity.

## 8. Crowds and shared timing

**Key findings**

- Audiences move faster at live concerts than at playback of the same music, and fans move most. Swarbrick et al. 2019, DOI [10.3389/fpsyg.2018.02682](https://doi.org/10.3389/fpsyg.2018.02682).
- Audience body signals (heart, breathing, movement) sync up during concerts, and more sync goes with stronger aesthetic experience. Tschacher et al. 2023, DOI [10.1038/s41598-023-41960-2](https://doi.org/10.1038/s41598-023-41960-2).
- Moving in sync with others increases social bonding ("self-other merging", endorphins). Tarr, Launay & Dunbar 2014, DOI [10.3389/fpsyg.2014.01096](https://doi.org/10.3389/fpsyg.2014.01096).
- At EDM drops, dancers move in similar ways at the same moments. Solberg & Jensenius 2016 (above).

**So for a light-show instrument…**

- Use the lights to *lead* group timing. Big moving beams on the beat give the crowd a shared visual pulse.
- Call and response: the lights "ask" (a sweep across the crowd), then leave a gap the crowd fills (a cheer or a jump), then "answer". This needs a gap-aware cue type.
- Optional: a camera or mic can sense crowd motion or noise and feed it back as a slow "crowd energy" input (automatic).

---

## 25 interaction ideas grounded in perception

| # | Idea | Rests on | Suits |
|---|---|---|---|
| 1 | Presses snap to the beat they were aiming at: early presses wait for the beat, slightly late ones fire at once. | Negative mean asynchrony; late visuals noticed sooner (Repp; ITU) | pad, keyboard |
| 2 | Global "visual lead" offset, default +10–30 ms, so light lands slightly before sound. | Audio-lag tolerance asymmetry (ITU BT.1359) | automatic |
| 3 | A beat clock that keeps running through breakdowns and silence. | Neural resonance; pulse without sound (Large) | automatic |
| 4 | Tap-tempo by nodding or bouncing, read at peak acceleration. | Beat = acceleration peak (Luck & Sloboda) | the body, hand gesture |
| 5 | Hold to build, release to drop, with release snapped to the next downbeat. | Tension then release; break routine (Huron; Solberg) | pad, fader, hand gesture |
| 6 | The build's intensity rises with hold time and pressure: beams narrow, rise and speed up. | Tension grows with nearness and uncertainty (ITPRA) | pad (pressure), touch |
| 7 | An armed "cut" blackout lands a beat before the drop. | Silence raises uncertainty before an expected event (Huron; not verified) | pad |
| 8 | Phrase ring: shows bar N of 8/16/32 and lights up near the phrase end. | Phrase-level expectation | automatic, display |
| 9 | Cues can be "queued to phrase": they fire at the next 8- or 16-bar boundary. | Changes feel right on phrase boundaries | pad, keyboard |
| 10 | Hand height → beam elevation; pitch-like rise → tilt up. | Pitch–height correspondence (Spence; Godøy) | hand gesture, touch |
| 11 | Gesture size/speed → beam count and brightness. | Loudness ↔ size/brightness (Spence) | hand gesture, the body |
| 12 | Jagged, shaky gestures → noisy, scattered textures; smooth gestures → clean lines. | Sound-tracing of noisy vs pitched sounds (Nymoen) | hand gesture |
| 13 | 2D mood pad: x = valence (cool↔warm), y = energy (sparse↔dense). | Valence–arousal (Russell; Spotify) | touch |
| 14 | Feeling macros (Wonder, Power, Tenderness, Tension). | GEMS nine factors (Zentner) | pad |
| 15 | Syncopation knob for automatic patterns, with the default in the middle. | Inverted-U groove (Witek) | fader |
| 16 | Hits stay tight on the grid; "feel" comes from which beats get hit. | Microtiming shifts lower groove (Frühauf; Senn) | automatic |
| 17 | Motion hits (bounce, snap) as the default accent; flashes as rare punctuation. | Moving visuals sync better than flashes (Hove; Iversen) | pad, automatic |
| 18 | Hard flash limiter: at most 3 full-field transitions per rolling second. | Photosensitivity guidelines (Ofcom; review) | automatic |
| 19 | Fast subdivisions (above 4 Hz) are treated as loose texture with no sync promise. | AV sync judgments fail above ~4 Hz (Fujisaki & Nishida) | automatic |
| 20 | Two-layer body control: hands for beat hits, sway for bar-level motion and colour. | Meter levels map to different body axes (Toiviainen) | the body |
| 21 | Sub-bass energy drives a slow "floor" layer of swell and brightness. | Low end drives dancing (Cameron; Burger) | automatic |
| 22 | Peak budget: the biggest look is locked until a drop, and its overuse is shown. | Contrast and the prediction effect (Huron) | automatic, display |
| 23 | Set energy timeline showing time spent at peak. | DJ energy arcs (Mixed In Key; not verified) | display |
| 24 | Call-and-response cue: sweep, then a gap, then an answer. | Crowd synchrony and bonding (Tarr; Tschacher) | pad |
| 25 | Signature motif: one look reused at matching song moments so the crowd learns it. | Dynamic expectations reward repetition (Huron) | pad, keyboard |

## Numbers designers should use

| What | Number | Source |
|---|---|---|
| Preferred tempo | ~120 bpm (2 Hz); peak 120–130 bpm | Moelants 2002 |
| Shortest rhythmic interval | ~100 ms | London 2004 |
| Longest felt beat | ~1.5–2 s (30–40 bpm) | London 2004 |
| Tap-ahead of non-musicians | tens of ms, up to ~100 ms; ~0 with music and for musicians | Repp 2005 |
| AV detectability (broadcast) | light late by >~45 ms is noticed; light early by up to ~125 ms is not | ITU-R BT.1359 |
| AV acceptability (broadcast) | light late ≤ ~90 ms, light early ≤ ~185 ms | ITU-R BT.1359 |
| Limit for judging AV sync of repeating events | ~4 Hz | Fujisaki & Nishida 2005 |
| Sound delay over distance | ~2.9 ms per metre; natural cancellation ~10 m | physics; Pöppel; King 2005 |
| Instrument latency target | ≤10 ms, jitter ≤1 ms | Wessel & Wright 2002 |
| Microtiming that already hurts groove | up to ±25 ms on kick or snare | Frühauf et al. 2013 via Senn 2016 |
| Flash limit | ≤3 flashes in any 1 s; risk 3–60 Hz, worst 16–25 Hz | Ofcom; PSE review |
| Flash rates at 128 bpm | quarter notes 2.13 Hz (ok); eighth notes 4.27 Hz (over the limit) | arithmetic |
| Anticipation window before a peak | ~15 s (the study's chosen epoch) | Salimpoor 2011 |
| EDM phrase lengths | 8 / 16 / 32 bars; at 128 bpm, 16 bars ≈ 30 s | genre convention (not verified) |
| Gesture beat point | peak acceleration | Luck & Sloboda 2009 |

## Open questions

1. **Lights vs speech.** The ITU thresholds come from lip-sync. Do laser hits on a kick drum have a narrower window? Sharp visual onsets probably give a tighter window, but we should test with our own rig.
2. **The press-timing rule.** How early do performers press when playing lights rather than tapping? Log real press offsets against the beat clock and fit the window per person.
3. **Gesture latency.** Camera and IMU tracking add 30–80 ms. Can we predict the acceleration peak far enough ahead to stay within budget? (Not verified.)
4. **Pre-drop silence.** No study found that measures the pause before a drop directly. A/B test cut lengths.
5. **Flash rules for lasers.** Screen-based flash guidelines do not cover haze beams and audience scanning. We need venue and laser-safety advice.
6. **Set arcs.** There is little evidence on how a crowd perceives intensity over a 60–120 minute set. Could crowd sensing (camera motion, noise level) give a usable "energy" signal?
7. **Groove's inverted U.** Does it hold for light patterns and not just drums? A recent study failed to replicate it for drums.
8. **Individual differences.** Binding windows, tap-ahead and chills all vary widely. Should the instrument learn its performer?
