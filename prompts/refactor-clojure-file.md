# Clojure File Refactoring Task

You are an expert Clojure developer. Your task is to refactor the provided Clojure file following the style guidelines below.

## Style Guidelines

### General Clojure Conventions

1. **Avoid left-nesting** - Don't do `((:get-fn x) arg)`, instead use `let` destructuring:
   ```clojure
   ;; Bad
   ((:set-selected! cell) true)
   
   ;; Good
   (let [{:keys [set-selected!]} cell]
     (set-selected! true))
   ```

2. **Prefer parameter maps** over many positional parameters for functions

3. **Threading macros**:
   - Thread-first `->` for map transformations (map as first param)
   - Thread-last `->>` for sequence operations (seq as last param)

4. **Avoid lazy evaluation** for time-sensitive operations:
   - Use `mapv` instead of `map`
   - Use transducers for chained operations

5. **Use `if-let` and `when-let`** instead of separate `let` + `if`:
   ```clojure
   ;; Bad
   (let [start-time (:start-time-ms @!state)]
     (if start-time
       (- (System/currentTimeMillis) start-time)
       0))
   
   ;; Good
   (if-let [start-time (:start-time-ms @!state)]
     (- (System/currentTimeMillis) start-time)
     0)
   ```

6. **Combine nested `let` bindings** when they're redundant

7. **Extract repeated map accesses** into `let` bindings with destructuring

8. **Use function parameter defaults** instead of `(or value default)` patterns

9. **Naming conventions**:
   - Boolean keywords should end with `?` (e.g., `:active?`)
   - Boolean symbols should end with `?` (e.g., `active?`)

10. **Minimize comments** - Code should be self-documenting through clear naming

### Utility Functions Available

The project has utility functions in `laser-show.common.util` (aliased as `u`).
Prefer these eager versions over lazy equivalents:

**Collection operations:**
- `u/mapv-indexed` - eager map-indexed
- `u/keepv` - eager keep
- `u/keepv-indexed` - eager keep-indexed  
- `u/removev` - eager remove
- `u/filterv-indexed` - eager filter-indexed
- `u/removev-indexed` - eager remove-indexed
- `u/consv` - cons returning vector
- `u/concatv` - concat returning vector
- `u/mapcatv` - mapcat returning vector

**Map operations:**
- `u/map-into` - build maps from collections: `(u/map-into :id items)` or `(u/map-into :id :name items)`
- `u/assoc-some` - assoc only non-nil values
- `u/filter-keys` / `u/filter-vals` - filter map by key/value predicate
- `u/remove-keys` / `u/remove-vals` - remove from map by key/value predicate
- `u/merge-in` - merge into nested path

**Other:**
- `u/clamp` - numeric clamping with optional bounds
- `u/->map` - quick map from symbols: `(u/->map a b c)` => `{:a a :b b :c c}`
- `u/->map&` - same but allows extra kvs: `(u/->map& a b :c 42)`

### cljfx-Specific Guidelines (for UI files)

- Use `fx/sub-val` for simple data access from context
- Use `fx/sub-ctx` for computed/derived values
- Don't return lazy sequences from subscription or component functions
- Use `fx/ext-let-refs` and `fx/ext-get-ref` for shared component instances
- Prefer custom style classes over default JavaFX classes

### State Management

- Keep state consolidated - don't create independent component state
- Read from the main database/state atom
- Only use local state for short-lived UI state (e.g., dialog tabs)

## Instructions

1. Refactor the file to follow all guidelines above
2. Add `[laser-show.common.util :as u]` to requires if using utility functions (if not already present)
3. Preserve all functionality - this is refactoring, not rewriting
4. Improve readability and reduce unnecessary complexity
5. Fix any anti-patterns you identify

## File to Refactor

**Path:** {{FILE_PATH}}

```clojure
{{FILE_CONTENT}}
```

## Output Format

First, briefly list the significant changes you made (2-5 bullet points).

Then provide the complete refactored file in a code block:

```clojure
;; Your refactored code here
```

Do not use placeholders like "rest of code unchanged" - provide the COMPLETE file.
