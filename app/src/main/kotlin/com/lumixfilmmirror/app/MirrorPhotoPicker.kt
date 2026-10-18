package com.lumixfilmmirror.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** Let the device choose its installed system/OEM photo picker; never pin a Google package. */
internal class MirrorPhotoPicker : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).setType("image/jpeg").addCategory(Intent.CATEGORY_OPENABLE)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Prefer an actual installed OEM system gallery that advertises the standard import contract.
        // No proprietary API, invented package dependency, broad storage permission or Google pinning.
        @Suppress("DEPRECATION")
        val galleries = context.packageManager.queryIntentActivities(galleryIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }.filter { info ->
                val system = info.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                // The legacy emulator Gallery3D was observed to ignore ALLOW_MULTIPLE.
                // Prefer the platform multi-picker there; retain real OEM gallery preference.
                system && info.packageName != "com.android.gallery3d" &&
                    (info.packageName.contains("gallery", true) || info.packageName in setOf("com.huawei.photos"))
            }.distinctBy { it.packageName }
        if (galleries.size == 1) return galleryIntent.setPackage(galleries.single().packageName)
        if (galleries.size > 1) return Intent.createChooser(galleryIntent, "选择系统相册")
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context))
            return ActivityResultContracts.PickMultipleVisualMedia().createIntent(context,
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.SingleMimeType("image/jpeg")))
        return galleryIntent
    }
    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if(resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        return buildList { intent.data?.let(::add); intent.clipData?.let { data ->
            repeat(data.itemCount) { add(data.getItemAt(it).uri) } } }.distinct()
    }
}
