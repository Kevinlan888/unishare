package com.unishare.app.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

data class ContentPickerLauncher(
    val launchPhoto: () -> Unit,
    val launchFile: () -> Unit,
)

@Composable
fun rememberContentPickerLauncher(
    onPhotoSelected: (Uri?) -> Unit,
    onFileSelected: (Uri?) -> Unit,
): ContentPickerLauncher {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = onPhotoSelected,
    )
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = onFileSelected,
    )
    return remember(photoPicker, filePicker) {
        ContentPickerLauncher(
            launchPhoto = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            launchFile = { filePicker.launch(arrayOf("*/*")) },
        )
    }
}
