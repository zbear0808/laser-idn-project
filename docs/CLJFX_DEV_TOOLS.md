# Cljfx Dev Tools Integration

This document describes the integration of [Cljfx dev tools](https://github.com/cljfx/dev) into the laser show application.

## Rationale

The default developer experience of cljfx has some issues:
- what are the allowed props for different JavaFX types is not clear and requires looking it up in the source code;
- what are the allowed JavaFX type keywords requires looking it up in the source code;
- errors when using non-existent props are unhelpful;
- generally, errors that happen during cljfx lifecycle are unhelpful because the stack traces have mostly cljfx internals instead of user code.

Cljfx dev tools solve these issues by providing:
- reference for cljfx types and props;
- specs for cljfx descriptions, so they can be validated;
- dev-time lifecycles that perform validation and add cljfx component stacks to exceptions to help with debugging;

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

### 3. Which tools are actually active?

The REPL help and `explain-desc` below are usable with `:dev`. Automatic validation, component stacks and the F12 inspector also need a dev lifecycle installed on the renderer. The current [`app/create-app`](../src/laser_show/app.clj#L95-L112) computes a dev resolver but passes it as `:opts` to `fx/create-app`. [cljfx 1.10.6](https://github.com/cljfx/cljfx/blob/1.10.6/src/cljfx/api.clj#L584-L613) builds its own renderer options and ignores that key. It uses the standard context-aware function lifecycle. The supplied `:error-handler` is also ignored; the `create-app` option is `:renderer-error-handler`.

If changing that wiring, preserve the [context-aware rendering and event pipeline](CLJFX_STATE_MANAGEMENT.md#reactive-state-in-this-app) and verify what reaches the renderer. The startup message alone is not evidence that the inspector is active.

## Tools and Usage

The `user` namespace (in `dev/user.clj`) provides convenient wrappers for the cljfx dev tools.

### Props and types reference

If you don't remember the props required by some cljfx type, or if you don't know what are the available types, you can use `help` (wraps `cljfx.dev/help`) to look up this information:

```clojure
(require 'user)

;; look up available types:
(user/help)
;; Available cljfx types:
;; Cljfx type                             Instance class
;; :accordion                             javafx.scene.control.Accordion
;; :affine                                javafx.scene.transform.Affine
;; ...etc

;; look up information about fx type:
(user/help :label)
;; Cljfx type:
;; :label
;; 
;; Instance class:
;; javafx.scene.control.Label
;; 
;; Props                            Value type     
;; :accessible-help                 string
;; :accessible-role                 either of: :button...
;; ...etc

;; look up information about a prop:
(user/help :label :graphic)
;; Prop of :label - :graphic
;; 
;; Cljfx desc, a map with :fx/type key
;; 
;; Required instance class:
;; javafx.scene.Node¹
;; 
;; ---
;; ¹javafx.scene.Node - Fitting cljfx types:
;;  Cljfx type               Class
;;  :accordion               javafx.scene.control.Accordion
;;  :ambient-light           javafx.scene.AmbientLight
;;  ...etc
```

You can also use help in a UI form that shows that same information, but is easier to search for:

```clojure
(user/help-ui)
```

Invoking this fn will open a window with props and types reference.

### Improved error messages with spec

With the validating lifecycle installed, invalid component descriptions produce errors like the one below. With the current wiring, use `explain-desc` directly to validate a description.

Example of an error in the REPL:

```
;; invalid state change - making text prop of a label not a string
;; prints to *err*:
clojure.lang.ExceptionInfo: Invalid cljfx description of :label type:
:not-a-string - failed: string? in [:text]

Cljfx component stack:
  :label
  user/message-view
  :scene
  :stage
  user/root-view
```

You can also validate individual descriptions while developing using `explain-desc` (wraps `cljfx.dev/explain-desc`):

```clojure
(user/explain-desc
  {:fx/type :stage
   :showing true
   :scene {:fx/type :scene
           :root {:fx/type :text-formatter
                  :value-converter :int}}})
;; :int - failed: #{:local-date-time ...} in [:scene :root :value-converter]

(user/explain-desc
  {:fx/type :stage
   :showing true
   :scene {:fx/type :scene
           :root {:fx/type :text-field
                  :text-formatter {:fx/type :text-formatter
                                   :value-converter :integer}}}})
;; Success!
```

### Cljfx component inspector

With a dev type->lifecycle installed, **F12** opens the component tree inspector while the application is focused. For the current app, the [plorer skill](../.agents/skills/plorer/SKILL.md) inspects the live JavaFX scene graph independently of that option.

Inspector shows a live tree of components and their props.

## CSS Hot-Reload Integration

CSS hot-reload works independently of the inspector:

```clojure
;; Enable CSS watching
(watch-styles!)

;; Now edit src/laser_show/css/title_bar.clj
;; Eval the (def menu-theme ...) form
;; The watch dispatches :dev/reloaded; the next render picks up the new CSS URL.
```

## Production Builds

Dev tools are automatically disabled in production:

1. The `:dev` alias is not used in production builds
2. The `laser-show.dev` system property is not set
3. `dev-config/dev-mode?` returns `false`
4. Standard cljfx lifecycle is used (no validation overhead)

## References

- [cljfx/dev GitHub](https://github.com/cljfx/dev)
- [cljfx Documentation](https://github.com/cljfx/cljfx)
- [JavaFX CSS Reference](https://openjfx.io/javadoc/12/javafx.graphics/javafx/scene/doc-files/cssref.html)
