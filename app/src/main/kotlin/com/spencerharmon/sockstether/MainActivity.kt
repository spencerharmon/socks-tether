package com.spencerharmon.sockstether

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny manual control; the normal path is the adb start/stop from the host. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
        }
        status = TextView(this).apply { textSize = 18f }
        val start = Button(this).apply {
            text = "Start"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, ProxyService::class.java))
                refreshSoon()
            }
        }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener {
                startForegroundService(
                    Intent(this@MainActivity, ProxyService::class.java).setAction(ProxyService.ACTION_STOP)
                )
                refreshSoon()
            }
        }
        val help = TextView(this).apply {
            text = "SOCKS5 proxy for a USB-tethered host. Listens on port ${ProxyService.DEFAULT_PORT}, " +
                "serving only clients on the USB-tether subnet."
        }
        root.addView(status)
        root.addView(start)
        root.addView(stop)
        root.addView(help)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refreshSoon() { handler.postDelayed({ refresh() }, 500) }

    private fun refresh() {
        status.text = when {
            ProxyService.running -> "Running"
            ProxyService.lastError != null -> "Stopped (error: ${ProxyService.lastError})"
            else -> "Stopped"
        }
    }
}
