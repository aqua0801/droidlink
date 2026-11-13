package io.github.aqua0801.droidlink.screenshot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** The runtime permission needed to see new screenshots in MediaStore. */
object ScreenshotAccess {
    val permission: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * Full image access. On Android 14+ "allow limited access" does not grant this, and new
     * screenshots would be invisible to the app, so that case counts as not granted.
     */
    fun granted(context: Context): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
