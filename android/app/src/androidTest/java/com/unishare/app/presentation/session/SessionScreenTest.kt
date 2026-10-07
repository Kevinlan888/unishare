package com.unishare.app.presentation.session

import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import com.unishare.app.R
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ActivityStatus
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey
import com.unishare.app.domain.ShareDirection
import com.unishare.app.presentation.theme.UniShareTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outgoing_cards_are_right_aligned_and_incoming_cards_are_left_aligned() {
        setSessionContent(
            activities = listOf(
                activity("outgoing", ShareDirection.OUTGOING, text = "Hello from Android!"),
                activity("incoming", ShareDirection.INCOMING, text = "Hi there"),
            ),
        )

        val outgoing = composeRule.onNodeWithText("Hello from Android!").fetchSemanticsNode().boundsInRoot
        val incoming = composeRule.onNodeWithText("Hi there").fetchSemanticsNode().boundsInRoot
        assertTrue("outgoing activity should sit farther right", outgoing.left > incoming.left)
        assertTrue("outgoing activity should end farther right", outgoing.right > incoming.right)
    }

    @Test
    fun text_image_and_file_cards_expose_readable_state_and_clamped_progress() {
        setSessionContent(
            activities = listOf(
                activity("text", ShareDirection.INCOMING, text = "Hi!"),
                activity("image", ShareDirection.INCOMING, kind = ContentKind.IMAGE, name = "IMG_3281.jpg", imageRes = R.drawable.sample_mountain_lake),
                activity(
                    "file",
                    ShareDirection.OUTGOING,
                    kind = ContentKind.FILE,
                    name = "project.zip",
                    status = ActivityStatus.TRANSFERRING,
                    transferred = 125,
                    total = 100,
                    rate = 42_000_000,
                    eta = 14,
                ),
            ),
        )

        composeRule.onNodeWithText("Hi!").assertIsDisplayed()
        composeRule.onNodeWithText("IMG_3281.jpg").assertIsDisplayed()
        composeRule.onNodeWithText("project.zip").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Transfer progress 100%")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel transfer").assertIsDisplayed()
    }

    @Test
    fun waiting_progress_and_terminal_cards_have_status_semantics_and_correct_actions() {
        setSessionContent(
            activities = listOf(
                activity("waiting", ShareDirection.OUTGOING, status = ActivityStatus.AWAITING_PEER, text = "Waiting"),
                activity("progress", ShareDirection.OUTGOING, status = ActivityStatus.TRANSFERRING, name = "active.zip"),
                activity("failed", ShareDirection.OUTGOING, status = ActivityStatus.FAILED, name = "failed.zip"),
                activity("done", ShareDirection.INCOMING, status = ActivityStatus.COMPLETED, text = "Done"),
            ),
        )

        composeRule.onNodeWithContentDescription("Waiting for peer").assertExists()
        composeRule.onNodeWithContentDescription("Transferring").assertExists()
        composeRule.onNodeWithContentDescription("Transfer failed").assertExists()
        composeRule.onNodeWithContentDescription("Transfer completed").assertExists()
        composeRule.onAllNodes(hasContentDescription("Cancel transfer") and hasClickAction()).assertCountEquals(2)
        composeRule.onNode(hasContentDescription("Retry transfer") and hasClickAction()).assertExists()
    }

    @Test
    fun attachment_sheet_has_five_supported_entries_and_never_offers_folder() {
        var state by mutableStateOf(baseState())
        setSessionContent(stateProvider = { state }, onAdd = { state = state.copy(shareSheetVisible = true) })

        composeRule.onNodeWithContentDescription("Add attachment").performClick()

        listOf("Photo", "File", "Clipboard", "Text", "Link").forEach {
            composeRule.onNodeWithText(it).assertIsDisplayed()
        }
        composeRule.onNodeWithText("Folder").assertDoesNotExist()
    }

    @Test
    fun add_and_send_controls_remain_reachable_at_two_hundred_percent_font_scale() {
        setSessionContent(fontScale = 2f)

        listOf("Add attachment", "Send text").forEach { label ->
            composeRule.onNodeWithContentDescription(label)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        composeRule.onNode(hasText("Type a message…") and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun capture_reference_session_and_share_sheet_for_design_qa() {
        var state by mutableStateOf(
            baseState().copy(
                activities = listOf(
                    activity("hello", ShareDirection.OUTGOING, text = "Hello from Android!"),
                    activity("reply", ShareDirection.INCOMING, text = "Hi!"),
                    activity(
                        "photo",
                        ShareDirection.INCOMING,
                        kind = ContentKind.IMAGE,
                        name = "IMG_3281.jpg",
                        imageRes = R.drawable.sample_mountain_lake,
                        total = 8_400_000,
                    ),
                    activity(
                        "archive",
                        ShareDirection.OUTGOING,
                        kind = ContentKind.FILE,
                        name = "project.zip",
                        status = ActivityStatus.TRANSFERRING,
                        transferred = 720_000_000,
                        total = 1_000_000_000,
                        rate = 42_000_000,
                        eta = 14,
                    ),
                ),
            ),
        )
        setSessionContent(
            stateProvider = { state },
            onAdd = { state = state.copy(shareSheetVisible = true) },
        )
        composeRule.waitForIdle()
        saveDeviceScreenshot("task9-session.png")

        composeRule.onNodeWithContentDescription("Add attachment").performClick()
        composeRule.onNodeWithText("Share to Kevin's Windows PC").assertIsDisplayed()
        composeRule.waitForIdle()
        saveDeviceScreenshot("task9-share-sheet.png")
    }

    private fun saveDeviceScreenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val result = instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name")
        ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
    }

    private fun setSessionContent(
        activities: List<SessionActivityUi> = emptyList(),
        stateProvider: (() -> SessionUiState)? = null,
        onAdd: () -> Unit = {},
        fontScale: Float = 1f,
    ) {
        val context = composeRule.activity
        composeRule.activityRule.scenario.onActivity { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars = true
        }
        val configuration = Configuration(context.resources.configuration)
        val localizedContext = context.createConfigurationContext(configuration)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, fontScale),
            ) {
                UniShareTheme(darkTheme = false) {
                    SessionScreen(
                        state = stateProvider?.invoke() ?: baseState().copy(activities = activities),
                        onBack = {},
                        onMore = {},
                        onComposerChanged = {},
                        onSend = {},
                        onAddAttachment = onAdd,
                        onDismissShareSheet = {},
                        onPickPhoto = {},
                        onPickFile = {},
                        onClipboard = {},
                        onText = {},
                        onLink = {},
                        onCancel = {},
                        onRetry = {},
                    )
                }
            }
        }
    }

    private fun baseState() = SessionUiState(
        deviceId = DeviceKey("pc-kevin"),
        deviceName = "Kevin's Windows PC",
        platform = DevicePlatform.WINDOWS,
        online = true,
        trusted = true,
    )

    private fun activity(
        id: String,
        direction: ShareDirection,
        kind: ContentKind = ContentKind.TEXT,
        name: String = "Text",
        status: ActivityStatus = ActivityStatus.COMPLETED,
        text: String? = null,
        imageRes: Int? = null,
        transferred: Long = 0,
        total: Long = 100,
        rate: Long? = null,
        eta: Long? = null,
    ) = SessionActivityUi(
        id = ActivityKey(id),
        direction = direction,
        kind = kind,
        displayName = name,
        mimeType = if (kind == ContentKind.IMAGE) "image/jpeg" else "application/octet-stream",
        byteCount = total,
        transferredBytes = transferred,
        status = status,
        timestampMillis = 1_800_000_000_000,
        text = text,
        previewDrawableRes = imageRes,
        rateBytesPerSecond = rate,
        etaSeconds = eta,
    )
}
