# CSS Color System Documentation

This document describes the consolidated color system for the laser-show application UI.

## Overview

The color system provides a single source of truth for all colors used in the application, with automatically computed state variants (hover, active, disabled) and semantic naming for better maintainability.

## Design Principles

1. **Minimal palette** - Use as few distinct colors as possible while maintaining visual hierarchy
2. **Semantic naming** - Colors are named by their purpose, not their appearance
3. **Computed variants** - State changes (hover, active) are computed from base colors
4. **No hardcoded hex values** - All CSS modules use theme references

## Architecture

The color system is organized in three tiers:

```
┌─────────────────────────────────────────────────────────────┐
│                     base-colors (7 grays)                   │
│  Raw hex values - the only place colors are defined         │
│  gray-900 → gray-800 → gray-700 → gray-600 → gray-500 →    │
│  gray-300 → gray-100                                        │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   semantic-colors                           │
│  Meaningful names mapped to base colors                     │
│  (bg-primary, bg-surface, bg-elevated, bg-interactive,      │
│   text-primary, accent-success, etc.)                       │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                   computed-colors                           │
│  Automatically derived state variants                       │
│  (accent-success-hover, drop-target-bg, etc.)               │
└─────────────────────────────────────────────────────────────┘
```

## Files

| File | Purpose |
|------|---------|
| `src/laser_show/css/colors.clj` | Color manipulation utilities (lighten, darken, etc.) |
| `src/laser_show/css/theme.clj` | Color definitions (base, semantic, computed) |
| `src/laser_show/css/core.clj` | Accessor functions and stylesheet aggregation |

## Color Reference

### Base Colors (Consolidated Grayscale - 7 levels)

| Name | Hex | Purpose |
|------|-----|---------|
| gray-900 | #1E1E1E | Base - main window background (darkest) |
| gray-800 | #282828 | Surface - panel backgrounds |
| gray-700 | #303030 | Elevated - cards, dialogs, menus |
| gray-600 | #3D3D3D | Interactive - buttons, inputs (default state) |
| gray-500 | #505050 | Hover state / active borders |
| gray-300 | #808080 | Muted text, disabled text, subtle borders |
| gray-100 | #E0E0E0 | Primary text (lightest) |

### Accent Colors

| Name | Hex | Usage |
|------|-----|-------|
| green-500 | #4CAF50 | Success, primary action, active state |
| green-400 | #5CBF60 | Success hover |
| blue-500 | #2196F3 | Info, links |
| blue-600 | #4A6FA5 | Selection background, active tabs |
| blue-400 | #5A8FCF | Drop target background |
| blue-300 | #7AB8FF | Focus ring, drop indicator border |
| orange-500 | #FF9800 | Warning |
| red-500 | #D32F2F | Danger, error, delete |
| red-600 | #B71C1C | Danger hover/pressed |
| purple-500 | #7E57C2 | Effects cells |

### Category Colors (for hierarchy depth)

| Name | Hex | Usage |
|------|-----|-------|
| cat-blue | #4A7B9D | Depth 0, geometric |
| cat-purple | #6B5B8C | Depth 1, wave |
| cat-green | #5B8C6B | Depth 2, beam |
| cat-brown | #8C7B5B | Depth 3, abstract |

## Semantic Color Mapping

### Background Hierarchy

| Semantic Name | Base Color | Purpose |
|---------------|------------|---------|
| `bg-primary` | gray-900 | Root/window background (darkest) |
| `bg-surface` | gray-800 | Panel backgrounds |
| `bg-elevated` | gray-700 | Cards, dialogs, menus, group headers |
| `bg-interactive` | gray-600 | Buttons, inputs (default state) |
| `bg-hover` | gray-500 | Hover state |
| `bg-active` | gray-500 | Active/pressed state |

### Text Hierarchy

