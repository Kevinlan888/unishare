package com.unishare.app.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationMotionTest {
    @Test
    fun top_level_tabs_never_inherit_detail_motion() {
        val routes = listOf("home", "devices", "history", "settings")
        for (initial in routes) {
            for (target in routes) {
                for (isPop in listOf(false, true)) {
                    assertEquals("$initial -> $target, pop=$isPop", NavigationMotion.None,
                        navigationMotion(initial, target, isPop))
                }
            }
        }
    }

    @Test
    fun detail_routes_use_hierarchical_motion_for_patterns_and_concrete_routes() {
        val cases = listOf(
            Case("home", "session/{deviceId}", false, NavigationMotion.ForwardDetail),
            Case("devices", "session/{deviceId}", false, NavigationMotion.ForwardDetail),
            Case("home", "session/cGMta2V2aW4", false, NavigationMotion.ForwardDetail),
            Case("devices", "session/cGMta2V2aW4", false, NavigationMotion.ForwardDetail),
            Case("home", "receive/{activityId}", false, NavigationMotion.ForwardDetail),
            Case("home", "receive/YWN0aXZpdHktMQ", false, NavigationMotion.ForwardDetail),
            Case("session/{deviceId}", "home", true, NavigationMotion.PopDetail),
            Case("session/{deviceId}", "devices", true, NavigationMotion.PopDetail),
            Case("session/cGMta2V2aW4", "history", true, NavigationMotion.PopDetail),
            Case("session/cGMta2V2aW4", "settings", true, NavigationMotion.PopDetail),
            Case("receive/{activityId}", "home", true, NavigationMotion.PopDetail),
            Case("receive/{activityId}", "devices", true, NavigationMotion.PopDetail),
            Case("receive/YWN0aXZpdHktMQ", "history", true, NavigationMotion.PopDetail),
            Case("receive/YWN0aXZpdHktMQ", "settings", true, NavigationMotion.PopDetail),
        )
        assertCases(cases)
    }

    @Test
    fun unsupported_or_unknown_transitions_have_no_motion() {
        assertCases(listOf(
            Case("home", "session/{deviceId}", true, NavigationMotion.None),
            Case("home", "receive/{activityId}", true, NavigationMotion.None),
            Case("session/{deviceId}", "home", false, NavigationMotion.None),
            Case("receive/{activityId}", "home", false, NavigationMotion.None),
            Case("session/{deviceId}", "receive/{activityId}", false, NavigationMotion.None),
            Case("receive/{activityId}", "session/{deviceId}", true, NavigationMotion.None),
            Case("unknown", "session/{deviceId}", false, NavigationMotion.None),
            Case("session/{deviceId}", "unknown", true, NavigationMotion.None),
            Case("home", "unknown", false, NavigationMotion.None),
            Case("home", "session-other/id", false, NavigationMotion.None),
            Case("home", "receive-other/id", false, NavigationMotion.None),
            Case(null, "session/{deviceId}", false, NavigationMotion.None),
            Case("session/{deviceId}", null, true, NavigationMotion.None),
            Case(null, null, false, NavigationMotion.None),
        ))
    }

    private fun assertCases(cases: List<Case>) {
        cases.forEach { case ->
            assertEquals("${case.initial} -> ${case.target}, pop=${case.isPop}", case.want,
                navigationMotion(case.initial, case.target, case.isPop))
        }
    }

    private data class Case(
        val initial: String?,
        val target: String?,
        val isPop: Boolean,
        val want: NavigationMotion,
    )
}
