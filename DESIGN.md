---
name: Astra TV
description: A quiet, remote-operated library for user-provided television and on-demand media.
colors:
  background: "#0B1015"
  surface: "#141C24"
  elevated: "#1B2732"
  ink: "#F5F7FA"
  muted: "#9CAAB7"
  accent: "#48D6E8"
  outline: "#293743"
  video-black: "#000000"
typography:
  headline:
    fontFamily: "sans-serif"
  title:
    fontFamily: "sans-serif"
  body:
    fontFamily: "sans-serif"
  label:
    fontFamily: "sans-serif"
rounded:
  artwork: "8dp"
  action: "12dp"
  dialog: "16dp"
spacing:
  compact: "8dp"
  control-gap: "12dp"
  standard: "16dp"
  dialog-inset: "24dp"
  safe-margin: "32dp"
components:
  action:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    typography: "{typography.label}"
    rounded: "{rounded.action}"
    padding: "10dp 16dp"
  action-selected:
    backgroundColor: "{colors.elevated}"
    textColor: "{colors.ink}"
    rounded: "{rounded.action}"
  action-focused:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.background}"
    rounded: "{rounded.action}"
  playlist-panel:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.action}"
    padding: "{spacing.standard}"
  artwork:
    backgroundColor: "{colors.elevated}"
    textColor: "{colors.muted}"
    rounded: "{rounded.artwork}"
  dialog:
    backgroundColor: "{colors.background}"
    textColor: "{colors.ink}"
    rounded: "{rounded.dialog}"
    padding: "{spacing.dialog-inset}"
---

# Design System: Astra TV

## Overview

**Creative North Star: "Quiet television, visible focus"**

Astra TV uses the brief-established charcoal and cyan identity for a remote-operated media library. Dark tonal surfaces, system typography, rounded actions and a strong focus color support ten-foot reading and D-pad selection. This is an implementation record, not a new creative direction.

This document was extracted from native Compose source. No approved visual comp was supplied, and the software emulator has not reached usable ADB; there are no emulator screenshots or physical-TV observations. Layout, clipping, text scaling, actual focus animation, remote traversal and playback overlays remain visually unverified. The finish review was source-only and contingent on successful final builds and tests; it is not rendered or device approval.

The north-star phrase describes the existing brief and implementation; it does not record a separately user-approved metaphor. The existing design seed has been refreshed in place to preserve its identity and capture implemented tokens.

**Key Characteristics:**
- Charcoal surfaces with restrained cyan focus.
- System Material typography and native Compose TV actions.
- 32dp display-safe content margins.
- Lazy lists and adaptive posters with explicit empty and recovery states.

## Colors

### Primary

- **Cyan accent** (`accent`): focused actions, input focus borders, loading indicators, playback progress and the guide's current-time marker. Focused actions use background-colored content.

### Neutral

- **Charcoal background** (`background`): the application shell, dialogs and playback control surfaces.
- **Dark surface** (`surface`): resting actions, playlist panels and guide lanes.
- **Raised charcoal** (`elevated`): selected actions, message panels and artwork placeholders.
- **Light ink** (`ink`): primary text and normal action content.
- **Muted slate** (`muted`): secondary information and artwork initials.
- **Quiet outline** (`outline`): unfocused field borders, dialog borders and progress tracks.
- **Video black** (`video-black`): the playback canvas behind the native video view.

**The Focus Contrast Rule.** A focused action uses cyan with dark content; keep its text and nested content inheriting the action's focus-aware content color.

These explicit mappings are implemented in both TV Material and Material 3 theme wrappers. The current app has one static dark scheme; dynamic color and a light scheme are not implemented. Error/recovery messages use the existing dark surfaces and readable text rather than an invented error palette.

## Typography

**Display and body font:** Android system sans-serif through the unmodified Material 3 typography defaults. No custom font asset or app-defined numeric type scale is present. Frontmatter records the family relationship only; source Material typography roles remain authoritative for effective sp size, weight, line height and letter spacing.

- **Headlines:** `headlineMedium` for section context; `headlineLarge` for the initial import prompt; `headlineSmall` for the brand, dialogs, empty states and playback title.
- **Titles:** `titleLarge` for panel titles and artwork initials; `titleMedium` for media rows; `titleSmall` for poster captions.
- **Body:** `bodyLarge` for first-run guidance, `bodyMedium` for profile/message context, `bodySmall` for metadata; unstyled Text uses Material's local text style.
- **Labels:** `labelLarge` for the common action label. Media titles truncate to one line in rows and two lines on posters.

