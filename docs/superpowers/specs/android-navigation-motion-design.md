# Android Navigation Motion Design

## Scope

Apply hierarchical page motion only when navigating between one of the four top-level tabs and the full-screen Session or Receive destinations.

## Required behavior

- Switching among Share, Devices, History, and Settings remains instantaneous.
- Entering Session or Receive follows Android Settings-style hierarchy motion: the destination slides in from the right while the previous page shifts slightly left.
- No navigation transition changes alpha; there is no fade or crossfade.
- Popping Session or Receive moves the current page to the right while scaling from `1.0` toward `0.9` around its center and reveals the previous page underneath. The horizontal exit target is the full page width.
- The toolbar back button and system back use the same pop transition.
- A predictive-back gesture scrubs the horizontal and scale transition with gesture progress and restores the page if the gesture is cancelled.
- Use Navigation Compose's native predictive-back integration; do not add a custom `PredictiveBackHandler` or update dependencies.
- The pinned Navigation Compose 2.9.7 transition callbacks do not expose the swipe edge. Follow the LTR left-edge gesture toward the right; right-edge gestures use the same rightward transition rather than mirroring it.

## Verification

- Automated tests cover the route motion policy, immediate top-level switching, forward horizontal movement, and a rightward pop with shrinking rendered bounds and opaque color.
- Build, lint, unit tests, and connected tests pass.
- Verify the predictive-back gesture and toolbar back behavior on the connected physical Android phone.
