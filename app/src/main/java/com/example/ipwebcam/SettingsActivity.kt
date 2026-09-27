package com.example.ipwebcam

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.example.ipwebcam.databinding.ActivitySettingsBinding
import com.example.ipwebcam.service.WebcamService
import com.example.ipwebcam.utils.DeviceGuide
import com.example.ipwebcam.utils.PermissionUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = WebcamService.PREFS_NAME
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>(KEY_GUIDE)?.apply {
                summary = getString(R.string.pref_guide_summary, DeviceGuide.brandName)
                setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), GuideActivity::class.java))
                    true
                }
            }

            findPreference<Preference>(KEY_BACKGROUND_ACTIVITY)?.setOnPreferenceClickListener {
                DeviceGuide.openBackgroundSettings(requireContext())
                true
            }

            findPreference<Preference>(KEY_BATTERY)?.setOnPreferenceClickListener {
                PermissionUtils.openBatteryOptimizationSettings(requireContext())
                true
            }

            findPreference<Preference>(KEY_OVERLAY_PERMISSION)?.setOnPreferenceClickListener {
                PermissionUtils.openOverlaySettings(requireContext())
                true
            }

            findPreference<SwitchPreferenceCompat>(WebcamService.KEY_SHOW_CAMERA_OVERLAY)
                ?.setOnPreferenceChangeListener { _, newValue ->
                    if (newValue == true && !PermissionUtils.hasOverlayPermission(requireContext())) {
                        Toast.makeText(
                            requireContext(),
                            R.string.overlay_permission_needed,
                            Toast.LENGTH_LONG
                        ).show()
                        PermissionUtils.openOverlaySettings(requireContext())
                    }
                    true
                }

            findPreference<Preference>(KEY_VERSION)?.summary = BuildConfig.VERSION_NAME
        }

        override fun onResume() {
            super.onResume()
            // Status izin bisa berubah setelah kembali dari halaman pengaturan sistem
            val context = requireContext()
            findPreference<Preference>(KEY_BATTERY)?.summary = getString(
                if (PermissionUtils.isIgnoringBatteryOptimizations(context)) {
                    R.string.pref_battery_summary_on
                } else {
                    R.string.pref_battery_summary_off
                }
            )
            findPreference<Preference>(KEY_BACKGROUND_ACTIVITY)?.summary = getString(
                if (DeviceGuide.isBackgroundRestricted(context)) {
                    R.string.pref_background_activity_restricted
                } else {
                    DeviceGuide.backgroundPath
                }
            )
            findPreference<Preference>(KEY_OVERLAY_PERMISSION)?.summary = getString(
                if (PermissionUtils.hasOverlayPermission(context)) {
                    R.string.pref_overlay_permission_summary_on
                } else {
                    R.string.pref_overlay_permission_summary_off
                }
            )
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference !is ListPreference) {
                super.onDisplayPreferenceDialog(preference)
                return
            }
            // Dialog pilihan bergaya Material 3, konsisten dengan dialog lain di aplikasi
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(preference.title)
                .setSingleChoiceItems(preference.entries, preference.findIndexOfValue(preference.value)) { dialog, which ->
                    val value = preference.entryValues[which].toString()
                    if (preference.callChangeListener(value)) {
                        preference.value = value
                    }
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        companion object {
            private const val KEY_GUIDE = "screen_off_guide"
            private const val KEY_BATTERY = "battery_optimization"
            private const val KEY_BACKGROUND_ACTIVITY = "background_activity"
            private const val KEY_OVERLAY_PERMISSION = "overlay_permission"
            private const val KEY_VERSION = "app_version"
        }
    }
}
