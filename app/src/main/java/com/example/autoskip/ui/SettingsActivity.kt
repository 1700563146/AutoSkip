package com.example.autoskip.ui

import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView
import com.example.autoskip.R
import com.example.autoskip.data.AppPrefs
import com.example.autoskip.databinding.ActivitySettingsBinding
import com.example.autoskip.log.LogBus
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tuning screen for the anti-jitter and anti-misclick parameters.
 *
 * Every slider writes straight through to [AppPrefs], which the service reads on
 * each event — so changes take effect immediately, with no restart.
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpToolbar(binding.toolbar, R.string.title_settings)
        applySystemBarInsets(binding.root)

        prefs = AppPrefs.get(this)

        bindSlider(
            label = binding.labelDebounce,
            labelRes = R.string.label_debounce,
            bar = binding.seekDebounce,
            current = prefs.debounceMs,
            toProgress = { (it / DEBOUNCE_STEP).roundToInt().toLong() },
            fromProgress = { (it * DEBOUNCE_STEP).toLong() },
            onChanged = { prefs.debounceMs = it },
        )

        bindSlider(
            label = binding.labelScanTimeout,
            labelRes = R.string.label_scan_timeout,
            bar = binding.seekScanTimeout,
            current = prefs.scanTimeoutMs,
            toProgress = { ((it - SCAN_TIMEOUT_MIN) / SCAN_TIMEOUT_STEP).toLong() },
            fromProgress = { SCAN_TIMEOUT_MIN + it * SCAN_TIMEOUT_STEP },
            onChanged = { prefs.scanTimeoutMs = it },
        )

        // Node counts jump in powers of two: a linear 256..16384 slider would
        // spend most of its travel in a range nobody uses.
        bindSlider(
            label = binding.labelMaxScan,
            labelRes = R.string.label_max_scan,
            bar = binding.seekMaxScan,
            current = prefs.maxScanNodes.toLong(),
            toProgress = { nearestNodeIndex(it.toInt()).toLong() },
            fromProgress = { NODE_CHOICES[it].toLong() },
            unitRes = R.string.unit_count,
            onChanged = { prefs.maxScanNodes = it.toInt() },
        )

        bindSlider(
            label = binding.labelCooldown,
            labelRes = R.string.label_cooldown,
            bar = binding.seekCooldown,
            current = prefs.clickCooldownMs,
            toProgress = { (it / COOLDOWN_STEP).toLong() },
            fromProgress = { it * COOLDOWN_STEP },
            onChanged = { prefs.clickCooldownMs = it },
        )

        bindSlider(
            label = binding.labelNodeCooldown,
            labelRes = R.string.label_node_cooldown,
            bar = binding.seekNodeCooldown,
            current = prefs.nodeCooldownMs,
            toProgress = { (it / NODE_COOLDOWN_STEP).toLong() },
            fromProgress = { it * NODE_COOLDOWN_STEP },
            onChanged = { prefs.nodeCooldownMs = it },
        )

        binding.switchAllowSelf.isChecked = prefs.allowSelfTestOverlay
        binding.switchAllowSelf.setOnCheckedChangeListener { _, checked ->
            prefs.allowSelfTestOverlay = checked
        }

        binding.switchDefaultAllow.isChecked = prefs.defaultAllowUnlisted
        binding.switchDefaultAllow.setOnCheckedChangeListener { _, checked ->
            prefs.defaultAllowUnlisted = checked
        }

        binding.btnReset.setOnClickListener {
            prefs.resetTunablesToDefaults()
            LogBus.log("参数已恢复默认值")
            // Rebuild the screen so every slider reflects the restored values.
            recreate()
        }
    }

    private fun nearestNodeIndex(value: Int): Int {
        var best = 0
        var bestDelta = Int.MAX_VALUE
        NODE_CHOICES.forEachIndexed { index, choice ->
            val delta = abs(choice - value)
            if (delta < bestDelta) {
                bestDelta = delta
                best = index
            }
        }
        return best
    }

    private fun bindSlider(
        label: TextView,
        labelRes: Int,
        bar: SeekBar,
        current: Long,
        toProgress: (Long) -> Long,
        fromProgress: (Int) -> Long,
        unitRes: Int = R.string.unit_ms,
        onChanged: (Long) -> Unit,
    ) {
        bar.max = MAX_PROGRESS
        bar.progress = toProgress(current).toInt().coerceIn(0, MAX_PROGRESS)

        fun render() {
            label.text = getString(labelRes) + "：" +
                getString(unitRes, fromProgress(bar.progress))
        }
        render()

        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                render()
                if (fromUser) onChanged(fromProgress(progress))
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private companion object {
        const val MAX_PROGRESS = 100

        const val DEBOUNCE_STEP = 20.0
        const val COOLDOWN_STEP = 50L
        const val NODE_COOLDOWN_STEP = 200L

        // Chosen so the shipped default of 800 ms lands exactly on a step; with
        // an awkward step the slider would round it to e.g. 788 on first open.
        const val SCAN_TIMEOUT_MIN = 100L
        const val SCAN_TIMEOUT_STEP = 20L

        val NODE_CHOICES = intArrayOf(512, 1024, 2048, 4096, 8192, 16384)
    }
}
