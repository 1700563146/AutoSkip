package com.example.autoskip.ui

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar

/**
 * Shared chrome for the four screens.
 *
 * Handles the two things that are easy to get subtly wrong on a modern target
 * SDK: opting into edge-to-edge consistently, and then keeping the UI out of the
 * system bars.
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so it applies to the initial window.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
    }

    protected fun setUpToolbar(toolbar: MaterialToolbar, titleRes: Int, showUp: Boolean = true) {
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            setTitle(titleRes)
            setDisplayHomeAsUpEnabled(showUp)
        }
        if (showUp) {
            toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
    }

    /**
     * Pads [root] so nothing is drawn under the status bar, navigation bar or a
     * display cutout. The root is a plain vertical container holding the toolbar
     * and the content, so one padding call moves the whole screen.
     */
    protected fun applySystemBarInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            insets
        }
    }
}
