---
name: app-repl
description: Evaluate Clojure in the running laser show app through its nREPL. Use to reuse or start the REPL, start or stop the app, reload changed code, read app state, or run live-app work before driving the UI with plorer.
---

# Evaluating in the app's REPL

The app runs inside a Clojure REPL started with the `:dev` alias. That REPL is an nREPL server that writes its port to `.nrepl-port` in the repo root (gitignored). Agents evaluate forms through `nrepl_eval.clj` in this folder, which reads that port.

## Reuse a running REPL first

The user may already have one, for example from Calva. Check before starting another, from the repo root:

```sh
clj -M:dev .agents/skills/app-repl/nrepl_eval.clj '(+ 1 2)'
```

Output `3` with exit status 0 means a REPL answers, so use it. "Nothing listens on port" means `.nrepl-port` is stale, and you should start a REPL. Calva's connect sequence also runs `(start)`, so a connected REPL may already have the app open. Check with `(count (javafx.stage.Window/getWindows))`.

## Start a REPL

Pick the platform alias for this machine: `mac-arm` (Apple Silicon Mac), `mac` (Intel Mac), `win` or `linux`.

- Visible app, with the same aliases as Calva's connect sequence in `.vscode/settings.json`: `clj -M:<platform>:dev:laser-show:repl`
- Headless, with no windows, for background work: `clj -M:<platform>:dev:laser-show:repl:test-ui-headless`

On macOS, from the repo root:

1. Start the REPL as a background command (the shell tool's `background` option), so it keeps running:

   ```sh
   mkdir -p debug-output
   export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
   export PATH="$JAVA_HOME/bin:$PATH"
   sh -c 'echo $$ > debug-output/repl.pid; exec clj -M:mac-arm:dev:laser-show:repl' > debug-output/repl.log 2>&1
   ```

2. Wait up to two minutes for the port file, then run the check above. If the port never appears, read `debug-output/repl.log`:

   ```sh
   for i in $(seq 1 120); do [ -s .nrepl-port ] && break; sleep 1; done
   ```

- Java 25 or newer must be the `java` on `PATH` and in `JAVA_HOME`. Agent shells don't load `~/.zshrc`, so set both yourself. The paths above are the Homebrew JDK on this Mac.
- Starting the REPL doesn't start the app. `(laser-show.app/start-test!)` or `(start)` does.
- Stop only the REPL you started: `kill "$(cat debug-output/repl.pid)"`. Never `pkill` by name, which can also kill the user's REPL. Delete `.nrepl-port` afterwards if it remains, so a stale port file isn't mistaken for a live REPL.
- The first run downloads dependencies and may print `Downloading:` lines.

## Evaluate code

```sh
clj -M:dev .agents/skills/app-repl/nrepl_eval.clj '<form>' [namespace]
clj -M:dev .agents/skills/app-repl/nrepl_eval.clj @path/to/form.clj [namespace]
```

- Prints stdout, then each result. Eval errors go to stderr and exit with status 1.
- Each call runs in a new session in the `user` namespace. Pass a namespace such as `laser-show.app` as the second argument, or fully qualify symbols. Namespaces, vars and app state persist across calls, but a namespace must be loaded before you call into it.
- The examples in this skill are REPL forms. Inside a shell argument's single quotes, write `(quote x)` for `'x`, or put the form in a file and pass `@path`. Use the file form for multi-line code and for code with double quotes, because PowerShell can mangle double quotes in arguments.

## Run the app

Prefer test mode for experiments:

```clojure
(require 'laser-show.app)      ; a fresh REPL hasn't loaded it yet
(laser-show.app/start-test!)   ; mounts the UI from starter state; no IDN scan, MIDI, OSC, gamepad or Link
(laser-show.app/stop!)         ; unmounts the UI and input; safe to call any time
```

Loading `laser-show.app` prints a `Reflection warning` from `cljfx/coerce.clj`. It is harmless.

`(start)` from `dev/user.clj` calls `laser-show.app/start!`. That scans the network for IDN laser projectors, connects to the Link (Carabiner) service, and starts MIDI, OSC and gamepad input. Use it only when the task needs real hardware or network behavior, and ask the user first.

Traps:

- `(stop)` from `dev/user.clj` only stops an app started with `(start)`. After `start-test!` it prints "Stopped." and leaves the window open. Use `(laser-show.app/stop!)`.
- `reload-styles!` and `watch-styles!` check the same flag, so they refuse after `start-test!`. Call `(require 'laser-show.css.reload)` and then `(laser-show.css.reload/reload-all-styles!)` directly; it returns `{:success? true}`.
- `start!` does not stop an app that is already mounted. Call `(laser-show.app/stop!)` before switching between `start-test!` and `start!`.

## Reload changed code

Reload an edited namespace with `(require 'the.ns :reload)`. Remount the UI with `(laser-show.app/stop!)` and then `(laser-show.app/start-test!)` to be sure view changes show up.

## Read app state

```clojure
(require 'laser-show.state.core)
(keys (laser-show.state.core/get-raw-state))   ; top-level keys, such as :backend, :chains, :config and :grid
```

For specific values, use the pure readers in `src/laser_show/state/extractors.clj`.

## UI work

To inspect or drive the UI, load the `plorer` skill (`.agents/skills/plorer/`) in the same REPL.

## Ask first

- The REPL may be the user's live session. Don't stop, restart or reload their app without asking.
- Don't start the full app (`start!`) or run code that connects hardware, sends laser output or writes project files unless the task needs it. Ask first.
