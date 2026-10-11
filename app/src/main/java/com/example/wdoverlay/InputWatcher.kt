package com.example.wdoverlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.Closeable

class InputWatcher(private val ctx: Context) {

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var stream: Closeable? = null

    /** Calls onKeyDown(keyName) for every key-down event while running. */
    fun start(onKeyDown: (String) -> Unit) {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                try {
                    val m = AdbManager.get(ctx)
                    if (!m.isConnected) {
                        Thread.sleep(2000)
                        continue
                    }
                    val s = m.openStream("shell:getevent -l")
                    stream = s
                    val reader = s.openInputStream().bufferedReader()
                    while (running) {
                        val line = reader.readLine() ?: break
                        val match = KEY_DOWN.find(line)
                        if (match != null) onKeyDown(match.groupValues[1])
                    }
                } catch (_: Throwable) {
                }
                if (running) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { stream?.close() } catch (_: Throwable) {}
        thread?.interrupt()
    }

    companion object {
        private val KEY_DOWN = Regex("""EV_KEY\s+(\S+)\s+DOWN""")

        /** Waits up to 15 seconds for one key press and reports its name (null if none). */
        fun learn(ctx: Context, onResult: (String?) -> Unit) {
            Thread {
                var result: String? = null
                var s: Closeable? = null
                try {
                    val m = AdbManager.get(ctx)
                    if (m.isConnected) {
                        val stream = m.openStream("shell:getevent -l")
                        s = stream
                        Thread {
                            try { Thread.sleep(15000); stream.close() } catch (_: Throwable) {}
                        }.also { it.isDaemon = true; it.start() }
                        val reader = stream.openInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            val match = KEY_DOWN.find(line)
                            if (match != null) { result = match.groupValues[1]; break }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    try { s?.close() } catch (_: Throwable) {}
                }
                Handler(Looper.getMainLooper()).post { onResult(result) }
            }.also { it.isDaemon = true; it.start() }
        }
    }
}
