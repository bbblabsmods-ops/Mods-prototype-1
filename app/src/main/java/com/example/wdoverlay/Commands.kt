package com.example.wdoverlay

/** One button: what it says, the command it runs, and whether it needs a second tap. */
class ShellCmd(val label: String, val command: String, val confirm: Boolean = false)

/** The command library, grouped. Everything here is read-only or easy to undo. */
object Commands {
    val groups: List<Pair<String, List<ShellCmd>>> = listOf(
        "Device" to listOf(
            ShellCmd("Model", "getprop ro.product.model"),
            ShellCmd("Android version", "getprop ro.build.version.release"),
            ShellCmd("Build number", "getprop ro.build.display.id"),
            ShellCmd("Serial", "getprop ro.serialno"),
            ShellCmd("Uptime", "uptime"),
            ShellCmd("Date and time", "date"),
            ShellCmd("Kernel", "uname -a"),
            ShellCmd("CPU", "cat /proc/cpuinfo | head -n 24"),
            ShellCmd("Memory", "cat /proc/meminfo | head -n 12")
        ),
        "Battery and heat" to listOf(
            ShellCmd("Battery details", "dumpsys battery"),
            ShellCmd("Temperature", "dumpsys battery | grep -i temp"),
            ShellCmd("Battery stats", "dumpsys batterystats | head -n 40"),
            ShellCmd("Thermal", "dumpsys thermalservice | head -n 40"),
            ShellCmd("Power state", "dumpsys power | head -n 40")
        ),
        "Storage" to listOf(
            ShellCmd("Disk space", "df -h"),
            ShellCmd("Downloads size", "du -sh /sdcard/Download"),
            ShellCmd("Folders", "ls /sdcard")
        ),
        "Apps" to listOf(
            ShellCmd("Installed apps", "pm list packages -3"),
            ShellCmd("All apps", "pm list packages | sort | head -n 150"),
            ShellCmd("System apps", "pm list packages -s | sort | head -n 150"),
            ShellCmd("Disabled apps", "pm list packages -d"),
            ShellCmd("Running", "ps -A | head -n 60"),
            ShellCmd("Top CPU", "top -b -n 1 | head -n 20"),
            ShellCmd("Front app", "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | head -n 5"),
            ShellCmd("Close background apps", "am kill-all")
        ),
        "Display" to listOf(
            ShellCmd("Display info", "dumpsys display | head -n 60"),
            ShellCmd("Screen size", "wm size"),
            ShellCmd("Density", "wm density"),
            ShellCmd("Refresh rate now", "dumpsys display | grep -i -E 'refreshrate|fps' | head -n 12")
        ),
        "Network" to listOf(
            ShellCmd("Wi-Fi details", "dumpsys wifi | head -n 60"),
            ShellCmd("Wi-Fi signal", "dumpsys wifi | grep -i -E 'rssi|linkspeed' | head -n 6"),
            ShellCmd("All addresses", "ip addr"),
            ShellCmd("Routes", "ip route"),
            ShellCmd("DNS servers", "getprop | grep -i dns"),
            ShellCmd("Ping 8.8.8.8", "ping -c 4 8.8.8.8"),
            ShellCmd("Ping google.com", "ping -c 4 google.com"),
            ShellCmd("Debug port", "getprop service.adb.tls.port")
        ),
        "Settings" to listOf(
            ShellCmd("Animations: show", "settings get global window_animation_scale; settings get global transition_animation_scale; settings get global animator_duration_scale"),
            ShellCmd("Animations 0.5x", "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5"),
            ShellCmd("Animations 1x", "settings put global window_animation_scale 1.0; settings put global transition_animation_scale 1.0; settings put global animator_duration_scale 1.0"),
            ShellCmd("Animations off", "settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0"),
            ShellCmd("Stay awake: on", "settings put global stay_on_while_plugged_in 3"),
            ShellCmd("Stay awake: off", "settings put global stay_on_while_plugged_in 0"),
            ShellCmd("Bluetooth state", "settings get global bluetooth_on")
        ),
        "Logs" to listOf(
            ShellCmd("Recent log", "logcat -d -t 60"),
            ShellCmd("Errors only", "logcat -d *:E | tail -n 50"),
            ShellCmd("Crashes", "logcat -d -b crash | tail -n 50"),
            ShellCmd("Clear log", "logcat -c")
        ),
        "System" to listOf(
            ShellCmd("Services", "service list | head -n 40"),
            ShellCmd("Oculus properties", "getprop | grep -i oculus | head -n 60"),
            ShellCmd("Restart headset", "reboot", confirm = true),
            ShellCmd("Power off", "reboot -p", confirm = true)
        )
    )
}
