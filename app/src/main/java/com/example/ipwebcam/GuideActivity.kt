package com.example.ipwebcam

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.ipwebcam.databinding.ActivityGuideBinding
import com.example.ipwebcam.databinding.ItemGuideStepBinding
import com.example.ipwebcam.utils.DeviceGuide
import com.example.ipwebcam.utils.PermissionUtils

/**
 * Panduan langkah demi langkah agar streaming tetap berjalan saat layar dimatikan,
 * disesuaikan dengan merek HP, lengkap dengan tombol pintasan ke halaman pengaturannya.
 */
class GuideActivity : AppCompatActivity() {

    private enum class Status { DONE, PENDING, RESTRICTED, OPTIONAL }

    private class Step(
        @StringRes val title: Int,
        @StringRes val body: Int,
        val action: (() -> Unit)? = null,
        val status: (() -> Status?)? = null
    )

    private lateinit var binding: ActivityGuideBinding
    private val stepViews = mutableListOf<Pair<Step, ItemGuideStepBinding>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvDevice.text = DeviceGuide.deviceName
        binding.tvSystem.text = DeviceGuide.systemName

        buildSteps().forEachIndexed { index, step ->
            val item = ItemGuideStepBinding.inflate(layoutInflater, binding.stepsContainer, true)
            item.tvNumber.text = (index + 1).toString()
            item.tvTitle.setText(step.title)
            item.tvBody.text = getText(step.body)
            step.action?.let { action ->
                item.btnAction.visibility = View.VISIBLE
                item.btnAction.setOnClickListener { action() }
            }
            stepViews += step to item
        }
    }

    override fun onResume() {
        super.onResume()
        // Status bisa berubah setelah kembali dari halaman pengaturan sistem
        for ((step, item) in stepViews) {
            bindStatus(item, step.status?.invoke())
        }
    }

    private fun buildSteps(): List<Step> {
        val steps = mutableListOf(
            Step(
                title = R.string.guide_step_battery_title,
                body = R.string.guide_step_battery_body,
                action = { PermissionUtils.openBatteryOptimizationSettings(this) },
                status = {
                    if (PermissionUtils.isIgnoringBatteryOptimizations(this)) Status.DONE else Status.PENDING
                }
            ),
            Step(
                title = R.string.guide_step_background_title,
                body = DeviceGuide.backgroundSteps,
                action = { DeviceGuide.openBackgroundSettings(this) },
                status = {
                    if (DeviceGuide.isBackgroundRestricted(this)) Status.RESTRICTED else null
                }
            )
        )
        DeviceGuide.autostartSteps?.let { body ->
            steps += Step(
                title = R.string.guide_step_autostart_title,
                body = body,
                action = { DeviceGuide.openAutostartSettings(this) }
            )
        }
        steps += Step(
            title = R.string.guide_step_overlay_title,
            body = R.string.guide_step_overlay_body,
            action = { PermissionUtils.openOverlaySettings(this) },
            status = {
                if (PermissionUtils.hasOverlayPermission(this)) Status.DONE else Status.OPTIONAL
            }
        )
        if (DeviceGuide.needsRecentsLock) {
            steps += Step(
                title = R.string.guide_step_lock_title,
                body = R.string.guide_step_lock_body
            )
        }
        return steps
    }

    private fun bindStatus(item: ItemGuideStepBinding, status: Status?) {
        if (status == null) {
            item.tvStatus.visibility = View.GONE
            return
        }
        val (label, color) = when (status) {
            Status.DONE -> R.string.guide_status_done to R.color.success
            Status.PENDING -> R.string.guide_status_pending to R.color.warning
            Status.RESTRICTED -> R.string.guide_status_restricted to R.color.danger
            Status.OPTIONAL -> R.string.guide_status_optional to R.color.text_secondary
        }
        item.tvStatus.setText(label)
        item.tvStatus.setTextColor(ContextCompat.getColor(this, color))
        item.tvStatus.visibility = View.VISIBLE
    }
}