**The Role Reuse Rule.** Extend the existing Material roles rather than adding per-screen hard-coded text sizes. TV readability and enlarged font settings still require emulator/device verification.

## Layout

The TV shell is a horizontal composition inside a display-safe inset (`safe-margin`). Its navigation column is 158dp wide, separated from the content by 28dp. The content header has 18dp bottom spacing. The category column is 154dp wide with an 18dp gap to the media area. Navigation itself scrolls vertically and uses 7dp gaps; categories use 8dp gaps. These are observed layout values, not a general breakpoint system.

Media rows use a lazy list with 9dp vertical gaps and 5dp content padding. Movie and series posters use an adaptive grid with a 155dp minimum cell width, 16dp gaps and 6dp content padding. Artwork uses a 2:3 aspect ratio in posters and a 48dp square in channel rows. Safe padding and list/grid focus buffers must remain when extending these surfaces.

Dialogs are bounded at 680dp width and 480dp height, with internal scrolling where implemented. First-run explanatory text is capped at 540dp width. Player messages and bottom controls use the same safe inset; the video itself fills the canvas. The implementation targets Android TV/Google TV; no phone/tablet layout or additional viewport breakpoint was established.

## Elevation & Depth

Depth comes primarily from background, surface and elevated tones. The source adds no custom drop-shadow system. Native TV button focus scaling/glow and dialog window behavior are inherited library defaults, not measured app tokens. Playback controls apply the background color at 96% opacity over video. Do not infer blur, gradients or additional elevation from this source-only record.

## Shapes

Actions, selected navigation, message surfaces and playlist panels share the action radius. Artwork placeholders use the smaller artwork radius; dialogs use the larger dialog radius and a 1dp outline. The artwork container has a rounded background, but the source does not explicitly clip downloaded artwork to that shape. Outlined fields retain Material's default shape rather than the action radius.

## Components

### TV actions and navigation

`TvAction` wraps the native TV Material Button with a minimum height of 48dp, horizontal 16dp and vertical 10dp content padding. Resting, selected and focused states use the frontmatter variants. Disabled treatment and focus animation remain TV library defaults; no custom duration or scale is pinned. The same primitive powers navigation, filters, settings, playback controls and save actions. Selection persists independently from transient focus.

The navigation column contains vertically scrollable named destinations. Focus groups/restorers and stable item IDs support list/grid return behavior; media actions record their focused item and request focus when that item reappears. This is source behavior, with complete remote traversal still awaiting runtime observation.

### Fields

`Field` uses Material 3 OutlinedTextField, single-line entry, a cyan focused outline, quiet unfocused outline and ink text. Password and numeric-PIN modes use the native input method and password transformation. Field dimensions, label animation and remaining states are library defaults.

### Media rows and posters

A row combines a flexible media action, artwork, a title/metadata column and a separate 82dp Save action. Nested text inherits focus-aware action content color. Posters put 2:3 artwork above a two-line title and optional viewing status. HTTPS artwork is loaded when available; missing or unsupported artwork shows title initials on the elevated background.

### Panels and dialogs

Playlist panels use the surface tone, 16dp padding and 12dp content gaps. `PanelDialog` uses the background tone, outlined rounded frame, 24dp padding and a headline. The shared dialog supports import, PINs, playlist management, profile editing/deletion, details, guide information and playback tracks. This documents the broader implemented use rather than the earlier seed's narrower intended use.

### Playback and recoverable states

The playback canvas is black; bottom controls are a dark 96%-opaque overlay with cyan progress and an outline-colored track. Play/pause, channel or seek controls, track selection and next-episode availability use native TV actions. Errors provide Retry and Back to library. Library messages offer Dismiss. Loading uses cyan progress indicators. Initial and empty-library states explain the next available action without decorative imagery.

## Do's and Don'ts

### Do:
- **Do** preserve visible cyan focus with dark focused content.
- **Do** keep library content and player controls within the established safe margins.
- **Do** use Material typography roles and the existing TvAction primitive.
- **Do** preserve keyed focus restoration, system Back and actionable recovery states.
- **Do** describe source evidence and device evidence separately.

### Don't:
- **Don't** add a phone-style bottom navigation to the TV shell.
- **Don't** invent provider artwork or metadata when it is unavailable.
- **Don't** replace native remote interaction with browser hover behavior.
- **Don't** claim screenshots, hardware approval or validated visual quality without that evidence.
