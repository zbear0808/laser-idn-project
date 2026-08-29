# Profiling Guide

This project provides REPL commands for profiling. Start with the `:dev` alias:

```bash
clj -M:dev
```

## Quick Start

```clojure
(start)                 ; Start the application
(profile-cpu 30)        ; Profile CPU for 30 seconds
(view-flamegraph)       ; Open flamegraph in browser
```

## Available Commands

### CPU & Allocation Profiling (clj-async-profiler)

```clojure
(profile-cpu 30)                          ; CPU profile for N seconds
(profile-alloc 30)                        ; Allocation profile for N seconds
(profile-section! #(your-code-here))      ; Profile specific code

(view-flamegraph)                         ; Open most recent flamegraph
(profiler-ui 8080)                        ; Web UI at http://localhost:8080
(profiler-status)                         ; Check status
```

### Frame Profiler (always-on, low overhead)

```clojure
(require '[laser-show.profiling.frame-profiler :as fp])
(fp/print-stats)        ; All frame stats
(fp/print-stats 100)    ; Last 100 frames
```

### JFR Profiling (timeline & spike detection)

Requires one-time setup: `clj -T:build compile-java`

```clojure
(jfr-start)                     ; Start recording
(jfr-spikes 5000)               ; Alert on frames >5ms
(jfr-auto-dump 10000)           ; Auto-save on frames >10ms  
(jfr-dump)                      ; Save recording
(jfr-stop)                      ; Stop recording

(jfr-status)                    ; Recording status
(jfr-recordings)                ; List saved recordings
```

Open `.jfr` files with [JDK Mission Control](https://jdk.java.net/jmc/).

## Typical Workflow

1. Use frame profiler to detect slow frames: `(fp/print-stats 100)`
2. Use async-profiler to find hot code: `(profile-cpu 30)` → `(view-flamegraph)`
3. Or use JFR for timeline correlation with GC/JIT events

## Output Locations

- Flamegraphs: `./profiling-results/results/`
- JFR recordings: `./profiling-results/jfr/`

## Further Reading

- [clj-async-profiler](https://github.com/clojure-goes-fast/clj-async-profiler)
- [Understanding Flamegraphs](http://www.brendangregg.com/flamegraphs.html)
- [JDK Mission Control](https://docs.oracle.com/javacomponents/jmc-5-5/jmc-user-guide/)