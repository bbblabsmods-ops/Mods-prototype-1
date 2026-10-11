package com.example.wdoverlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        window.statusBarColor = Mono.BLACK
        window.navigationBarColor = Mono.BLACK

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val prefs = getSharedPreferences("wd", MODE_PRIVATE)

        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setTextColor(Mono.WHITE); setPadding(0, pad / 2, 0, 0) }
        fun field(hint: String, number: Boolean = true) = EditText(this).apply {
            this.hint = hint
            if (number) inputType = InputType.TYPE_CLASS_NUMBER
            Mono.edit(this)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply { text = t; setOnClickListener { onClick() }; Mono.button(this, resources.displayMetrics.density) }

        val title = TextView(this).apply { text = "BBBlabs"; textSize = 28f; setTextColor(Mono.WHITE) }
        status = TextView(this).apply { textSize = 18f; setTextColor(Mono.WHITE) }
        output = TextView(this).apply { setTextIsSelectable(true); setTextColor(Mono.WHITE); setPadding(0, pad, 0, 0) }

        val ipField = field("Device IP (127.0.0.1 or your Wi-Fi IP)", number = false).apply {
            setText("127.0.0.1")
        }
        val pairPort = field("Pairing port")
        val pairCode = field("Pairing code")
        val debugPort = field("Wireless debugging port")
        val cmd = field("Shell command (e.g. getprop ro.product.model)", number = false)
        val keyLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(Mono.WHITE)
            text = "Open button: ${prefs.getString("toggleKey", "BTN_X")}"
        }

        fun host(): String = ipField.text.toString().trim().ifEmpty { "127.0.0.1" }

        fun colorButton(name: String, color: Int) = Button(this).apply {
            text = name
            Mono.button(this, resources.displayMetrics.density)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener { Mono.saveAccent(this@MainActivity, color); recreate() }
        }
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(colorButton("White", Mono.WHITE))
            addView(colorButton("Red", Mono.RED))
            addView(colorButton("Blue", Mono.BLUE))
            addView(colorButton("Purple", Mono.PURPLE))
        }

        root.addView(title)
        root.addView(status)
        root.addView(label("Theme color (the overlay uses it the next time you start it)"))
        root.addView(colorRow)
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
        root.addView(label("3. Run a command"))
        root.addView(cmd)
        root.addView(button("Run") {
            val c = cmd.text.toString().trim()
            if (c.isNotEmpty()) Adb.run({ Adb.shell(this, c) }) { show(it) }
        })
        root.addView(label("4. Choose the controller button that opens the overlay"))
        root.addView(keyLabel)
        root.addView(button("Learn button (then press it on the controller)") {
            show("Press the button you want to use within 15 seconds...")
            InputWatcher.learn(this) { name ->
                if (name == null) {
                    show("No button press detected. Make sure ADB is connected and try again.")
                } else {
                    prefs.edit().putString("toggleKey", name).apply()
                    keyLabel.text = "Open button: $name"
                    show("Saved: $name. Start the overlay again to use it.")
                }
            }
        })
        root.addView(label("5. Overlay"))
        root.addView(button("Grant overlay permission") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("Start overlay") {
            if (!Settings.canDrawOverlays(this)) show("Grant overlay permission first.")
            else startForegroundService(Intent(this, OverlayService::class.java))
        })
        root.addView(button("Stop overlay") { stopService(Intent(this, OverlayService::class.java)) })
        root.addView(output)

        setContentView(ScrollView(this).apply { background = Mono.screenBackground(); addView(root) })
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun show(text: String) { output.text = text; refreshStatus() }

    private fun refreshStatus() {
        Adb.run({ if (Adb.isConnected(this)) "ADB: connected" else "ADB: not connected" }) { status.text = it }
    }
}
