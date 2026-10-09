package com.unishare.app.presentation.home

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.core.view.WindowCompat
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareErrorKind
import com.unishare.app.presentation.navigation.UniShareNavHost
import com.unishare.app.presentation.session.SessionScreen
import com.unishare.app.presentation.session.SessionUiState
import com.unishare.app.presentation.theme.UniShareTheme
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class HomeScreenTest(
    private val languageTag: String,
    private val copy: Copy,
) {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun home_matches_localized_shell_and_summary_contract() {
        setHomeContent()

        composeRule.onNode(hasText(copy.share) and isSelected()).assertIsDisplayed().assertIsSelected()
        composeRule.onNodeWithText(copy.devices).assertIsDisplayed()
        composeRule.onNodeWithText(copy.history).assertIsDisplayed()
        composeRule.onNodeWithText(copy.settings).assertIsDisplayed()
        composeRule.onNodeWithText(copy.thisDevice).assertIsDisplayed()
        composeRule.onNodeWithText("Pixel 10").assertIsDisplayed()
        composeRule.onNodeWithText(copy.recent).assertIsDisplayed()
        composeRule.onNodeWithText("Kevin's Windows PC").assertIsDisplayed()
        composeRule.onNodeWithText("project.zip · 72%").assertIsDisplayed()
        composeRule.onNodeWithText(copy.nearby).assertIsDisplayed()
        composeRule.onNodeWithText(copy.trustedOnline).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(copy.trustedOnline).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(copy.refresh).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(copy.newShare).assertIsDisplayed()
    }

    @Test
    fun refresh_fab_and_bottom_tabs_are_functional() {
        var refreshes = 0
        setHomeContent(onRefresh = { refreshes++ })

        composeRule.onNodeWithContentDescription(copy.refresh).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, refreshes) }

        composeRule.onNodeWithContentDescription(copy.newShare).performClick()
        composeRule.onNodeWithText(copy.devicesTitle).assertIsDisplayed()
        composeRule.onNode(hasText(copy.devices) and isSelected()).assertIsSelected()

        composeRule.onNodeWithText(copy.history).performClick()
        composeRule.onNodeWithText(copy.historyTitle).assertIsDisplayed()
        composeRule.onNode(hasText(copy.history) and isSelected()).assertIsSelected()
    }

    @Test
    fun top_level_tab_switch_replaces_content_without_a_crossfade() {
        composeRule.mainClock.autoAdvance = false
        setHomeContent()

        composeRule.onNodeWithText(copy.devices).performClick()
        composeRule.mainClock.advanceTimeBy(100)
        val destinationTitle = composeRule.onNodeWithText(copy.devicesTitle).assertExists()
        val firstRenderedFrame = destinationTitle.captureToImage().asAndroidBitmap()

        composeRule.mainClock.advanceTimeBy(1_000)
        val settledFrame = destinationTitle.captureToImage().asAndroidBitmap()

        assertTrue(
            "top-level content must already match its settled frame without a crossfade",
            firstRenderedFrame.sameAs(settledFrame),
        )
    }

    @Test
    fun forward_session_moves_in_from_the_right_and_shifts_the_previous_page_left() {
        composeRule.mainClock.autoAdvance = false
        setMotionContent()
        composeRule.onNodeWithText(copy.devices).performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        val prior = composeRule.onNodeWithTag("motion_prior")
        val priorSettled = prior.fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithText("Open motion session").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        val detail = composeRule.onNodeWithTag("motion_detail")
        val detailMidFrame = detail.fetchSemanticsNode().boundsInRoot
        val priorMidFrame = prior.fetchSemanticsNode().boundsInRoot
        val detailMidImage = detail.captureToImage().asAndroidBitmap()
        val rootMidImage = composeRule.onRoot().captureToImage().asAndroidBitmap()
        composeRule.mainClock.advanceTimeBy(1_000)
        val detailSettled = detail.fetchSemanticsNode().boundsInRoot

        assertTrue("detail must enter from the right", detailMidFrame.left > detailSettled.left)
        assertTrue("prior page must shift left", priorMidFrame.left < priorSettled.left)
        assertEquals("entering detail must keep its opaque blue color", 0xff0000ff.toInt(),
            detailMidImage.getPixel(detailMidImage.width / 2, detailMidImage.height / 2))
        assertEquals("visible previous page must keep its opaque red color", 0xffff0000.toInt(),
            rootMidImage.getPixel(1, rootMidImage.height / 2))
    }

    @Test
    fun home_fab_waits_until_the_popped_session_is_no_longer_visible() {
        composeRule.mainClock.autoAdvance = false
        setHomeContent(
            sessionContent = { _, onBack ->
                Box(Modifier.fillMaxSize().testTag("motion_detail").background(Color.Blue)) {
                    Button(onClick = onBack, modifier = Modifier.align(Alignment.TopCenter)) {
                        Text("Back from motion session")
                    }
                }
            },
        )
        composeRule.onNodeWithContentDescription(copy.newShare).assertIsDisplayed()
        composeRule.onNodeWithText("Kevin's Windows PC").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithContentDescription(copy.newShare).assertDoesNotExist()

        composeRule.onNodeWithText("Back from motion session").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.onNodeWithTag("motion_detail").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(copy.newShare).assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithTag("motion_detail").assertDoesNotExist()
        composeRule.onNodeWithContentDescription(copy.newShare).assertIsDisplayed()
    }

    @Test
    fun pop_session_moves_right_and_shrinks_the_current_page_without_fading_its_color() {
        composeRule.mainClock.autoAdvance = false
        setMotionContent()
        composeRule.onNodeWithText(copy.devices).performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText("Open motion session").performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        val detail = composeRule.onNodeWithTag("motion_detail")
        val settledBounds = detail.fetchSemanticsNode().boundsInRoot
        val settledImage = detail.captureToImage().asAndroidBitmap()

        composeRule.onNodeWithText("Back from motion session").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        val midBounds = detail.fetchSemanticsNode().boundsInRoot
        val midImage = detail.captureToImage().asAndroidBitmap()

        assertTrue("detail center must move right on pop", midBounds.center.x > settledBounds.center.x + 1f)
        assertTrue("detail left edge must move right on pop", midBounds.left > settledBounds.left)
        assertTrue("detail width must shrink on pop", midBounds.width < settledBounds.width)
        assertTrue("detail height must shrink on pop", midBounds.height < settledBounds.height)
        assertEquals("solid detail color must stay opaque during pop",
            settledImage.getPixel(settledImage.width / 2, settledImage.height / 2),
            midImage.getPixel(midImage.width / 2, midImage.height / 2))
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.onNodeWithText("Open motion session").assertIsDisplayed()
    }

    @Test
    fun device_route_restores_nested_destination_and_hides_bottom_navigation() {
        val restoration = StateRestorationTester(composeRule)
        setHomeContent(restoration = restoration)

        composeRule.onNodeWithText("Kevin's Windows PC").performClick()
        composeRule.onNodeWithText(copy.sessionTitle).assertIsDisplayed()
        composeRule.onNodeWithText("pc-kevin").assertIsDisplayed()
        composeRule.onNodeWithText(copy.share).assertDoesNotExist()

        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(copy.sessionTitle).assertIsDisplayed()
        composeRule.onNodeWithText("pc-kevin").assertIsDisplayed()
        composeRule.onNodeWithText(copy.share).assertDoesNotExist()
    }

    @Test
    fun nested_session_applies_the_status_bar_inset_only_once() {
        var safeTopPx = 0f
        var densityPxPerDp = 0f
        setHomeContent(
            edgeToEdge = true,
            sessionContent = { deviceId, onBack ->
                val density = LocalDensity.current
                safeTopPx = WindowInsets.safeDrawing.getTop(density).toFloat()
                densityPxPerDp = density.density
                SessionScreen(
                    state = SessionUiState(
                        deviceId = deviceId,
                        deviceName = "Kevin's Windows PC",
                        platform = DevicePlatform.WINDOWS,
                        online = true,
                        trusted = true,
                    ),
                    onBack = onBack,
                    onMore = {},
                    onComposerChanged = {},
                    onSend = {},
                    onAddAttachment = {},
                    onDismissShareSheet = {},
                    onPickPhoto = {},
                    onPickFile = {},
                    onClipboard = {},
                    onText = {},
                    onLink = {},
                    onCancel = {},
                    onRetry = {},
                )
            },
        )

        composeRule.onNodeWithText("Kevin's Windows PC").performClick()

        composeRule.onNodeWithTag("session_header")
            .assertHeightIsEqualTo(64.dp)
        val expectedControlCenterY = safeTopPx + 32.dp.value * densityPxPerDp
        listOf(copy.back, copy.more).forEach { description ->
            val control = composeRule.onNodeWithContentDescription(description)
                .assertWidthIsEqualTo(48.dp)
                .assertHeightIsEqualTo(48.dp)
                .fetchSemanticsNode().boundsInRoot
            assertEquals(
                "$description should be centered in the 64dp session app bar below the safe inset",
                expectedControlCenterY,
                control.center.y,
                1f,
            )
        }
    }

    @Test
    fun nearby_loading_error_empty_and_content_states_are_exclusive() {
        val state = mutableStateOf(
            HomeUiState(
                isLoading = true,
                thisDevice = ThisDeviceUi("Pixel 10", ThisDeviceStatus.STARTING),
                discoveryError = ShareErrorKind.DISCOVERY_FAILED,
            ),
        )
        setHomeContent(
            stateProvider = { state.value },
        )

        composeRule.onNodeWithText(copy.loading).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy.discoveryError).assertDoesNotExist()
        composeRule.onNodeWithText(copy.noNearby).assertDoesNotExist()

        composeRule.runOnIdle { state.value = state.value.copy(isLoading = false) }
        composeRule.onNodeWithText(copy.discoveryError).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy.loading).assertDoesNotExist()
        composeRule.onNodeWithText(copy.noNearby).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(copy.refresh).assertExists()

        composeRule.runOnIdle { state.value = state.value.copy(discoveryError = null) }
        composeRule.onNodeWithText(copy.noNearby).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy.loading).assertDoesNotExist()
        composeRule.onNodeWithText(copy.discoveryError).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(copy.noNearby).assertDoesNotExist()
        composeRule.onNodeWithContentDescription(copy.noRecent).assertDoesNotExist()

        composeRule.runOnIdle { state.value = state.value.copy(nearby = populatedState().nearby) }
        composeRule.onNodeWithText("Alice's iPhone").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy.noNearby).assertDoesNotExist()
    }

    @Test
    fun grouped_activity_rows_are_stacked_without_overlap() {
        val first = populatedState().recent.single()
        setHomeContent(
            state = populatedState().copy(
                recent = listOf(
                    first.copy(deviceName = "First recent device"),
                    first.copy(
                        activityId = ActivityKey("activity-2"),
                        deviceId = DeviceKey("pc-second"),
                        deviceName = "Second recent device",
                    ),
                ),
                nearby = emptyList(),
            ),
        )

        val firstBounds = composeRule.onNodeWithText("First recent device").fetchSemanticsNode().boundsInRoot
        val secondBounds = composeRule.onNodeWithText("Second recent device").fetchSemanticsNode().boundsInRoot
        assertTrue("second row must start below the first row", secondBounds.top >= firstBounds.bottom)
    }

    @Test
    fun key_actions_keep_touch_targets_and_remain_reachable_at_two_hundred_percent_font_scale() {
        setHomeContent(fontScale = 2f)

        listOf(copy.search, copy.more, copy.newShare).forEach { description ->
            composeRule.onNodeWithContentDescription(description)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNodeWithContentDescription(copy.refresh)
            .performScrollTo()
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText(copy.settings).assertIsDisplayed()
        composeRule.onNode(hasText(copy.share) and isSelected()).assertIsDisplayed()
    }

    private fun setMotionContent() {
        setHomeContent(
            devicesContent = { open ->
                Box(Modifier.fillMaxSize().background(Color.Red), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(80.dp).testTag("motion_prior").background(Color.Green))
                    Button(onClick = { open(DeviceKey("motion-device")) },
                        modifier = Modifier.align(Alignment.TopCenter)) {
                        Text("Open motion session")
                    }
                }
            },
            sessionContent = { _, onBack ->
                Box(Modifier.fillMaxSize().testTag("motion_detail").background(Color.Blue)) {
                    Button(onClick = onBack, modifier = Modifier.align(Alignment.TopCenter)) {
                        Text("Back from motion session")
                    }
                }
            },
        )
    }

    private fun setHomeContent(
        state: HomeUiState = populatedState(),
        stateProvider: (() -> HomeUiState)? = null,
        onRefresh: () -> Unit = {},
        fontScale: Float = 1f,
        restoration: StateRestorationTester? = null,
        sessionContent: (@Composable (DeviceKey, () -> Unit) -> Unit)? = null,
        devicesContent: (@Composable ((DeviceKey) -> Unit) -> Unit)? = null,
        edgeToEdge: Boolean = false,
    ) {
        val context = composeRule.activity
        if (edgeToEdge) {
            composeRule.activityRule.scenario.onActivity { activity ->
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            }
        }
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTag))
        }
        val localizedContext = context.createConfigurationContext(configuration)
        val content = @androidx.compose.runtime.Composable {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                UniShareTheme(darkTheme = false) {
                    UniShareNavHost(
                        homeUiState = stateProvider?.invoke() ?: state,
                        onRefresh = onRefresh,
                        sessionContent = sessionContent,
                        devicesContent = devicesContent,
                    )
                }
            }
        }
        if (restoration == null) composeRule.setContent(content) else restoration.setContent(content)
    }

    private fun populatedState() = HomeUiState(
        thisDevice = ThisDeviceUi("Pixel 10", ThisDeviceStatus.ONLINE),
        recent = listOf(
            RecentActivityUi(
                activityId = ActivityKey("activity-1"),
                deviceId = DeviceKey("pc-kevin"),
                deviceName = "Kevin's Windows PC",
                platform = DevicePlatform.WINDOWS,
                displayName = "project.zip",
                status = ActivityStatus.TRANSFERRING,
                progressPercent = 72,
                updatedAtMillis = 1_799_999_940_000,
            ),
        ),
        nearby = listOf(
            NearbyDeviceUi(
                id = DeviceKey("phone-alice"),
                name = "Alice's iPhone",
                platform = DevicePlatform.IOS,
                online = true,
                trusted = true,
            ),
        ),
    )

    data class Copy(
        val share: String,
        val devices: String,
        val history: String,
        val settings: String,
        val thisDevice: String,
        val recent: String,
        val nearby: String,
        val trustedOnline: String,
        val refresh: String,
        val newShare: String,
        val search: String,
        val more: String,
        val devicesTitle: String,
        val historyTitle: String,
        val sessionTitle: String,
        val back: String,
        val loading: String,
        val discoveryError: String,
        val noRecent: String,
        val noNearby: String,
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "locale={0}")
        fun locales() = listOf(
            arrayOf(
                "en-US",
                Copy(
                    "Share", "Devices", "History", "Settings", "This device", "Recent",
                    "Nearby Devices", "Trusted · Online", "Refresh nearby devices", "New share",
                    "Search", "More options", "Your devices", "Transfer history", "Device session",
                    "Back",
                    "Finding nearby devices…", "Nearby discovery is unavailable.",
                    "No recent activity", "No nearby devices",
                ),
            ),
            arrayOf(
                "zh-CN",
                Copy(
                    "分享", "设备", "历史", "设置", "本机设备", "最近活动", "附近设备",
                    "已信任 · 在线", "刷新附近设备", "新建分享", "搜索", "更多选项", "设备管理", "传输历史",
                    "设备会话", "返回", "正在查找附近设备…", "附近设备发现暂不可用。", "暂无最近活动", "暂无附近设备",
                ),
            ),
        )
    }
}
