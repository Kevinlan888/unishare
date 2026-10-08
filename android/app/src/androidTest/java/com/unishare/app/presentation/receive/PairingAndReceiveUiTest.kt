package com.unishare.app.presentation.receive

import android.content.ActivityNotFoundException
import android.content.Intent
import android.app.Notification
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import com.unishare.app.MainActivity
import com.unishare.app.consumeReceiveActivityKey
import com.unishare.app.data.sdk.DevicePlatform
import com.unishare.app.domain.ActivityKey
import com.unishare.app.domain.ContentKind
import com.unishare.app.domain.DeviceKey
import com.unishare.app.presentation.pairing.PairingDialog
import com.unishare.app.presentation.pairing.PairingPrompt
import com.unishare.app.presentation.pairing.PairingUiState
import com.unishare.app.service.ShareNotificationFactory
import com.unishare.app.presentation.theme.UniShareTheme
import com.unishare.app.test.R as TestR
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingAndReceiveUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun receive_file_matches_reference_information_and_has_no_auto_accept_control() {
        val state = mutableStateOf(receiveState())
        var accepted = 0
        var rejected = 0
        setEnglishContent {
            ReceiveScreen(
                state = state.value,
                onBack = {},
                onAccept = {
                    accepted++
                    state.value = state.value.copy(phase = ReceivePhase.RECEIVING)
                },
                onReject = {
                    rejected++
                    state.value = state.value.copy(phase = ReceivePhase.REJECTED)
                },
                onChangeDestination = {},
                onRetryPublication = {},
                onOpen = {},
                verifiedPreview = sampleMountainLake(),
            )
        }

        listOf(
            "Receive File",
            "IMG_3281.jpg",
            "8.4 MB · JPEG",
            "From",
            "Kevin's Windows PC",
            "Online",
            "Reject",
            "Accept",
            "Files will be saved to:",
            "Download",
            "Why Downloads?",
        ).forEach { composeRule.onNodeWithText(it).assertIsDisplayed() }
        composeRule.onNodeWithText("Always accept files from this device").assertDoesNotExist()
        composeRule.onNodeWithText("Always accept").assertDoesNotExist()

        val rejectBounds = composeRule.onNodeWithText("Reject").fetchSemanticsNode().boundsInRoot
        val acceptBounds = composeRule.onNodeWithText("Accept").fetchSemanticsNode().boundsInRoot
        assertTrue("reject and accept actions should be side by side", rejectBounds.right <= acceptBounds.left)

        composeRule.onNodeWithText("Accept").performClick()
        composeRule.onNodeWithText("Accept").assertIsNotEnabled()
        composeRule.onNodeWithText("Reject").assertIsNotEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, accepted)
            assertEquals(0, rejected)
        }
    }

    @Test
    fun receive_expiration_and_directory_recovery_are_actionable() {
        val state = mutableStateOf(receiveState().copy(phase = ReceivePhase.DIRECTORY_ERROR))
        var changes = 0
        var retries = 0
        setEnglishContent {
            ReceiveScreen(
                state = state.value,
                onBack = {},
                onAccept = {},
                onReject = {},
                onChangeDestination = { changes++ },
                onRetryPublication = { retries++ },
                onOpen = {},
            )
        }

        composeRule.onNodeWithText("Couldn’t save this file. Restore Downloads, then try again.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Use Downloads").performClick()
        composeRule.onNodeWithText("Try again").performClick()
        composeRule.runOnIdle {
            assertEquals(1, changes)
            assertEquals(1, retries)
            state.value = state.value.copy(phase = ReceivePhase.EXPIRED)
        }
        composeRule.onNodeWithText("This receive request is no longer available.").assertIsDisplayed()
        composeRule.onNodeWithText("Accept").assertDoesNotExist()
    }

    @Test
    fun completed_receive_replaces_decisions_with_open_only_when_readable() {
        val state = receiveState().copy(
            phase = ReceivePhase.COMPLETED,
            finalContentUri = "content://downloads/final-image",
            canOpen = true,
        )
        var opens = 0
        setEnglishContent {
            ReceiveScreen(
                state = state,
                onBack = {},
                onAccept = {},
                onReject = {},
                onChangeDestination = {},
                onRetryPublication = {},
                onOpen = { opens++ },
            )
        }

        composeRule.onNodeWithText("Accept").assertDoesNotExist()
        composeRule.onNodeWithText("Reject").assertDoesNotExist()
        composeRule.onNodeWithText("Open").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, opens) }
    }

    @Test
    fun receive_notification_is_consent_only_and_deep_links_with_the_activity_key() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activityId = ActivityKey("activity/deep-link")
        val factory = ShareNotificationFactory(context)

        val intent = factory.incomingOfferIntent(activityId)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(
            activityId.value,
            intent.getStringExtra(MainActivity.EXTRA_RECEIVE_ACTIVITY_ID),
        )
        val notification = factory.incomingOffer(activityId)
        assertNull(notification.actions)
        assertEquals(Notification.VISIBILITY_SECRET, notification.visibility)
        val lockscreenCopy = listOf(
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        ).joinToString(" ").lowercase(Locale.US)
        assertFalse("file" in lockscreenCopy)
        assertFalse("image" in lockscreenCopy)
        assertFalse(activityId.value.lowercase(Locale.US) in lockscreenCopy)
    }

    @Test
    fun receive_intent_activity_key_is_consumed_once_before_configuration_recreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_RECEIVE_ACTIVITY_ID, "activity/once")

        assertEquals(ActivityKey("activity/once"), intent.consumeReceiveActivityKey())
        assertNull(intent.consumeReceiveActivityKey())
    }

    @Test
    fun published_content_launch_skips_package_visibility_preflight_and_handles_no_viewer() {
        var launched: Intent? = null

        val opened = launchPublishedContent("content://downloads/final-image", "image/jpeg") {
            launched = it
        }

        assertTrue(opened)
        assertEquals(Intent.ACTION_VIEW, launched?.action)
        assertEquals("content://downloads/final-image", launched?.dataString)
        assertEquals("image/jpeg", launched?.type)
        assertTrue(launched!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        var unavailable = 0
        assertFalse(
            launchPublishedContent(
                rawUri = "content://downloads/no-viewer",
                mimeType = "application/x-unishare-test",
                launch = {
                throw ActivityNotFoundException()
                },
                onUnavailable = { unavailable++ },
            ),
        )
        assertEquals(1, unavailable)
    }

    @Test
    fun pairing_dialog_reads_every_digit_and_submits_exactly_one_choice() {
        val state = mutableStateOf(
            PairingUiState(
                prompt = PairingPrompt.Verification(
                    requestId = 9,
                    deviceId = DeviceKey("pc-kevin"),
                    deviceName = "Kevin's Windows PC",
                    platform = DevicePlatform.WINDOWS,
                    code = "000042",
                ),
            ),
        )
        var matches = 0
        var differs = 0
        setEnglishContent {
            PairingDialog(
                state = state.value,
                onMatch = {
                    matches++
                    state.value = state.value.copy(submitting = true)
                },
                onMismatch = { differs++ },
                onDismiss = {},
                onForgetAndRepair = {},
            )
        }

        composeRule.onNodeWithText("Pair with Kevin's Windows PC?").assertIsDisplayed()
        composeRule.onNodeWithText("000 042").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pairing verification code 0 0 0 0 4 2")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Codes match")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
            .assertIsNotEnabled()
        composeRule.onNodeWithText("Codes don’t match").assertIsNotEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, matches)
            assertEquals(0, differs)
        }
    }

    @Test
    fun identity_change_dialog_only_offers_forget_and_repair() {
        setEnglishContent {
            PairingDialog(
                state = PairingUiState(
                    prompt = PairingPrompt.IdentityChanged(
                        deviceId = DeviceKey("pc-kevin"),
                        deviceName = "Kevin's Windows PC",
                        platform = DevicePlatform.WINDOWS,
                    ),
                ),
                onMatch = {},
                onMismatch = {},
                onDismiss = {},
                onForgetAndRepair = {},
            )
        }

        composeRule.onNodeWithText("Device identity changed").assertIsDisplayed()
        composeRule.onNodeWithText("Forget & pair again").assertIsDisplayed()
        composeRule.onNodeWithText("Codes match").assertDoesNotExist()
    }

    @Test
    fun capture_receive_and_pairing_reference_states_for_design_qa() {
        val showPairing = mutableStateOf(false)
        setEnglishContent {
            if (showPairing.value) {
                PairingDialog(
                    state = PairingUiState(
                        prompt = PairingPrompt.Verification(
                            requestId = 9,
                            deviceId = DeviceKey("pc-kevin"),
                            deviceName = "Kevin's Windows PC",
                            platform = DevicePlatform.WINDOWS,
                            code = "000042",
                        ),
                    ),
                    onMatch = {},
                    onMismatch = {},
                    onDismiss = {},
                    onForgetAndRepair = {},
                )
            } else {
                ReceiveScreen(
                    state = receiveState(),
                    onBack = {},
                    onAccept = {},
                    onReject = {},
                    onChangeDestination = {},
                    onRetryPublication = {},
                    onOpen = {},
                    verifiedPreview = sampleMountainLake(),
                )
            }
        }
        composeRule.waitForIdle()
        saveDeviceScreenshot("task10-receive.png")

        composeRule.runOnIdle { showPairing.value = true }
        composeRule.waitForIdle()
        saveDeviceScreenshot("task10-pairing.png")
    }

    private fun setEnglishContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        val context = composeRule.activity
        composeRule.activityRule.scenario.onActivity { activity ->
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .isAppearanceLightStatusBars = true
        }
        val configuration = Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(Locale.US))
        }
        val localized = context.createConfigurationContext(configuration)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
            ) {
                UniShareTheme(darkTheme = false, content = content)
            }
        }
    }

    private fun receiveState() = ReceiveUiState(
        activityId = ActivityKey("activity-1"),
        kind = ContentKind.IMAGE,
        displayName = "IMG_3281.jpg",
        mimeType = "image/jpeg",
        byteCount = 8_400_000,
        source = ReceiveSourceUi(
            id = DeviceKey("pc-kevin"),
            name = "Kevin's Windows PC",
            platform = DevicePlatform.WINDOWS,
            online = true,
        ),
        phase = ReceivePhase.PENDING,
    )

    private fun sampleMountainLake() = requireNotNull(
        BitmapFactory.decodeResource(
            InstrumentationRegistry.getInstrumentation().context.resources,
            TestR.drawable.sample_mountain_lake,
        ),
    ).asImageBitmap()

    private fun saveDeviceScreenshot(name: String) {
        val result = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("screencap -p /sdcard/Download/$name")
        ParcelFileDescriptor.AutoCloseInputStream(result).use { it.readBytes() }
    }
}
