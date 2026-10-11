package com.unishare.app.presentation

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.core.view.WindowCompat
import androidx.room.Room
import com.unishare.app.data.history.ActivityDatabase
import com.unishare.app.data.history.RoomActivityRepository
import com.unishare.app.MainActivity
import com.unishare.app.UniShareApplication
import com.unishare.app.data.settings.*
import com.unishare.app.presentation.format.DisplayFormatters
import com.unishare.app.presentation.receive.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import com.unishare.app.data.device.DeviceSummary
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.data.history.HistoryQuery
import com.unishare.app.domain.*
import com.unishare.app.presentation.devices.*
import com.unishare.app.presentation.history.*
import com.unishare.app.presentation.home.HomeUiState
import com.unishare.app.presentation.home.HomeScreen
import com.unishare.app.presentation.home.RecentActivityUi
import com.unishare.app.presentation.navigation.UniShareNavHost
import com.unishare.app.presentation.session.*
import com.unishare.app.presentation.settings.*
import com.unishare.app.presentation.theme.UniShareTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class AppAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val peer = DeviceSummary(DeviceKey("pc"), "Studio PC", DevicePlatform.WINDOWS, true, true)
    private val item = ShareActivity(ActivityKey("one"), peer.id, ShareDirection.INCOMING, ContentKind.FILE, ShareOrigin.DIRECT, "report.pdf", "application/pdf", 1024, 1_800_000_000_000, 1_800_000_000_000, status = ActivityStatus.COMPLETED)

    @Test fun navigation_displays_real_management_pages_and_captures_them() {
        setContent {
            UniShareNavHost(HomeUiState(), {},
                devicesContent = { open -> DevicesScreen(DevicesUiState(trusted = listOf(peer), nearby = listOf(peer.copy(id = DeviceKey("phone"), name = "Pixel 10", platform = DevicePlatform.ANDROID, trusted = false)), offline = listOf(peer.copy(id = DeviceKey("laptop"), name = "Travel laptop", online = false))), {}, {}, {}, {}, open) },
                historyContent = { HistoryScreen(HistoryUiState(rows = listOf(HistoryRow(item, "Studio PC", false)), devices = listOf(peer)), {}, {}) },
                settingsContent = { SettingsScreen(SettingsUiState(), {}, {}, {}, {}, {}, {}) })
        }
        rule.onNodeWithText("Devices").performClick()
        rule.onNodeWithText("Trusted devices").assertIsDisplayed()
        rule.onNodeWithText("Studio PC").assertIsDisplayed()
        screenshot("task11-devices.png")
        rule.onNodeWithText("History").performClick()
        rule.onNodeWithText("Search history").assertIsDisplayed()
        rule.onNodeWithText("report.pdf").performScrollTo().assertIsDisplayed()
        screenshot("task11-history.png")
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Device name").assertIsDisplayed()
        rule.onNodeWithText("UniShare Downloads").assertIsDisplayed()
        screenshot("task11-settings.png")
    }
    @Test fun icon_actions_have_labels_roles_and_minimum_touch_size() {
        setContent { DevicesScreen(DevicesUiState(trusted = listOf(peer)), {}, {}, {}, {}, {}) }
        rule.onNodeWithContentDescription("Forget Studio PC")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        rule.onNodeWithText("Trusted · Online").assertIsDisplayed()
    }
    @Test fun forget_confirmation_preserves_cancel_and_confirm_actions() {
        setContent { DevicesScreen(DevicesUiState(trusted = listOf(peer), pendingForget = peer), {}, {}, {}, {}, {}) }
        rule.onNodeWithText("Cancel").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Forget").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
    }
    @Test fun traversal_indices_follow_the_visible_device_order() {
        setContent { DevicesScreen(DevicesUiState(trusted = listOf(peer), nearby = listOf(peer.copy(id = DeviceKey("phone"), name = "Phone", trusted = false))), {}, {}, {}, {}, {}) }
        val first = rule.onNodeWithTag("device-pc", useUnmergedTree = true).fetchSemanticsNode()
        val second = rule.onNodeWithTag("device-phone", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue(first.boundsInRoot.bottom <= second.boundsInRoot.top)
        assertTrue(first.config[SemanticsProperties.TraversalIndex] < second.config[SemanticsProperties.TraversalIndex])
        assertTrue(first.config[SemanticsProperties.IsTraversalGroup])
        val nearbyHeading = rule.onNodeWithText("Nearby devices").fetchSemanticsNode()
        val headingIndex = nearbyHeading.config.getOrElse(SemanticsProperties.TraversalIndex) { 0f }
        assertTrue(first.config[SemanticsProperties.TraversalIndex] < headingIndex)
        assertTrue(headingIndex < second.config[SemanticsProperties.TraversalIndex])
    }
    @Test fun terminal_state_announces_but_progress_ticks_do_not() {
        val state = mutableStateOf(ActivityStatus.TRANSFERRING)
        val bytes = mutableStateOf(512L)
        setContent {
            ActivityCard(SessionActivityUi(ActivityKey("one"), ShareDirection.OUTGOING, ContentKind.FILE, displayName = "report.pdf", mimeType = "application/pdf", byteCount = 1024, transferredBytes = bytes.value, status = state.value, timestampMillis = 100), {}, {})
        }
        val live = SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)
        rule.onAllNodes(live).assertCountEquals(0)
        rule.runOnIdle { bytes.value = 768 }
        rule.onAllNodes(live).assertCountEquals(0)
        rule.runOnIdle { state.value = ActivityStatus.COMPLETED }
        rule.onNodeWithContentDescription("Transfer completed").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }
    @Test fun missing_content_is_disabled_and_explained() {
        setContent { HistoryScreen(HistoryUiState(rows = listOf(HistoryRow(item, "Studio PC", false))), {}, {}) }
        rule.onNodeWithText("Content unavailable").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Open").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Transfer completed").assertExists()
    }
    @Test fun denied_settings_offer_system_recovery_with_background_switch_off() {
        var opens = 0
        setContent { SettingsScreen(SettingsUiState(error = SettingsError.NOTIFICATION_DENIED), {}, {}, {}, {}, {}, {}, onNotificationSettings = { opens++ }) }
        rule.onNodeWithText("Open notification settings").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { assertEquals(1, opens) }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Background receiving"))
        rule.onNodeWithText("Background receiving").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOff()
    }
    @Test fun critical_actions_are_reachable_at_200_percent_in_portrait() = largeTextActions(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    @Test fun critical_actions_are_reachable_at_200_percent_in_landscape() = largeTextActions(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
    @Test fun room_filters_all_dimensions_and_never_searches_raw_paths() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(rule.activity, ActivityDatabase::class.java).build()
        try {
            val repository = RoomActivityRepository(database, { item.updatedAtMillis })
            repository.upsert(item.copy(displayName = "C:\\private\\report.pdf\u0000"))
            repository.upsert(item.copy(id = ActivityKey("phone"), deviceId = DeviceKey("phone")))
            repository.upsert(item.copy(id = ActivityKey("out"), direction = ShareDirection.OUTGOING))
            repository.upsert(item.copy(id = ActivityKey("text"), kind = ContentKind.TEXT))
            repository.upsert(item.copy(id = ActivityKey("failed"), status = ActivityStatus.FAILED))
            val query = HistoryQuery("REPORT", peer.id, ShareDirection.INCOMING, ContentKind.FILE, ActivityStatus.COMPLETED)
            assertEquals(listOf("one"), repository.observeHistory(query).first().map { it.id.value })
            assertEquals(listOf("one"), database.activityDao().observeFiltered("pc", "INCOMING", "FILE", "COMPLETED").first().map { it.id })
            assertEquals(listOf("pc", "phone"), database.activityDao().observeDeviceIds().first())
            assertEquals(listOf("pc", "phone"), repository.observeDeviceIds().first().map { it.value })
            assertTrue(repository.observeHistory(HistoryQuery("private")).first().isEmpty())
        } finally { database.close() }
    }
    @Test fun binary_units_are_used_in_session_cards_and_receive_metadata() {
        val receive = mutableStateOf(false)
        setContent {
            if (receive.value) ReceiveScreen(ReceiveUiState(item.id, byteCount = 1024, phase = ReceivePhase.PENDING), {}, {}, {}, {}, {}, {})
            else ActivityCard(SessionActivityUi(item.id, item.direction, item.kind, displayName = item.displayName, mimeType = item.mimeType, byteCount = 1024, transferredBytes = 1024, status = item.status, timestampMillis = 100), {}, {})
        }
        rule.onNodeWithText("1 KiB").assertIsDisplayed()
        rule.runOnIdle { receive.value = true }
        rule.onNodeWithText("1 KiB", substring = true).assertIsDisplayed()
    }
    @Test fun byte_and_date_formatting_follow_the_selected_locale() {
        fun context(tag: String) = rule.activity.createConfigurationContext(Configuration(rule.activity.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
        assertEquals("1.5 KiB", DisplayFormatters.bytes(context("en-US"), 1536))
        assertEquals("1,5 KiB", DisplayFormatters.bytes(context("de-DE"), 1536))
        assertEquals("512 字节", DisplayFormatters.bytes(context("zh-CN"), 512))
        assertNotEquals(DisplayFormatters.timestamp(context("en-US"), 1_700_000_000_000), DisplayFormatters.timestamp(context("zh-CN"), 1_700_000_000_000))
    }
    @Test fun main_activity_uses_real_repositories_and_applies_language_and_theme() {
        val settings = UniShareApplication.graph(rule.activity).settingsRepository
        val original = runBlocking { settings.settings.first() }
        try {
            runBlocking { settings.setLanguage(AppLanguage.ENGLISH); settings.setTheme(AppTheme.LIGHT) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                rule.onNodeWithText("Devices").performClick()
                rule.onNodeWithText("Trusted devices").assertIsDisplayed()
                rule.onNodeWithText("History").performClick()
                rule.onNodeWithText("Search history").assertIsDisplayed()
                rule.onNodeWithText("Settings").performClick()
                rule.onNodeWithText("Device name").assertIsDisplayed()
                scenario.onActivity { activity ->
                    val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    assertTrue(bars.isAppearanceLightStatusBars)
                    assertTrue(bars.isAppearanceLightNavigationBars)
                }
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Language: English"))
                rule.onNodeWithText("Language: English").performClick()
                rule.onNodeWithText("简体中文").performClick()
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("保存名称"))
                rule.onNodeWithText("保存名称").assertIsDisplayed()
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("主题：浅色"))
                rule.onNodeWithText("主题：浅色").performClick()
                screenshot("task11-main-theme-menu-zh.png")
                val menuTexts = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
                    .fetchSemanticsNodes().flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }
                assertTrue("Theme popup must follow the selected language: $menuTexts", "深色" in menuTexts)
                rule.onNodeWithText("深色").performClick()
                rule.onNodeWithText("主题：深色").assertIsDisplayed()
                assertEquals(AppLanguage.SIMPLIFIED_CHINESE, runBlocking { settings.settings.first() }.language)
                assertEquals(AppTheme.DARK, runBlocking { settings.settings.first() }.theme)
                scenario.onActivity { activity ->
                    val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                    assertFalse(bars.isAppearanceLightStatusBars)
                    assertFalse(bars.isAppearanceLightNavigationBars)
                }
                screenshot("task11-main-settings-zh-dark.png")
            }
        } finally {
            runBlocking { settings.setLanguage(original.language); settings.setTheme(original.theme) }
        }
    }
    @Test fun production_language_scope_can_enter_real_session_route_with_picker_owners() {
        val graph = UniShareApplication.graph(rule.activity)
        val original = runBlocking { graph.settingsRepository.settings.first() }
        val now = System.currentTimeMillis()
        val activity = item.copy(id = ActivityKey("task11-locale-session"), displayName = "task11-session.pdf", createdAtMillis = now, updatedAtMillis = now)
        val database = Room.databaseBuilder(rule.activity, ActivityDatabase::class.java, "unishare-activity.db").build()
        try {
            runBlocking {
                graph.settingsRepository.setLanguage(AppLanguage.ENGLISH)
                graph.activityRepository.upsert(activity)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                rule.onNodeWithText("task11-session.pdf").assertIsDisplayed()
                runBlocking { graph.settingsRepository.setLanguage(AppLanguage.SIMPLIFIED_CHINESE) }
                rule.onAllNodesWithText("分享").assertCountEquals(2)
                rule.onNodeWithText("task11-session.pdf").performClick()
                rule.onNodeWithContentDescription("添加附件").assertIsDisplayed()
                scenario.recreate() // Lifecycle/saved-state owners must restore the real route and its picker.
                rule.onNodeWithContentDescription("添加附件").assertIsDisplayed().performClick()
                rule.onNodeWithText("照片").assertIsDisplayed()
                rule.onNodeWithText("文件").assertIsDisplayed()
                rule.onNodeWithText("文本").performClick()
                androidx.test.espresso.Espresso.pressBack()
                rule.onNodeWithText("task11-session.pdf").assertIsDisplayed()
            }
        } finally {
            runBlocking {
                graph.settingsRepository.setLanguage(original.language)
                database.openHelper.writableDatabase.execSQL("DELETE FROM activities WHERE id = ?", arrayOf(activity.id.value))
            }
            database.close()
        }
    }
    @Test fun generic_home_and_history_summaries_follow_locale_but_real_names_never_translate() {
        val page = mutableStateOf(false)
        val chinese = mutableStateOf(false)
        val entries = listOf(
            item.copy(id = ActivityKey("text"), kind = ContentKind.TEXT, displayName = "Text"),
            item.copy(id = ActivityKey("image"), kind = ContentKind.IMAGE, displayName = ""),
            item.copy(id = ActivityKey("file"), kind = ContentKind.FILE, displayName = ""),
            item.copy(id = ActivityKey("real"), kind = ContentKind.FILE, displayName = "File"),
        )
        setContent {
            val config = Configuration(rule.activity.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(if (chinese.value) "zh-CN" else "en-US")) }
            val context = rule.activity.createConfigurationContext(config)
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config, LocalResources provides context.resources) {
                if (page.value) HistoryScreen(HistoryUiState(rows = entries.map { HistoryRow(it, "Studio PC", false) }), {}, {})
                else HomeScreen(HomeUiState(recent = entries.map { RecentActivityUi(it.id, it.deviceId, "Studio PC", null, it.displayName, it.status, null, it.updatedAtMillis, it.kind) }), {}, {}, {}, {}, {}, {}, {})
            }
        }
        listOf(false, true).forEach { zh ->
            rule.runOnIdle { chinese.value = zh }
            listOf(false, true).forEach { history ->
                rule.runOnIdle { page.value = history }
                val labels = if (zh) listOf("文本", "图片", "文件") else listOf("Text", "Image", "File")
                labels.forEach { label ->
                    rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
                    rule.onAllNodesWithText(label).onFirst().assertIsDisplayed()
                }
                rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("File"))
                rule.onAllNodesWithText("File").onLast().assertIsDisplayed()
            }
        }
    }
    private fun largeTextActions(orientation: Int) {
        rule.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        val expected = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == expected }
        val page = mutableStateOf(0)
        setContent(scale = 2f) {
            when (page.value) {
                0 -> SettingsScreen(SettingsUiState(), {}, {}, {}, {}, {}, {})
                1 -> ReceiveScreen(ReceiveUiState(item.id, displayName = "report.pdf", byteCount = 1024, phase = ReceivePhase.PENDING), {}, {}, {}, {}, {}, {})
                2 -> SessionScreen(SessionUiState(peer.id, peer.name, online = true, trusted = true, activities = listOf(SessionActivityUi(item.id, ShareDirection.OUTGOING, item.kind, displayName = item.displayName, mimeType = item.mimeType, byteCount = 1024, transferredBytes = 512, status = ActivityStatus.TRANSFERRING, timestampMillis = 100))), {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        listOf("Save name", "Background receiving", "Use UniShare Downloads").forEach {
            rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(it))
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Save name"))
        rule.onNodeWithText("Save name").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        rule.runOnIdle { page.value = 1 }
        listOf("Accept", "Reject").forEach { rule.onNodeWithText(it).performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
        rule.runOnIdle { page.value = 2 }
        listOf("Back", "More options", "Send text", "Add attachment", "Cancel transfer").forEach { rule.onNodeWithContentDescription(it).assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
    }
    private fun setContent(scale: Float = 1f, content: @Composable () -> Unit) {
        rule.activityRule.scenario.onActivity { WindowCompat.getInsetsController(it.window, it.window.decorView).isAppearanceLightStatusBars = true }
        val config = Configuration(rule.activity.resources.configuration).apply { setLocales(LocaleList(Locale.US)) }
        val context = rule.activity.createConfigurationContext(config)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config, LocalDensity provides Density(density.density, scale)) { UniShareTheme(false, content) }
        }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
