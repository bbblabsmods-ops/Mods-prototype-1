package com.example.wdoverlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Mono.BLACK
        window.navigationBarColor = Mono.BLACK

        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(Mono.WHITE); setPadding(0, pad / 2, 0, 0)
        }
        fun field(hint: String, number: Boolean = true) = EditText(this).apply {
            this.hint = hint
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            Mono.edit(this)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }; Mono.button(this, d)
        }
        // Two-tap confirm: the first tap arms the button, a second tap within 3 seconds runs it.
        fun confirmButton(name: String, onConfirm: () -> Unit) = Button(this).apply {
            text = name; Mono.button(this, d)
            var armed = false
            setOnClickListener {
                if (!armed) {
                    armed = true
                    text = "Tap again to confirm"
                    postDelayed({ armed = false; text = name }, 3000)
                } else {
                    armed = false
                    text = name
                    onConfirm()
                }
            }
        }
        // Puts a button side by side with others in a row.
        fun <T : Button> side(b: T): T {
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) }
            return b
        }
        fun row(vararg buttons: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it) }
        }

        // Runs a command and shows what it printed.
        fun showCmd(command: String) = Adb.run({ Adb.shell(this, command) }) { show(it) }
        // Runs a command and shows a short message (or the error if ADB isn't connected).
        fun setCmd(command: String, message: String) = Adb.run({
            val r = Adb.shell(this, command)
            if (r == "Not connected.") r else message
        }) { show(it) }

        val title = TextView(this).apply { text = "BBBlabs"; textSize = 28f; setTextColor(Mono.WHITE) }
        status = TextView(this).apply { textSize = 18f; setTextColor(Mono.WHITE) }
        output = TextView(this).apply {
            setTextIsSelectable(true); setTextColor(Mono.WHITE); setBackgroundColor(0xCC000000.toInt())
            setPadding(pad, pad / 2, pad, pad / 2); text = "Ready."
        }

        val ipField = field("Device IP (127.0.0.1 or your Wi-Fi IP)", number = false).apply { setText("127.0.0.1") }
        val pairPort = field("Pairing port")
        val pairCode = field("Pairing code")
        val debugPort = field("Wireless debugging port")
        val cmd = field("Shell command (e.g. getprop ro.product.model)", number = false)

        fun host(): String = ipField.text.toString().trim().ifEmpty { "127.0.0.1" }

        val colorRow = row(
            side(button("White") { Mono.saveAccent(this, Mono.WHITE); recreate() }),
            side(button("Red") { Mono.saveAccent(this, Mono.RED); recreate() }),
            side(button("Blue") { Mono.saveAccent(this, Mono.BLUE); recreate() }),
            side(button("Purple") { Mono.saveAccent(this, Mono.PURPLE); recreate() })
        )

        root.addView(title)
        root.addView(status)
        root.addView(label("Theme color"))
        root.addView(colorRow)

        // ---- Connect ----
        root.addView(label("1. Turn on Wireless Debugging, then tap \"Pair device with pairing code\"."))
        root.addView(button("Open Developer Options") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        })
        root.addView(label("IP address (the one shown in Wireless Debugging, or 127.0.0.1)"))
        root.addView(ipField)
        root.addView(button("Use my Wi-Fi IP") {
            Adb.run({ Adb.wifiIp() ?: "" }) { ip ->
                if (ip.isEmpty()) show("Couldn't find the Wi-Fi IP. Type it in by hand.")
                else { ipField.setText(ip); show("IP set to $ip") }
            }
        })
        root.addView(pairPort); root.addView(pairCode)
        root.addView(button("Pair") {
            val port = pairPort.text.toString().toIntOrNull()
            if (port == null) show("Enter the pairing port.") else
                Adb.run({ Adb.pair(this, host(), port, pairCode.text.toString().trim()) }) { show(it) }
        })
        root.addView(label("2. Enter the port shown on the main Wireless Debugging screen."))
        root.addView(debugPort)
        root.addView(button("Connect") {
            val port = debugPort.text.toString().toIntOrNull()
            if (port == null) show("Enter the debug port.") else
                Adb.run({ Adb.connect(this, host(), port) }) { show(it) }
        })

        // ---- Tools ----
        root.addView(label("3. Info"))
        root.addView(row(
            side(button("Device info") { showCmd("getprop ro.product.model; getprop ro.build.version.release") }),
            side(button("Battery") { showCmd("dumpsys battery | head -n 12") }),
            side(button("Wi-Fi IP") { showCmd("ip -f inet addr show wlan0") })
        ))

        root.addView(label("4. Refresh rate"))
        root.addView(row(
            side(button("60 Hz") { setCmd("setprop debug.oculus.refreshRate 60", "Refresh rate set to 60 Hz.") }),
            side(button("72 Hz") { setCmd("setprop debug.oculus.refreshRate 72", "Refresh rate set to 72 Hz.") }),
            side(button("90 Hz") { setCmd("setprop debug.oculus.refreshRate 90", "Refresh rate set to 90 Hz.") }),
            side(button("120 Hz") { setCmd("setprop debug.oculus.refreshRate 120", "Refresh rate set to 120 Hz.") })
        ))
        root.addView(row(
            side(confirmButton("200 Hz (FPS)") {
                setCmd("setprop debug.oculus.refreshRate 200", "Requested 200. If the screen looks wrong, tap Reset to normal.")
            }),
            side(confirmButton("Max") {
                setCmd("setprop debug.oculus.refreshRate 1000", "Requested 1000. The screen's real limit is far lower, so this is likely ignored. Tap Reset to normal if anything looks wrong.")
            })
        ))

        root.addView(label("5. Boundary"))
        root.addView(row(
            side(confirmButton("Delete boundary") {
                setCmd("setprop debug.oculus.guardian_pause 1", "Boundary paused. Be careful of nearby objects.")
            }),
            side(button("Restore boundary") {
                setCmd("setprop debug.oculus.guardian_pause 0", "Boundary restored.")
            })
        ))

        root.addView(label("If anything looks wrong:"))
        root.addView(button("Reset to normal (boundary on, 90 Hz)") {
            setCmd(
                "setprop debug.oculus.guardian_pause 0; setprop debug.oculus.refreshRate 90",
                "Reset: boundary on, 90 Hz."
            )
        })

        // ---- Metrics HUD (uses Meta's OVR Metrics Tool, which must be installed) ----
        val omms = "am broadcast -n com.oculus.ovrmonitormetricsservice/.SettingsBroadcastReceiver -a com.oculus.ovrmonitormetricsservice"
        fun hud(vararg parts: String) = parts.joinToString("; ") { "$omms.$it" }
        val hudNote = "Sent. If the HUD doesn't appear, open OVR Metrics Tool once, turn on Enable Persistent Overlay, and restart the headset."

        root.addView(label("6. Metrics HUD (battery, FPS, refresh rate, battery temp, screen tears)"))
        root.addView(button("Show HUD at the top") {
            setCmd(
                hud(
                    "DISABLE_STATS",
                    "DISABLE_GRAPH",
                    "ENABLE_STAT --es stat battery_level_percentage",
                    "ENABLE_STAT --es stat average_frame_rate",
                    "ENABLE_STAT --es stat display_refresh_rate",
                    "ENABLE_STAT --es stat battery_temperature_celcius",
                    "ENABLE_STAT --es stat screen_tear_count",
                    "ENABLE_OVERLAY --eb headlocked true --ef pitch 20.0"
                ),
                hudNote
            )
        })
        root.addView(row(
            side(button("HUD up") { setCmd(hud("ENABLE_OVERLAY --eb headlocked true --ef pitch 20.0"), hudNote) }),
            side(button("HUD center") { setCmd(hud("ENABLE_OVERLAY --eb headlocked true --ef pitch 0.0"), hudNote) }),
            side(button("Hide HUD") { setCmd(hud("DISABLE_OVERLAY"), "HUD hidden.") })
        ))
        root.addView(button("Open OVR Metrics Tool") { showCmd("am start omms://app") })

        root.addView(label("7. Run your own command"))
        root.addView(cmd)
        root.addView(button("Run") {
            val c = cmd.text.toString().trim()
            if (c.isNotEmpty()) showCmd(c)
        })

        val scroll = ScrollView(this).apply { addView(root) }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Mono.screenBackground()
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(output, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(outer)
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun show(text: String) { output.text = text; refreshStatus() }

    private fun refreshStatus() {
        Adb.run({ if (Adb.isConnected(this)) "ADB: connected" else "ADB: not connected" }) { status.text = it }
    }
}
