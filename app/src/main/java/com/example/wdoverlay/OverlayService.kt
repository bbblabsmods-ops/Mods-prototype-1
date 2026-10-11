package com.example.wdoverlay

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var watcher: InputWatcher? = null
    private var lastToggle = 0L
    private val main = Handler(Looper.getMainLooper())

    // The panel hides itself so it can never trap you in a game.
    private val autoHide = Runnable { panel?.visibility = View.GONE }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("overlay", "Overlay", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, "overlay")
            .setContentTitle("BBBlabs running")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build())

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = buildPanel()
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 200 }
        makeDraggable(view, lp)
        view.visibility = View.GONE          // always start hidden
        wm.addView(view, lp)
        panel = view

        val key = getSharedPreferences("wd", MODE_PRIVATE).getString("toggleKey", "BTN_X") ?: "BTN_X"
        if (AdbManager.get(this).isConnected) {
            watcher = InputWatcher(this).also { w ->
                w.start { k ->
                    if (k == key) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastToggle > 400) {
                            lastToggle = now
                            main.post { togglePanel() }
                        }
                    }
                }
            }
            Toast.makeText(this, "Overlay ready. Press $key to open or hide it.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "ADB not connected. Connect first, then start the overlay again.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPanel() {
        panel?.visibility = View.VISIBLE
        kick()
    }

    private fun hidePanel() {
        main.removeCallbacks(autoHide)
        panel?.visibility = View.GONE
    }

    private fun togglePanel() {
        if (panel?.visibility == View.VISIBLE) hidePanel() else showPanel()
    }

    /** Restarts the auto-hide countdown (8 seconds). */
    private fun kick() {
        main.removeCallbacks(autoHide)
        main.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun buildPanel(): View {
        val d = resources.displayMetrics.density
        val p = (10 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            Mono.panelBackground(this, d)
            setPadding(p, p, p, p)
        }
        val title = TextView(this).apply { text = "BBBlabs (drag me)"; setTextColor(Color.WHITE); textSize = 14f }
        val out = TextView(this).apply {
            setTextColor(Mono.WHITE); textSize = 12f; maxWidth = (260 * d).toInt()
            text = "Ready."
        }
        fun btn(label: String, action: () -> String) = Button(this).apply {
            text = label; textSize = 12f; Mono.button(this, d)
            setOnClickListener { kick(); Adb.run(action) { out.text = it } }
        }

        // Two-tap confirm: first tap arms the button, second tap (within 3s) runs it.
        fun confirmBtn(label: String, action: () -> String) = Button(this).apply {
            text = label; textSize = 12f; Mono.button(this, d)
            var armed = false
            setOnClickListener {
                kick()
                if (!armed) {
                    armed = true
                    text = "Tap again to confirm"
                    postDelayed({ armed = false; text = label }, 3000)
                } else {
                    armed = false
                    text = label
                    Adb.run(action) { out.text = it }
                }
            }
        }

        box.addView(title)
        box.addView(btn("Status") { if (Adb.isConnected(this)) "Connected" else "Not connected" })
        box.addView(btn("Device info") {
            Adb.shell(this, "getprop ro.product.model; getprop ro.build.version.release")
        })
        box.addView(btn("Battery") { Adb.shell(this, "dumpsys battery | head -n 12") })
        box.addView(btn("Wi-Fi IP") { Adb.shell(this, "ip -f inet addr show wlan0") })
        box.addView(confirmBtn("Delete boundary") {
            Adb.shell(this, "setprop debug.oculus.guardian_pause 1"); "Boundary paused. Be careful of nearby objects."
        })
        box.addView(btn("Restore boundary") {
            Adb.shell(this, "setprop debug.oculus.guardian_pause 0"); "Boundary restored."
        })
        box.addView(btn("60 Hz") {
            Adb.shell(this, "setprop debug.oculus.refreshRate 60"); "Refresh rate set to 60 Hz."
        })
        box.addView(btn("72 Hz") {
            Adb.shell(this, "setprop debug.oculus.refreshRate 72"); "Refresh rate set to 72 Hz."
        })
        box.addView(btn("90 Hz") {
            Adb.shell(this, "setprop debug.oculus.refreshRate 90"); "Refresh rate set to 90 Hz."
        })
        box.addView(btn("120 Hz") {
            Adb.shell(this, "setprop debug.oculus.refreshRate 120"); "Refresh rate set to 120 Hz."
        })
        box.addView(confirmBtn("200 Hz (FPS)") {
            Adb.shell(this, "setprop debug.oculus.refreshRate 200"); "Requested 200. If the screen looks wrong, tap 90 Hz to go back."
        })
        box.addView(confirmBtn("Max") {
            Adb.shell(this, "setprop debug.oculus.refreshRate 1000"); "Requested 1000. The screen's real limit is far lower, so this is likely ignored. Tap 90 Hz to go back."
        })
        box.addView(Button(this).apply {
            text = "Hide panel"; textSize = 12f; Mono.button(this, d)
            setOnClickListener { hidePanel() }
        })
        box.addView(btn("Close overlay") { stopSelf(); "" })
        box.addView(out)
        return box
    }

    private fun makeDraggable(v: View, lp: WindowManager.LayoutParams) {
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    kick()
                    startX = lp.x; startY = lp.y; touchX = e.rawX; touchY = e.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - touchX).toInt()
                    lp.y = startY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(v, lp); true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(autoHide)
        watcher?.stop()
        panel?.let { wm.removeView(it) }
        super.onDestroy()
    }

    companion object {
        private const val AUTO_HIDE_MS = 8000L
    }
}