| Semantic Name | Purpose |
|---------------|---------|
| `text-primary` | Main text, headings (gray-100) |
| `text-secondary` | Secondary text (#B0B0B0) |
| `text-muted` | Hints, disabled text (gray-300) |

### Border Colors

| Semantic Name | Purpose |
|---------------|---------|
| `border-subtle` | Subtle borders (matches elevated bg) |
| `border-default` | Standard borders (gray-500) |

## Usage

### In CSS Modules

```clojure
(ns laser-show.css.my-component
  (:require [cljfx.css :as css]
            [laser-show.css.theme :as theme]))

(def my-styles
  (css/register ::my-styles
    (let [{:keys [bg-interactive bg-hover text-primary]} theme/semantic-colors]
      {".my-button"
       {:-fx-background-color bg-interactive
        :-fx-text-fill text-primary
        
        ":hover"
        {:-fx-background-color bg-hover}}})))
```

### In View Components (Inline Styles)

```clojure
(ns laser-show.views.example
  (:require [laser-show.css.core :as css]))

(defn my-component [{:keys [status]}]
  {:fx/type :label
   :text "Status"
   :style (str "-fx-text-fill: " 
               (if (= status :success) 
                 (css/accent-success) 
                 (css/accent-danger))
               ";")})
```

### For Canvas/Graphics (JavaFX Color objects)

```clojure
(require '[laser-show.css.core :as css])
(import '[javafx.scene.paint Color])

(.setStroke gc (Color/web (css/bg-primary)))
(.setFill gc (Color/web (css/text-primary)))
```

## Accessor Functions

### Background Colors

| Function | Description |
|----------|-------------|
| `(css/bg-primary)` | Root/window background (darkest) |
| `(css/bg-surface)` | Panel backgrounds |
| `(css/bg-elevated)` | Elevated surfaces (cards, dialogs) |
| `(css/bg-interactive)` | Interactive element default state |
| `(css/bg-hover)` | Hover state background |
| `(css/bg-active)` | Active/pressed state background |

### Text Colors

| Function | Description |
|----------|-------------|
| `(css/text-primary)` | Primary text |
| `(css/text-secondary)` | Secondary text |
| `(css/text-muted)` | Muted/hint text |
| `(css/text-disabled)` | Disabled text |

### Accent Colors

| Function | Description |
|----------|-------------|
| `(css/accent-success)` | Success (green) |
| `(css/accent-info)` | Info (blue) |
| `(css/accent-warning)` | Warning (orange) |
| `(css/accent-danger)` | Danger (red) |
| `(css/accent-success-hover)` | Success hover state |
| `(css/accent-danger-hover)` | Danger hover state |

### Selection Colors

| Function | Description |
|----------|-------------|
| `(css/selection-bg)` | Selected item background |
| `(css/selection-hover)` | Selection hover state |

### Status Colors

```clojure
(css/status-color :online)    ; Green
(css/status-color :offline)   ; Red
(css/status-color :connecting) ; Blue
(css/status-color :occupied)   ; Yellow
```

### Category/Depth Colors

```clojure
(css/category-color :depth-0)  ; Blue
(css/category-color :depth-1)  ; Purple
(css/category-color :depth-2)  ; Green
(css/category-color :depth-3)  ; Brown

(css/depth-color 0)  ; Same as :depth-0
```

## Color Utility Functions

The `laser-show.css.colors` namespace provides pure functions for color manipulation:

### Brightness

```clojure
(require '[laser-show.css.colors :as colors])

(colors/lighten "#4CAF50" 0.1)  ; 10% lighter
(colors/darken "#4CAF50" 0.1)   ; 10% darker
```

### Transparency

```clojure
(colors/with-alpha "#4CAF50" 0.5)  ; 50% opacity -> "#4CAF5080"
```

### Convenience Functions

```clojure
(colors/hover-color "#4CAF50")     ; Default 10% lighter
(colors/active-color "#4CAF50")    ; Default 10% darker
(colors/disabled-color "#4CAF50")  ; Desaturated + reduced opacity
```

## Best Practices

1. **Never use hardcoded hex values** - Always use accessor functions from `css/core.clj` or theme references in CSS modules

2. **Use semantic names** - Prefer `accent-success` over `green-500` for clarity

3. **Computed variants** - Use `accent-success-hover` instead of manually lightening

4. **CSS Classes over inline styles** - When possible, use CSS classes defined in the CSS modules

5. **Status colors** - Use `(css/status-color status)` for connection/device states

6. **Canvas drawing** - Import colors via `(Color/web (css/bg-primary))`

## Migration Notes

### Changes from Previous Version

The color system was consolidated from 11 gray levels to 7:

| Old Name | New Equivalent |
|----------|----------------|
| gray-850 | gray-800 |
| gray-550 | gray-500 |
| gray-400 | gray-300 |
| gray-200 | text-secondary (#B0B0B0) |

### Removed Colors

- `bg-group` - Use `bg-elevated` instead
- `border-strong` - Use `border-default` instead

### Legacy Aliases

For backward compatibility, these legacy names still work:

| Old Key | New Semantic Name |
|---------|-------------------|
| `bg-darkest` | `bg-primary` |
| `bg-dark` | `bg-surface` |
| `bg-medium` | `bg-elevated` |
| `bg-light` | `bg-interactive` |
| `accent-primary` | `accent-success` |
| `accent-blue` | `accent-info` |
| `accent-red` | `accent-danger` |
| `accent-orange` | `accent-warning` |
