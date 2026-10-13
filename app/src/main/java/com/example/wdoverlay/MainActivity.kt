package com.example.wdoverlay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var outputScroll: ScrollView

    // The keep-alive reports reconnects here.
    private val messageListener: (String) -> Unit = { msg -> runOnUiThread { show(msg) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Mono.BLACK
        window.navigationBarColor = Mono.BLACK

        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val gap = (3 * d).toInt()
        val prefs = getSharedPreferences("wd", MODE_PRIVATE)

        // ---------- small helpers ----------
        fun label(t: String) = TextView(this).apply {
            text = t; textSize = 16f; setTextColor(Mono.WHITE); setPadding(0, pad / 2, 0, pad / 4)
        }
        fun inputBox(hint: String, numbersOnly: Boolean = false) = EditText(this).apply {
            this.hint = hint
            inputType = if (numbersOnly) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
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
        // Puts a view side by side with others in a row.
        fun <T : View> side(v: T): T {
            v.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, gap, 4, gap) }
            return v
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it) }
        }
        fun page() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // Runs a command and shows what it printed.
        fun showCmd(command: String) = Adb.run({ Adb.shell(this, command) }) { show(it) }
        // Runs a command and shows a short message (or the error if ADB isn't connected).
        fun setCmd(command: String, message: String) = Adb.run({
            val r = Adb.shell(this, command)
            if (r == "Not connected." || r.startsWith("Error") || r.startsWith("Connection lost")) r else message
        }) { show(it) }
        // Runs slow work (like searching for Wireless Debugging) without blocking the other buttons.
        fun inBackground(block: () -> String) {
            Thread {
                val message = block()
                runOnUiThread { show(message) }
            }.also { it.isDaemon = true; it.start() }
        }
        // Runs one of the library commands and shows what it printed.
        fun runCmd(command: String) = Adb.run({
            val r = Adb.shell(this, command)
            if (r == "(no output)") "Done." else r
        }) { show(it) }
        fun commandButton(c: ShellCmd): Button =
            if (c.confirm) confirmButton(c.label) { runCmd(c.command) } else button(c.label) { runCmd(c.command) }

        // Splits "192.168.1.5:37123" into address and port; a plain number is just a port.
        fun splitHostPort(raw: String): Pair<String?, Int?> {
            val t = raw.trim()
            return if (t.contains(":")) {
                Pair(t.substringBeforeLast(":").trim().ifEmpty { null }, t.substringAfterLast(":").trim().toIntOrNull())
            } else {
                Pair(null, t.toIntOrNull())
            }
        }

        // ---------- inputs ----------
        val ipField = inputBox("Device IP (127.0.0.1 or your Wi-Fi IP)").apply { setText("127.0.0.1") }
        val pairPort = inputBox("Pairing port (or ip:port)")
        val pairCode = inputBox("Pairing code", numbersOnly = true)
        val debugPort = inputBox("Wireless debugging port (or ip:port)")
        val cmd = inputBox("Shell command (e.g. getprop ro.product.model)")
        val pkgField = inputBox("Package name, e.g. com.oculus.browser")

        fun host(): String = ipField.text.toString().trim().ifEmpty { "127.0.0.1" }

        // ---------- header (always visible) ----------
        val title = TextView(this).apply { text = "BBBlabs"; textSize = 28f; setTextColor(Mono.WHITE) }
        status = TextView(this).apply { textSize = 16f; setTextColor(Mono.WHITE) }
        output = TextView(this).apply {
            setTextIsSelectable(true); setTextColor(Mono.WHITE)
            setPadding(pad, pad / 2, pad, pad / 2); text = "Ready."
        }
        outputScroll = ScrollView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            addView(output)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 4)
            addView(title)
            addView(status)
            addView(button("Auto connect (no IP or port needed)") {
                show("Looking for Wireless Debugging...")
                inBackground { Adb.autoConnect(this@MainActivity) }
            })
        }

        // ---------- tabs ----------
        val tabNames = listOf("Connect", "Display", "Tools", "Commands", "HUD", "Theme")
        val pages = tabNames.map { page() }
        val tabButtons = tabNames.map { name -> Button(this).apply { text = name; textSize = 13f } }
        fun selectTab(i: Int) {
            pages.forEachIndexed { index, p -> p.visibility = if (index == i) View.VISIBLE else View.GONE }
            tabButtons.forEachIndexed { index, b -> Mono.tab(b, d, index == i) }
            prefs.edit().putInt("tab", i).apply()
        }
        tabButtons.forEachIndexed { index, b ->
            b.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(2, 0, 2, 0) }
            b.setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            b.setOnClickListener { selectTab(index) }
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tabButtons.forEach { addView(it) }
        }
        val tabBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(pad / 2, 0, pad / 2, pad / 4)
            addView(tabRow)
        }

        val connectPage = pages[0]
        val displayPage = pages[1]
        val toolsPage = pages[2]
        val commandsPage = pages[3]
        val hudPage = pages[4]
        val themePage = pages[5]

        // ---------- Connect page ----------
        connectPage.addView(label("Step 1: Turn on Wireless Debugging, tap \"Pair device with pairing code\", and leave that window open."))
        connectPage.addView(button("Open Developer Options") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        })
        connectPage.addView(label("Step 2: Type what the pairing window shows"))
        connectPage.addView(row(side(pairPort), side(pairCode)))
        connectPage.addView(button("Pair") {
            val (typedHost, port) = splitHostPort(pairPort.text.toString())
            if (port == null) {
                show("Enter the pairing port (or ip:port) exactly as the pairing window shows it.")
            } else {
                show("Pairing...")
                inBackground { Adb.pair(this, typedHost ?: host(), port, pairCode.text.toString().trim()) }
            }
        })
        connectPage.addView(label("Step 3: Connect by hand (only if Auto connect can't find it)"))
        connectPage.addView(row(side(ipField), side(debugPort)))
        connectPage.addView(row(
            side(button("Use my Wi-Fi IP") {
                Adb.run({ Adb.wifiIp() ?: "" }) { ip ->
                    if (ip.isEmpty()) show("Couldn't find the Wi-Fi IP. Type it in by hand.")
                    else { ipField.setText(ip); show("IP set to $ip") }
                }
            }),
            side(button("Connect") {
                val (typedHost, port) = splitHostPort(debugPort.text.toString())
                if (port == null) {
                    show("Enter the port shown on the main Wireless Debugging screen.")
                } else {
                    show("Connecting...")
                    inBackground { Adb.connect(this, typedHost ?: host(), port) }
                }
            })
        ))

        // ---------- Display page ----------
        displayPage.addView(label("Refresh rate"))
        displayPage.addView(row(
            side(button("60 Hz") { setCmd("setprop debug.oculus.refreshRate 60", "Refresh rate set to 60 Hz.") }),
            side(button("72 Hz") { setCmd("setprop debug.oculus.refreshRate 72", "Refresh rate set to 72 Hz.") })
        ))
        displayPage.addView(row(
            side(button("90 Hz") { setCmd("setprop debug.oculus.refreshRate 90", "Refresh rate set to 90 Hz.") }),
            side(button("120 Hz") { setCmd("setprop debug.oculus.refreshRate 120", "Refresh rate set to 120 Hz.") })
        ))
        displayPage.addView(row(
            side(confirmButton("200 Hz (FPS)") {
                setCmd("setprop debug.oculus.refreshRate 200", "Requested 200. If the screen looks wrong, tap Reset to normal.")
            }),
            side(button("Max") {
                Adb.run({
                    if (!Adb.isConnected(this)) "Not connected." else {
                        val found = Adb.maxRefreshRate(this)
                        val rate = found ?: 120
                        Adb.shell(this, "setprop debug.oculus.refreshRate $rate")
                        if (found != null) "Max: set to $rate Hz, the highest this headset reports."
                        else "Couldn't read this headset's supported rates, so I tried $rate Hz."
                    }
                }) { show(it) }
            })
        ))
        displayPage.addView(label("Boundary"))
        displayPage.addView(row(
            side(confirmButton("Delete boundary") {
                setCmd("setprop debug.oculus.guardian_pause 1", "Boundary paused. Be careful of nearby objects.")
            }),
            side(button("Restore boundary") {
                setCmd("setprop debug.oculus.guardian_pause 0", "Boundary restored.")
            })
        ))
        displayPage.addView(label("If anything looks wrong"))
        displayPage.addView(button("Reset to normal (boundary on, 90 Hz)") {
            setCmd(
                "setprop debug.oculus.guardian_pause 0; setprop debug.oculus.refreshRate 90",
                "Reset: boundary on, 90 Hz."
            )
        })
        // Runs detached on the headset so it keeps going after the connection drops.
        val wifiScript = "nohup sh -c 'svc wifi disable; sleep 5; svc wifi enable; sleep 12; settings put global adb_wifi_enabled 1' >/dev/null 2>&1 &"
        displayPage.addView(label("Wi-Fi"))
        displayPage.addView(confirmButton("Wi-Fi off for 5 seconds, then back on") {
            Adb.run({
                if (!Adb.isConnected(this)) "Not connected." else {
                    Adb.shell(this, wifiScript, retry = false)
                    Adb.wantConnected = true
                    "Wi-Fi is going off for 5 seconds. The app reconnects by itself (it checks every 10 seconds)."
                }
            }) { show(it) }
        })

        // ---------- Tools page ----------
        toolsPage.addView(label("Info"))
        toolsPage.addView(row(
            side(button("Device info") { showCmd("getprop ro.product.model; getprop ro.build.version.release") }),
            side(button("Battery") { showCmd("dumpsys battery | head -n 12") })
        ))
        toolsPage.addView(button("Wi-Fi IP") { showCmd("ip -f inet addr show wlan0") })
        toolsPage.addView(label("Run your own command"))
        toolsPage.addView(cmd)
        toolsPage.addView(button("Run") {
            val c = cmd.text.toString().trim()
            if (c.isNotEmpty()) showCmd(c)
        })

        // ---------- Commands page ----------
        commandsPage.addView(label("Tap a command to run it. The result shows in the box at the bottom."))
        Commands.groups.forEach { (groupName, items) ->
            commandsPage.addView(label(groupName))
            items.chunked(2).forEach { pair ->
                val views = ArrayList<View>()
                pair.forEach { c -> views.add(side(commandButton(c))) }
                if (pair.size == 1) views.add(side(View(this)))
                commandsPage.addView(row(*views.toTypedArray()))
            }
        }
        // Commands that need an app's package name.
        fun withPackage(build: (String) -> String) {
            val pkg = pkgField.text.toString().trim()
            if (!Regex("[A-Za-z0-9_.]+").matches(pkg)) {
                show("Type a package name first (letters, numbers and dots only).")
            } else {
                runCmd(build(pkg))
            }
        }
        commandsPage.addView(label("App tools (type a package name)"))
        commandsPage.addView(pkgField)
        commandsPage.addView(row(
            side(button("App info") { withPackage { "dumpsys package $it | head -n 60" } }),
            side(button("Launch") { withPackage { "monkey -p $it -c android.intent.category.LAUNCHER 1" } })
        ))
        commandsPage.addView(row(
            side(button("Force stop") { withPackage { "am force-stop $it" } }),
            side(confirmButton("Uninstall") { withPackage { "pm uninstall $it" } })
        ))

        // ---------- HUD page (uses Meta's OVR Metrics Tool, which must be installed) ----------
        val omms = "am broadcast -n com.oculus.ovrmonitormetricsservice/.SettingsBroadcastReceiver -a com.oculus.ovrmonitormetricsservice"
        fun hud(vararg parts: String) = parts.joinToString("; ") { "$omms.$it" }
        val hudNote = "Sent. If the HUD doesn't appear, open OVR Metrics Tool once, turn on Enable Persistent Overlay, and restart the headset."

        hudPage.addView(label("Metrics HUD (battery, FPS, refresh rate, battery temp, screen tears)"))
        hudPage.addView(button("Show HUD at the top") {
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
        hudPage.addView(row(
            side(button("HUD up") { setCmd(hud("ENABLE_OVERLAY --eb headlocked true --ef pitch 20.0"), hudNote) }),
            side(button("HUD center") { setCmd(hud("ENABLE_OVERLAY --eb headlocked true --ef pitch 0.0"), hudNote) })
        ))
        hudPage.addView(row(
            side(button("Hide HUD") { setCmd(hud("DISABLE_OVERLAY"), "HUD hidden.") }),
            side(button("Open OVR Metrics Tool") { showCmd("am start omms://app") })
        ))

        // ---------- Theme page ----------
        themePage.addView(label("Theme color"))
        themePage.addView(row(
            side(button("White") { Mono.saveAccent(this, Mono.WHITE); recreate() }),
            side(button("Red") { Mono.saveAccent(this, Mono.RED); recreate() })
        ))
        themePage.addView(row(
            side(button("Blue") { Mono.saveAccent(this, Mono.BLUE); recreate() }),
            side(button("Purple") { Mono.saveAccent(this, Mono.PURPLE); recreate() })
        ))

        // ---------- put it together ----------
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
            pages.forEach { addView(it) }
        }
        val scroll = ScrollView(this).apply { addView(content) }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Mono.screenBackground()
            addView(header)
            addView(tabBar)
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(outputScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (150 * d).toInt()))
        }
        setContentView(outer)
        selectTab(prefs.getInt("tab", 0).coerceIn(0, tabNames.size - 1))

        // Connect right away if Wireless Debugging is already on and paired, and keep it connected.
        Adb.onMessage = messageListener
        Adb.startWatchdog(this)
        inBackground { Adb.autoConnect(this) }
    }

    override fun onDestroy() {
        if (Adb.onMessage === messageListener) Adb.onMessage = null
        super.onDestroy()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun show(text: String) {
        output.text = if (text.length > 6000) text.take(6000) + "\n... (cut off; run a narrower command to see the rest)" else text
        outputScroll.scrollTo(0, 0)
        refreshStatus()
    }

    private fun refreshStatus() {
        Adb.run({ if (Adb.isConnected(this)) "ADB: connected" else "ADB: not connected" }) { status.text = it }
    }
}
