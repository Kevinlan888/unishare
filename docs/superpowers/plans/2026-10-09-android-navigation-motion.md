# Android Navigation Motion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace nested-page crossfades with Android Settings-style forward motion and predictive-back motion that moves right while scaling down, keeping all four top-level tabs instantaneous.

**Architecture:** Introduce a small route-level motion classifier that distinguishes top-level tab switches, forward detail navigation, and detail pops. `UniShareNavHost` maps that policy to Navigation Compose transitions, allowing Navigation Compose 2.9.7 to seek the pop transition during predictive back without a custom back handler.

**Tech Stack:** Kotlin, Jetpack Compose animation, Navigation Compose 2.9.7, JUnit 4, Compose UI instrumentation tests.

**Spec:** `docs/superpowers/specs/android-navigation-motion-design.md`

## Global Constraints

- The four bottom tabs must switch instantly with `EnterTransition.None` and `ExitTransition.None`.
- Entering Session or Receive must slide the destination from the right and shift the previous page slightly left without changing alpha.
- Popping Session or Receive must move right toward a full-width horizontal exit while scaling toward exactly `0.9f` around its center and reveal the previous page without changing alpha.
- Toolbar back and system/predictive back must share the same Navigation Compose pop transition.
- Do not add a custom `PredictiveBackHandler`, change dependency versions, or animate dialogs and bottom sheets.
- Follow the LTR left-edge gesture toward the right. Navigation Compose 2.9.7 does not expose the gesture edge to transition callbacks, so right-edge gestures cannot mirror the transition under these constraints.

## Review Focus

- A top-level-to-top-level navigation must never inherit the host animation.
- Both Session and Receive patterns must be classified as detail destinations despite encoded path arguments.
- A detail pop must reveal the actual previous top-level entry rather than navigating to a new one.
- Cancelling a predictive-back gesture must leave the current destination active and full-size.
- Transition code must not introduce any `fadeIn` or `fadeOut` component.

---

### Task 1: Route-aware hierarchical and predictive navigation motion

**Files:**
- Create: `android/app/src/main/java/com/unishare/app/presentation/navigation/NavigationMotion.kt`
- Create: `android/app/src/test/java/com/unishare/app/presentation/navigation/NavigationMotionTest.kt`
- Modify: `android/app/src/main/java/com/unishare/app/presentation/navigation/UniShareNavHost.kt`
- Modify: `android/app/src/androidTest/java/com/unishare/app/presentation/home/HomeScreenTest.kt`

**Interfaces:**
- Consumes: `AppDestination` route constants and Navigation Compose transition callbacks.
- Produces: `navigationMotion(initialRoute: String?, targetRoute: String?, isPop: Boolean): NavigationMotion`; `NavigationMotion` values `None`, `ForwardDetail`, and `PopDetail`.

- [ ] **Step 1: Write failing unit tests for motion classification**

Add table-driven tests asserting literal outcomes for all four top-level tab pairs, Home/Devices to Session, Home to Receive, Session/Receive back to top-level, and unknown/null routes. These tests catch accidental inheritance of detail animation by tabs and failure to recognize dynamic route patterns.

- [ ] **Step 2: Run the focused unit test and verify RED**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.unishare.app.presentation.navigation.NavigationMotionTest`

Expected: compilation failure because `NavigationMotion` and `navigationMotion` do not exist.

- [ ] **Step 3: Implement the minimal motion classifier**

Create `NavigationMotion.kt` with the exact interface above. Recognize top-level routes and the `session/` and `receive/` route prefixes; return `None` for top-level switches and unsupported combinations, `ForwardDetail` only for a non-pop transition into a detail destination, and `PopDetail` only for a pop from a detail destination to a top-level destination.

- [ ] **Step 4: Run the focused unit test and verify GREEN**

Run the Step 2 command and expect all `NavigationMotionTest` tests to pass.

- [ ] **Step 5: Write failing Compose instrumentation tests for observable motion**

Extend `HomeScreenTest` using solid tagged injected content and a paused Compose clock. Assert that at a forward mid-frame the detail content's left bound is right of its settled left bound while the prior page has shifted left, that a mid-pop frame moves its center right by more than one pixel and makes rendered bounds smaller without alpha disappearance, and that the existing top-level no-crossfade test remains unchanged. Include Session and Receive coverage through the classifier unit table; use Session for rendered geometry to avoid duplicating framework tests. The center check prevents stationary center scaling from falsely satisfying a left-bound-only assertion.

- [ ] **Step 6: Run the focused instrumentation tests and verify RED**

Run: `./gradlew.bat :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.unishare.app.presentation.home.HomeScreenTest`

Expected: the new forward and pop geometry assertions fail because the current nested navigation uses the default crossfade. For the user's direction revision, also verify the center-movement assertion fails against the initial stationary scale implementation before adding horizontal pop motion.

- [ ] **Step 7: Map motion policy to Navigation Compose transitions**

Update `UniShareNavHost.kt` so host transitions use the classifier. Forward detail navigation uses `slideInHorizontally` from full width and `slideOutHorizontally` to one quarter width left. Pop combines `slideOutHorizontally(targetOffsetX = { it })` with `scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin.Center)` for the current detail and `EnterTransition.None` for the previous page. Use no fade primitives. Preserve explicit `None` transitions for top-level destinations so all four tabs remain instantaneous.

- [ ] **Step 8: Run focused tests, then the complete Android verification suite**

Run the focused unit and instrumentation commands, then `./gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and `./gradlew.bat :app:connectedDebugAndroidTest`. Expect all commands to pass without new warnings or failures.

- [ ] **Step 9: Verify on the connected physical phone**

Install the debug APK on device `62041JEA316847`. Verify entry to Session uses right-to-left hierarchical motion, toolbar back moves right while scaling out, predictive-back scrubs both horizontal movement and scale to reveal the previous page, and cancelling the gesture restores Session.

- [ ] **Step 10: Commit**

Commit the spec, plan, production code, and tests with message `feat(android): add predictive navigation motion`.
