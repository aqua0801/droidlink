package io.github.aqua0801.droidlink.data

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceInfo {
    /** The user-visible device name (Settings → About), falling back to the model. */
    fun name(context: Context): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
}
