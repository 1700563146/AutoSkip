package com.example.autoskip.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.autoskip.R
import com.example.autoskip.data.AppPrefs
import com.example.autoskip.databinding.ActivityWhitelistBinding
import com.example.autoskip.model.WhitelistEntry
import kotlin.concurrent.thread

/**
 * Per-app whitelist.
 *
 * PLACEHOLDER: toggles persist and are honoured by the service's package gate,
 * but there is only one built-in rule set, so "enabled" currently means "run
 * the same rules as everywhere else". Per-app rule assignment is the natural
 * next step — see [AppPrefs.isPackageEnabled].
 */
class WhitelistActivity : BaseActivity() {

    private lateinit var binding: ActivityWhitelistBinding
    private lateinit var prefs: AppPrefs
    private lateinit var adapter: WhitelistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWhitelistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setUpToolbar(binding.toolbar, R.string.title_whitelist)
        applySystemBarInsets(binding.root)

        prefs = AppPrefs.get(this)

        adapter = WhitelistAdapter { entry, enabled ->
            prefs.setPackageEnabled(entry.packageName, enabled)
        }

        binding.listApps.layoutManager = LinearLayoutManager(this)
        binding.listApps.adapter = adapter
        binding.btnReload.setOnClickListener { loadApps() }

        loadApps()
    }

    private fun loadApps() {
        binding.textCount.setText(R.string.whitelist_loading)
        binding.textEmpty.visibility = View.GONE

        // queryIntentActivities can take a while on a device with many apps, so
        // it stays off the main thread.
        thread(name = "whitelist-load") {
            val pm = packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

            val rows = pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .asSequence()
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != packageName }
                .map { info ->
                    WhitelistAdapter.AppRow(
                        entry = WhitelistEntry(
                            packageName = info.packageName,
                            appName = info.loadLabel(pm).toString(),
                            enabled = prefs.isPackageEnabled(info.packageName),
                        ),
                        icon = info.loadIcon(pm),
                    )
                }
                .sortedBy { it.entry.appName }
                .toList()

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submit(rows)
                binding.textCount.text = getString(R.string.unit_count, rows.size)
                binding.textEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
