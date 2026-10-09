package com.unishare.app.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.unishare.app.data.transfer.ContentPermissionException
import com.unishare.app.data.transfer.ContentUriPermissionRetainer
import com.unishare.app.data.transfer.RetainedContentUri
import com.unishare.app.domain.ShareErrorKind

data class ContentPickerLauncher(
    val launchPhoto: () -> Unit,
    val launchFile: () -> Unit,
)

@Composable
fun rememberContentPickerLauncher(
    retainer: ContentUriPermissionRetainer,
    onPhotoSelected: (RetainedContentUri?) -> Unit,
    onFileSelected: (RetainedContentUri?) -> Unit,
    onPermissionFailure: (ShareErrorKind) -> Unit,
): ContentPickerLauncher {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { deliverPickerResult(it, retainer, onPhotoSelected, onPermissionFailure) },
    )
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { deliverPickerResult(it, retainer, onFileSelected, onPermissionFailure) },
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

internal fun deliverPickerResult(
    uri: Uri?,
    retainer: ContentUriPermissionRetainer,
    onSelected: (RetainedContentUri?) -> Unit,
    onPermissionFailure: (ShareErrorKind) -> Unit,
) {
    if (uri == null) {
        onSelected(null)
        return
    }
    val retained = try {
        retainer.retain(uri)
    } catch (failure: ContentPermissionException) {
        onPermissionFailure(failure.errorKind)
        return
    }
    try {
        onSelected(retained)
    } catch (failure: Exception) {
        retained.lease.close()
        throw failure
    }
}
