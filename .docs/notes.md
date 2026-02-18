# Project Notes

## Overview
capy is a custom Android app for moshizmoshill. It auto-returns the device to the home screen after a configurable inactivity timeout.

## How it works
- `MainActivity` — UI for setting the delay and starting/stopping the service
- `MinimizerOverlayService` — background foreground service that:
  - Draws a transparent `TYPE_APPLICATION_OVERLAY` window
  - Listens for `ACTION_OUTSIDE` touch events to reset the inactivity timer
  - After timeout, fires `Intent.ACTION_MAIN / CATEGORY_HOME` to go home

## Known constraints
- Requires `SYSTEM_ALERT_WINDOW` (overlay) permission — prompted on first launch
- Requires `FOREGROUND_SERVICE_SPECIAL_USE` for Android 14+
- Minimum Android 8.1 (API 27) due to `TYPE_APPLICATION_OVERLAY` usage

## Logo
Target: cute capybara icon — relaxed pose, flat/minimal style to match Material3.
