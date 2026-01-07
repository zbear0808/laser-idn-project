# Cljfx Dev Tools Integration

This document describes the integration of cljfx/dev tools into the laser show application.

## Overview

The cljfx/dev tools provide enhanced development experience with:
- **Better error messages** - Validation errors show exactly which prop is invalid and where in the component tree
- **Component inspector** - Press F12 to see live component tree and props
- **Props/types reference** - Documentation browser for all cljfx types and their props
- **Component stacks in errors** - Error messages show the full component hierarchy

## Setup

### 1. Dependency

The `io.github.cljfx/dev` dependency is added to the `:dev` alias in `deps.edn`:

```clojure
:dev
{:extra-deps {io.github.cljfx/dev {:mvn/version "1.10.6.42"}}}
```

This ensures dev tools are only available during development, not in production builds.

### 2. Dev Mode Detection

Dev mode is controlled by the `laser-show.dev` system property:

```clojure
;; In deps.edn :dev alias
:jvm-opts ["-Dlaser-show.dev=true"]
```

The `laser-show.dev-config/dev-mode?` function checks this property.

### 3. Conditional Lifecycle

In `laser-show.app/create-renderer`, the lifecycle is conditionally selected:

```clojure
(if (dev-config/dev-mode?)
  ;; Dev mode: Use validating lifecycle with better errors
  @(requiring-resolve 'cljfx.dev/type->lifecycle)
  ;; Prod mode: Use standard lifecycle
  #(or (fx/keyword->lifecycle %)
       (fx/fn->lifecycle-with-context %)))
```

### 4. Error Handler Integration

A custom error handler logs errors and integrates with dev tools:

```clojure
(defn- error-handler
  [^Throwable ex]
  (log/error ex "Error in cljfx renderer:")
  (.printStackTrace ex *err*))
```

In dev mode, this shows component stacks from cljfx.dev.

## Usage

### Starting the App

Start the REPL with the `:dev` alias:

```bash
clj -M:dev
```

Then in the REPL:

```clojure
(start)  ; Starts app with dev tools enabled
```

You'll see:
```
🔧 Dev mode enabled - cljfx dev tools active (Press F12 for inspector)
```

### Component Inspector

While the app is running, press **F12** to open the component inspector. This shows:
- Live component tree
- Props for each component
- Component hierarchy

### Props Reference

Use the `help` function to look up cljfx types and props:

```clojure
;; List all available types
(help)

;; Show props for a specific type
(help :label)

;; Show details for a specific prop
(help :label :text)
```

### Interactive Reference Browser

Open a searchable UI window with all types and props:

```clojure
(help-ui)
```

### Validate Descriptions

Check if a cljfx description is valid before using it:

```clojure
;; This will show an error: 123 is not a string
(explain-desc {:fx/type :label :text 123})

;; This will show "Success!"
(explain-desc {:fx/type :label :text "Hello"})
```

### CSS Hot-Reload Integration

The dev tools work seamlessly with CSS hot-reload:

```clojure
;; Enable CSS watching
(watch-styles!)

;; Now edit src/laser_show/css/title_bar.clj
;; Eval the (def menu-theme ...) form
;; UI updates instantly with new styles!

;; Dev tools will validate the new CSS descriptions
```

## Error Messages

### Without Dev Tools (Production)

```
Exception in thread "JavaFX Application Thread" java.lang.ClassCastException: 
class java.lang.Integer cannot be cast to class java.lang.String
```

### With Dev Tools (Development)

```
clojure.lang.ExceptionInfo: Invalid cljfx description of :label type:
123 - failed: string? in [:text]

Cljfx component stack:
  :label
  user/message-view
  :scene
  :stage
  user/root-view
```

Much more helpful! Shows exactly what's wrong and where.

## Production Builds

Dev tools are automatically disabled in production:

1. The `:dev` alias is not used in production builds
2. The `laser-show.dev` system property is not set
3. `dev-config/dev-mode?` returns `false`
4. Standard cljfx lifecycle is used (no validation overhead)

## Benefits

### Faster Development
- Instant feedback on prop typos or wrong types
- No need to dig through JavaFX stack traces

### Better Debugging
- Component stacks show exactly where errors occur
- Inspector shows live component state

### Learning Aid
- Help UI makes it easy to discover available props
- No need to constantly check documentation

### Catch Errors Early
- Validation happens before rendering
- Prevents cryptic JavaFX errors

## Troubleshooting

### "cljfx.dev not available"

If you see this message, you're not running with the `:dev` alias. Start the REPL with:

```bash
clj -M:dev
```

### Inspector Not Opening

Make sure:
1. App is running with `:dev` alias
2. Dev mode is enabled (check logs for "🔧 Dev mode enabled")
3. You're pressing F12 while the app window has focus

### Validation Too Strict

If validation is catching false positives, you can temporarily disable it by:
1. Removing the `-Dlaser-show.dev=true` JVM option
2. Restarting the REPL

## References

- [cljfx/dev GitHub](https://github.com/cljfx/dev)
- [cljfx Documentation](https://github.com/cljfx/cljfx)
- [JavaFX CSS Reference](https://openjfx.io/javadoc/12/javafx.graphics/javafx/scene/doc-files/cssref.html)
