# Profiling Guide

This guide covers how to use clj-async-profiler to profile your laser show application for CPU and memory performance.

## Quick Start

1. **Start your REPL with the `:dev` alias** (profiler JVM options are configured there):
   ```bash
   clj -M:dev
   ```

2. **Start the application**:
   ```clojure
   (start)
   ```

3. **Profile CPU for 30 seconds**:
   ```clojure
   (profile-cpu 30)
   ```
   Interact with the application normally during profiling.

4. **View the flamegraph**:
   ```clojure
   (view-flamegraph)
   ```

## Available Commands

All profiling commands are available from the `user` namespace in the REPL:

### CPU Profiling

```clojure
;; Profile CPU for specified duration (in seconds)
(profile-cpu 30)

;; Profile a specific code section
(profile-section! #(your-expensive-computation))
```

### Allocation Profiling

```clojure
;; Profile memory allocations for specified duration
(profile-alloc 30)
```

### Viewing Results

```clojure
;; Open the most recent flamegraph in browser
(view-flamegraph)

;; Start web UI to browse all profiles
(profiler-ui 8080)
;; Then visit http://localhost:8080

;; Check profiler status
(profiler-status)
```

## Understanding Flamegraphs

### Reading a Flamegraph

- **Width**: Represents time spent (CPU) or bytes allocated (allocation profiling)
- **Height**: Call stack depth (bottom = entry point, top = leaf functions)
- **Color**: Just for visual distinction, no semantic meaning
- **Interactive**: Click to zoom, search for functions, etc.

### Finding Hotspots

1. Look for **wide bars** at the top of the stack - these are leaf functions consuming CPU/memory
2. **Flat tops** (plateaus) indicate time spent in that specific function
3. **Towers** indicate deep call stacks

### Common Patterns in JavaFX/Clojure Apps

- `clojure.lang.*` - Clojure runtime overhead
- `javafx.scene.*` - JavaFX rendering
- `laser_show.*` - Your application code (focus here!)

## Profiling Workflows

### Workflow 1: Finding Slow Code Paths

Use this when frames are taking too long to generate:

```clojure
;; 1. Check frame profiler stats
(require '[laser-show.profiling.frame-profiler :as fp])
(fp/print-stats 100)

;; 2. If you see slow frames, profile CPU while using the app
(profile-cpu 30)

;; 3. View flamegraph and look for wide bars in laser_show.* namespaces
(view-flamegraph)

;; 4. Optimize the hot functions you found
;; 5. Verify improvement with frame profiler
(fp/print-stats 100)
```

### Workflow 2: Finding Memory Pressure

Use this when you suspect excessive allocations:

```clojure
;; 1. Profile allocations while using the app
(profile-alloc 30)

;; 2. View flamegraph - look for wide bars showing allocation hotspots
(view-flamegraph)

;; 3. Common culprits:
;;    - Creating many temporary collections
;;    - String concatenation in loops
;;    - Unnecessary object creation in hot paths
```

### Workflow 3: Profiling Specific Operations

Use this to profile a specific piece of code:

```clojure
;; Profile frame generation
(require '[laser-show.profiling.async-profiler :as prof])
(prof/profile-section!
  #(dotimes [_ 100]
     ;; Your code here
     ))

;; Profile effect chain application
(prof/profile-section!
  #(let [chain (get-effect-chain)]
     (dotimes [_ 100]
       (apply-effects chain base-frame))))
```

### Workflow 4: Comparing Before/After

Use the web UI to generate differential flamegraphs:

```clojure
;; 1. Profile before optimization
(profile-cpu 30)

;; 2. Make your changes

;; 3. Profile after optimization
(profile-cpu 30)

;; 4. Start web UI
(profiler-ui 8080)

;; 5. In the UI, select both profiles and generate a diff flamegraph
;;    Red = got slower, Green = got faster
```

## Integration with Frame Profiler

Your application has two complementary profiling tools:

### Frame Profiler (`laser-show.profiling.frame-profiler`)
- **What**: Measures frame generation timing at a high level
- **When**: Always-on, negligible overhead
- **Use for**: Identifying WHEN performance issues occur

```clojure
(require '[laser-show.profiling.frame-profiler :as fp])
(fp/print-stats)
(fp/print-stats 100) ; Last 100 frames
```

### Async Profiler (clj-async-profiler)
- **What**: Deep CPU/allocation profiling with flamegraphs
- **When**: On-demand, some overhead during profiling
- **Use for**: Understanding WHY performance issues occur

**Combined Workflow**:
1. Use frame profiler to detect slow frames
2. Use async profiler to find the cause
3. Optimize the hot code paths
4. Verify with frame profiler

## Tips for Accurate Profiling

### 1. Profile Realistic Workloads
- Use actual effect chains, not empty ones
- Test with typical projector counts
- Include realistic input (MIDI/OSC) if relevant

### 2. Profile Long Enough
- CPU profiling: 30-60 seconds minimum
- Allocation profiling: 30-60 seconds minimum
- Longer is better for statistical accuracy

### 3. Avoid Profiling Startup
- Let the app warm up before profiling
- JIT compilation affects early performance
- Profile steady-state behavior

### 4. Profile in Development Mode
- The `:dev` alias includes necessary JVM options
- Production builds may have different performance characteristics

### 5. Watch for JIT Effects
- First run may be slower (interpreted code)
- After JIT compilation, code runs faster
- Profile after warm-up for realistic results

## Troubleshooting

### "Can not attach to current VM"

You're missing the `-Djdk.attach.allowAttachSelf` JVM option. Make sure you started the REPL with `:dev` or `:repl` alias:

```bash
clj -M:dev
```

Verify the option is set:
```clojure
(System/getProperty "jdk.attach.allowAttachSelf")
;; Should return "" (empty string), not nil
```

### "WARNING: package jdk.attach not in java.base"

You're running on JRE instead of JDK. clj-async-profiler requires JDK.

### Flamegraph shows mostly `[unknown]`

You need JDK debug symbols. On Windows, this usually isn't an issue, but on Linux you may need to install them (e.g., `openjdk-11-dbg` on Ubuntu).

### Profiler seems to hang

The profiler is working - just wait for the duration to complete. You'll see a completion message when done.

## Advanced Usage

For advanced features, use the `laser-show.profiling.async-profiler` namespace directly:

```clojure
(require '[laser-show.profiling.async-profiler :as prof])

;; Manual start/stop
(prof/start-cpu-profiling!)
;; ... do work ...
(prof/stop-profiling!)

;; Custom options
(prof/profile-cpu! 30 {:interval 1000000}) ; 1ms sampling

;; List all profiles
(prof/list-profiles)

;; View specific profile
(prof/view-profile! "01-cpu-flamegraph.html")

;; Get profiler status
(prof/profiler-status)
```

## Output Location

All flamegraphs are saved to `./profiling-results/results/` in your project directory.

Files are named with timestamps and profile type:
- `YYYYMMDD_HHMMSS-01-cpu-flamegraph.html`
- `YYYYMMDD_HHMMSS-02-alloc-flamegraph.html`

## Further Reading

- [clj-async-profiler documentation](https://github.com/clojure-goes-fast/clj-async-profiler)
- [Clojure Goes Fast knowledge base](http://clojure-goes-fast.com/kb/profiling/clj-async-profiler/)
- [Understanding Flamegraphs](http://www.brendangregg.com/flamegraphs.html)
