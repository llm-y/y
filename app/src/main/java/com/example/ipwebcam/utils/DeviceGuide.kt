package com.example.ipwebcam.utils

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.StringRes
import com.example.ipwebcam.R

/**
 * Panduan & pintasan pengaturan latar belakang per merek HP.
 * Tiap ROM (ColorOS, MIUI, Funtouch, One UI, EMUI) punya menu sendiri untuk
 * membatasi aplikasi di latar belakang, di luar pengoptimalan baterai standar Android.
 */
object DeviceGuide {

    enum class Family { OPPO, XIAOMI, VIVO, SAMSUNG, HUAWEI, OTHER }

    val family: Family by lazy {
        when (Build.MANUFACTURER.lowercase()) {
            "oppo", "realme", "oneplus" -> Family.OPPO
            "xiaomi", "redmi", "poco" -> Family.XIAOMI
            "vivo", "iqoo" -> Family.VIVO
            "samsung" -> Family.SAMSUNG
            "huawei", "honor" -> Family.HUAWEI
            else -> Family.OTHER
        }
    }

    val deviceName: String
        get() {
            val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
            return if (Build.MODEL.startsWith(manufacturer, ignoreCase = true)) {
                Build.MODEL
            } else {
                "$manufacturer ${Build.MODEL}"
            }
        }

    val brandName: String
        get() = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }

    val systemName: String
        get() {
            val skin = when (Build.MANUFACTURER.lowercase()) {
                "realme" -> "realme UI"
                "oneplus" -> "OxygenOS"
                "oppo" -> "ColorOS"
                "xiaomi", "redmi", "poco" -> "MIUI / HyperOS"
                "vivo", "iqoo" -> "Funtouch OS / OriginOS"
                "samsung" -> "One UI"
                "huawei" -> "EMUI"
                "honor" -> "MagicOS"
                else -> null
            }
            val android = "Android ${Build.VERSION.RELEASE}"
            return if (skin != null) "$skin · $android" else android
        }

    @get:StringRes
    val backgroundSteps: Int
        get() = when (family) {
            Family.OPPO -> R.string.guide_bg_oppo
            Family.XIAOMI -> R.string.guide_bg_xiaomi
            Family.VIVO -> R.string.guide_bg_vivo
            Family.SAMSUNG -> R.string.guide_bg_samsung
            Family.HUAWEI -> R.string.guide_bg_huawei
            Family.OTHER -> R.string.guide_bg_default
        }

    @get:StringRes
    val backgroundPath: Int
        get() = when (family) {
            Family.OPPO -> R.string.guide_bg_path_oppo
            Family.XIAOMI -> R.string.guide_bg_path_xiaomi
            Family.VIVO -> R.string.guide_bg_path_vivo
            Family.SAMSUNG -> R.string.guide_bg_path_samsung
            Family.HUAWEI -> R.string.guide_bg_path_huawei
            Family.OTHER -> R.string.guide_bg_path_default
        }

    /** Langkah mulai otomatis, hanya untuk ROM yang punya pengelola autostart terpisah. */
    @get:StringRes
    val autostartSteps: Int?
        get() = when (family) {
            Family.OPPO -> R.string.guide_autostart_oppo
            Family.XIAOMI -> R.string.guide_autostart_xiaomi
            Family.VIVO -> R.string.guide_autostart_vivo
            else -> null
        }

    /** ROM yang agresif membersihkan aplikasi dari menu Terbaru. */
    val needsRecentsLock: Boolean
        get() = family != Family.OTHER

    /** Android 9+ bisa memberi tahu jika pengguna membatasi aplikasi di latar belakang. */
    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.isBackgroundRestricted
    }

    fun openBackgroundSettings(context: Context) {
        val intents = when (family) {
            Family.VIVO -> listOf(Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
            Family.HUAWEI -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )
            else -> emptyList()
        }
        startFirst(context, intents + appDetails(context))
    }

    fun openAutostartSettings(context: Context) {
        val intents = when (family) {
            Family.XIAOMI -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            Family.OPPO -> listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
            )
            Family.VIVO -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            )
            else -> emptyList()
        }
        startFirst(context, intents + appDetails(context))
    }

    private fun appDetails(context: Context) = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    /** Coba intent satu per satu; halaman khusus ROM bisa tidak ada atau tidak di-export. */
    private fun startFirst(context: Context, intents: List<Intent>) {
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (e: Exception) {
                // Lanjut ke intent berikutnya
            }
        }
        Toast.makeText(context, R.string.guide_open_failed, Toast.LENGTH_LONG).show()
    }
}
