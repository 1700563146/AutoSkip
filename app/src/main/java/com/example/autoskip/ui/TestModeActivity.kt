package com.example.autoskip.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.core.app.ActivityCompat
import com.example.autoskip.R
import com.example.autoskip.data.AppPrefs
import com.example.autoskip.databinding.ActivityTestModeBinding
import com.example.autoskip.testmode.TestModeService
import kotlin.math.roundToInt

/**
 * Drives the built-in test mode: floats a "跳过" button at random positions and
 * reports how often the accessibility service managed to click it.
 */
class TestModeActivity : BaseActivity() {

    private lateinit var binding: ActivityTestModeBinding
    private lateinit var prefs: AppPrefs

    /** Stable reference so it can be unregistered in onPause. */
    private val statsListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestModeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpToolbar(binding.toolbar, R.string.title_test_mode)
        applySystemBarInsets(binding.root)

        prefs = AppPrefs.get(this)

        binding.btnToggle.setOnClickListener { toggle() }

        binding.labelInterval.text = getString(R.string.label_test_interval) + "：" +
            getString(R.string.unit_ms, prefs.testIntervalMs)

        binding.seekInterval.max = MAX_PROGRESS
        binding.seekInterval.progress =
            ((prefs.testIntervalMs - INTERVAL_MIN) / INTERVAL_STEP).toInt()
                .coerceIn(0, MAX_PROGRESS)

        binding.seekInterval.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = INTERVAL_MIN + progress * INTERVAL_STEP
                binding.labelInterval.text = getString(R.string.label_test_interval) + "：" +
                    getString(R.string.unit_ms, value)
                if (fromUser) prefs.testIntervalMs = value
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        TestModeService.setListener(statsListener)
        refresh()
    }

    override fun onPause() {
        TestModeService.setListener(null)
        super.onPause()
    }

    private fun toggle() {
        if (TestModeService.isRunning) {
            TestModeService.stop(this)
            refresh()
            return
        }

        if (!Permissions.canDrawOverlays(this)) {
            warn(getString(R.string.test_mode_need_overlay))
            Permissions.requestOverlayPermission(this)
            return
        }
        if (!Permissions.isAccessibilityServiceEnabled(this)) {
            warn(getString(R.string.test_mode_need_a11y))
            Permissions.openAccessibilitySettings(this)
            return
        }

        requestNotificationPermissionIfNeeded()
        TestModeService.resetCounters()
        TestModeService.start(this)
        refresh()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    private fun warn(message: String) {
        binding.textWarning.text = message
        binding.textWarning.visibility = View.VISIBLE
    }

    private fun refresh() {
        val running = TestModeService.isRunning

        binding.btnToggle.setText(
            if (running) R.string.action_stop_test else R.string.action_start_test,
        )
        binding.textState.setText(
            if (running) R.string.test_mode_running else R.string.test_mode_stopped,
        )

        val spawns = TestModeService.spawnCount
        val hits = TestModeService.autoHitCount
        binding.textStats.text = getString(R.string.test_stats_format, spawns, hits)
        binding.textHitRate.text = getString(
            R.string.test_hit_rate,
            if (spawns == 0) 0 else (hits * 100 / spawns),
        )

        if (running) binding.textWarning.visibility = View.GONE
    }

    private companion object {
        const val MAX_PROGRESS = 100
        // Step chosen so the 1500 ms default round-trips exactly.
        const val INTERVAL_MIN = 500L
        const val INTERVAL_STEP = 50L
        const val REQUEST_NOTIFICATIONS = 100
    }
}
