package com.example.autoskip.ui

import android.content.Intent
import android.os.Bundle
import com.example.autoskip.R
import com.example.autoskip.data.AppPrefs
import com.example.autoskip.databinding.ActivityMainBinding
import com.example.autoskip.log.LogBus
import com.example.autoskip.testmode.TestModeService

/** Home screen: live permission status, the master switch, and a running log. */
class MainActivity : BaseActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs

    /** Held as a stable reference so it can be unregistered again. */
    private val logListener: () -> Unit = { refreshLog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The launcher screen is the root of the stack, so no up arrow.
        setUpToolbar(binding.toolbar, R.string.app_name, showUp = false)
        applySystemBarInsets(binding.root)

        prefs = AppPrefs.get(this)

        binding.switchMaster.isChecked = prefs.enabled
        binding.switchMaster.setOnCheckedChangeListener { _, checked ->
            prefs.enabled = checked
            LogBus.log(if (checked) "已启用自动跳过" else "已暂停自动跳过")
        }

        binding.btnA11y.setOnClickListener { Permissions.openAccessibilitySettings(this) }
        binding.btnOverlay.setOnClickListener { Permissions.requestOverlayPermission(this) }
        binding.btnTestMode.setOnClickListener { open(TestModeActivity::class.java) }
        binding.btnWhitelist.setOnClickListener { open(WhitelistActivity::class.java) }
        binding.btnSettings.setOnClickListener { open(SettingsActivity::class.java) }
        binding.btnClearLog.setOnClickListener { LogBus.clear() }

        LogBus.addListener(logListener)
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshLog()
    }

    override fun onDestroy() {
        LogBus.removeListener(logListener)
        super.onDestroy()
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }

    private fun refreshStatus() {
        val a11yEnabled = Permissions.isAccessibilityServiceEnabled(this)
        binding.statusA11y.setText(
            if (a11yEnabled) R.string.status_enabled else R.string.status_disabled,
        )
        binding.btnA11y.isEnabled = !a11yEnabled

        val overlayGranted = Permissions.canDrawOverlays(this)
        binding.statusOverlay.setText(
            if (overlayGranted) R.string.status_enabled else R.string.status_disabled,
        )
        binding.btnOverlay.isEnabled = !overlayGranted

        binding.btnTestMode.text = if (TestModeService.isRunning) {
            getString(R.string.test_mode_running)
        } else {
            getString(R.string.action_open_test_mode)
        }
    }

    private fun refreshLog() {
        val entries = LogBus.snapshot()
        binding.textLog.text = if (entries.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            entries.takeLast(MAX_LOG_LINES).joinToString("\n") { LogBus.format(it) }
        }
        binding.textStats.text =
            getString(R.string.stats_format, LogBus.scanCount, LogBus.clickCount)
    }

    private companion object {
        const val MAX_LOG_LINES = 40
    }
}
