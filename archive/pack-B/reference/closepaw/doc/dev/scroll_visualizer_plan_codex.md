# Scroll Visualizer Implementation Plan

## Goal

Add visual feedback for accessibility-backed `ScrollNodeAt` actions only.

## Phase 1

Affected files:
- `app/src/main/kotlin/ai/closepaw/platform/AccessibilityPlatform.kt`
- `app/src/main/kotlin/ai/closepaw/platform/ScrollVisualizationGeometry.kt`
- `app/src/test/kotlin/ai/closepaw/platform/ScrollVisualizationGeometryTest.kt`
- `doc/main/infra/platform.md`
- `doc/main/ui/overlay.md`
- `doc/PROGRESS.md`

Key decisions:
- Keep the change scoped to a11y scroll visualization.
- Reuse the existing `ActionVisualizerManager.showScrollAsSwipe()` API.
- Render a short canonical scroll trail centered on the scroll target before the node scroll action executes.
- Preserve current scroll semantics: `down` means reveal content below, so the visual finger trail moves upward.
- Do not add type visualization.
