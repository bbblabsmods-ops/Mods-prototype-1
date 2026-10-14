package com.example.wdoverlay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val keyFile = File(context.filesDir, "adb_key.pk8")
    private val certFile = File(context.filesDir, "adb_cert.der")
    private val privKey: PrivateKey
    private val cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        if (keyFile.exists() && certFile.exists()) {
            privKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            cert = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it)
            }
        } else {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val name = X500Name("CN=WDOverlay")
            val from = Date()
            val to = Date(from.time + 10L * 365 * 24 * 3600 * 1000)
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger(64, SecureRandom()), from, to, name, kp.public
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val x509 = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            privKey = kp.private
            cert = x509
            keyFile.writeBytes(kp.private.encoded)
            certFile.writeBytes(x509.encoded)
        }
    }

    override fun getPrivateKey(): PrivateKey = privKey
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "WDOverlay"

    companion object {
        @Volatile private var instance: AdbManager? = null
        fun get(context: Context): AdbManager =
            instance ?: synchronized(this) {
                instance ?: AdbManager(context.applicationContext).also { instance = it }
            }
    }
}

object Adb {
    // Button commands run on a few threads so one slow command never blocks the others.
    private val commandPool = Executors.newFixedThreadPool(4)
    private val workers = Executors.newCachedThreadPool()
    private val connectLock = Any()
    private val running = AtomicInteger(0)

    /** Set once a connection has worked; the keep-alive then tries to keep it. */
    @Volatile var wantConnected = false
    /** Where the keep-alive reports what it did. */
    @Volatile var onMessage: ((String) -> Unit)? = null
    @Volatile private var watchdogStarted = false
    @Volatile private var reconnecting = false

    // Facts about the headset that never change, read only once.
    @Volatile private var cachedModel: String? = null
    @Volatile private var cachedMaxHz: Int? = null
    @Volatile private var factsLoaded = false
    @Volatile private var loadingFacts = false

    fun run(block: () -> String, onResult: (String) -> Unit) {
        commandPool.execute {
            val text = try { block() } catch (t: Throwable) { "Error: ${describe(t)}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(text) }
        }
    }

    /** True if a result text means the command did not work. */
    fun failed(r: String): Boolean =
        r.startsWith("Not connected") || r.startsWith("Reconnecting") ||
            r.startsWith("Error") || r.startsWith("Connection lost")

    private fun describe(t: Throwable): String {
        val msg = t.message ?: ""
        return if (msg.isEmpty()) t.javaClass.simpleName else "${t.javaClass.simpleName}: $msg"
    }

    // ---------- addresses ----------

    /** This device's Wi-Fi IPv4 address, or null if it can't be found. */
    fun wifiIp(): String? = try {
        val all = java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name == "wlan0") 0 else 1 }
        all.flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Throwable) {
        null
    }

    private fun ownAddresses(): Set<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .mapNotNull { (it as? java.net.Inet4Address)?.hostAddress }
            .toSet()
    } catch (_: Throwable) {
        emptySet()
    }

    /** The port Wireless Debugging is listening on, if the system will tell us. */
    private fun tlsPort(): Int? = try {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tls.port"))
        p.inputStream.bufferedReader().readText().trim().toIntOrNull()?.takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }

    /** Looks for this headset's own Wireless Debugging service on the network. */
    private fun discoverTls(ctx: Context, waitMs: Long): Pair<String, Int>? {
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = try {
            wifi?.createMulticastLock("bbblabs")?.apply { setReferenceCounted(false); acquire() }
        } catch (_: Throwable) {
            null
        }
        val mine = ownAddresses()
        val latch = CountDownLatch(1)
        var found: Pair<String, Int>? = null

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                try {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress
                            // only accept this headset itself, not other devices on the network
                            if (host != null && found == null && (host in mine || host == "127.0.0.1")) {
                                found = Pair(host, serviceInfo.port)
                                latch.countDown()
                            }
                        }
                    })
                } catch (_: Throwable) {
                }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { latch.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        try {
            nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
            latch.await(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        } finally {
            try { nsd.stopServiceDiscovery(discovery) } catch (_: Throwable) {}
            try { lock?.release() } catch (_: Throwable) {}
        }
        return found
    }

    // ---------- connection ----------

    /** Closes whatever connection is open so a fresh one can be made. */
    private fun dropConnection(m: AdbManager) {
        for (name in listOf("close", "disconnect")) {
            try { m.javaClass.getMethod(name).invoke(m); return } catch (_: Throwable) {}
        }
    }

    /** Runs a command on the headset, giving up if it doesn't answer in time. */
    private fun runShell(m: AdbManager, command: String, timeoutSec: Long = 12): String {
        val future = workers.submit(Callable<String> {
            val stream = m.openStream("shell:$command")
            stream.openInputStream().bufferedReader().use { it.readText() }.trim().ifEmpty { "(no output)" }
        })
        try {
            return future.get(timeoutSec, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw java.io.IOException("the headset didn't answer")
        }
    }

    /** True only if the headset really answers. */
    private fun ping(m: AdbManager, seconds: Long): Boolean =
        try { runShell(m, "echo ok", seconds).trim() == "ok" } catch (_: Throwable) { false }

    fun isConnected(ctx: Context): Boolean = AdbManager.get(ctx).isConnected

    fun pair(ctx: Context, host: String, port: Int, code: String): String = synchronized(connectLock) {
        val m = AdbManager.get(ctx)
        val hosts = listOfNotNull(host.trim().ifEmpty { null }, "127.0.0.1", wifiIp()).distinct()
        var reason = ""
        for (h in hosts) {
            try {
                if (m.pair(h, port, code)) {
                    wantConnected = true
                    return@synchronized "Paired (via $h). " + autoConnect(ctx)
                }
                return@synchronized "Pairing was refused. Check the code, and keep the \"Pair device with pairing code\" window open on the headset. The code and port change every time you open it."
            } catch (t: Throwable) {
                reason = describe(t)
                val cantReach = t is java.net.ConnectException || t is java.net.SocketTimeoutException ||
                    reason.contains("refused", true) || reason.contains("timed out", true) ||
                    reason.contains("unreachable", true)
                if (!cantReach) break
            }
        }
        "Pairing failed ($reason). Keep the pairing window open on the headset and type the port and code exactly as shown."
    }

    fun connect(ctx: Context, host: String, port: Int): String = synchronized(connectLock) {
        val m = AdbManager.get(ctx)
        dropConnection(m)
        val hosts = listOfNotNull(host.trim().ifEmpty { null }, "127.0.0.1", wifiIp()).distinct()
        var reason = ""
        for (h in hosts) {
            try {
                if (m.connect(h, port)) {
                    wantConnected = true
                    warmUp(ctx)
                    return@synchronized "Connected to $h:$port."
                }
                reason = "no answer from $h"
            } catch (t: Throwable) {
                reason = describe(t)
            }
        }
        "Connect failed ($reason). Use the port on the main Wireless Debugging screen (not the pairing port)."
    }

    /**
     * Finds Wireless Debugging by itself (no IP or port to type) and connects.
     * It checks the current connection first and only reconnects if it is really dead.
     * You still have to pair once with the pairing code.
     */
    fun autoConnect(ctx: Context): String = synchronized(connectLock) {
        val m = AdbManager.get(ctx)
        fun ok(message: String): String {
            wantConnected = true
            warmUp(ctx)
            return message
        }
        if (m.isConnected && ping(m, 6)) return@synchronized ok("Connected (already).")
        dropConnection(m)
        val notes = mutableListOf<String>()

        // 1) the ADB library's own search
        try { m.autoConnect(ctx, 10_000L) } catch (t: Throwable) { notes.add(describe(t)) }
        if (m.isConnected) return@synchronized ok("Connected (found automatically).")

        // 2) our own search for this headset's Wireless Debugging service
        val found = discoverTls(ctx, 6_000L)
        if (found != null) {
            try {
                if (m.connect(found.first, found.second)) {
                    return@synchronized ok("Connected to ${found.first}:${found.second}.")
                }
            } catch (t: Throwable) {
                notes.add("${found.first}:${found.second} ${describe(t)}")
            }
        } else {
            notes.add("Wireless Debugging wasn't found on the network")
        }

        // 3) the port the system reports
        val port = tlsPort()
        if (port != null) {
            for (h in listOfNotNull(wifiIp(), "127.0.0.1")) {
                try {
                    if (m.connect(h, port)) return@synchronized ok("Connected to $h:$port.")
                } catch (t: Throwable) {
                    notes.add("$h:$port ${describe(t)}")
                }
            }
        }

        "Couldn't connect automatically (" + notes.joinToString("; ").take(180) +
            "). Turn on Wireless Debugging and pair once."
    }

    /**
     * Runs a shell command. If the connection turns out to be dead it reconnects once and
     * tries again (retry = false for commands that must never run twice).
     */
    fun shell(ctx: Context, command: String, retry: Boolean = true): String {
        val m = AdbManager.get(ctx)
        if (!m.isConnected) {
            return if (reconnecting) "Reconnecting... try again in a few seconds."
            else "Not connected. Tap Auto connect at the top."
        }
        running.incrementAndGet()
        try {
            return try {
                runShell(m, command)
            } catch (t: Throwable) {
                if (!retry) return "Error: ${describe(t)}"
                val again = autoConnect(ctx)
                if (!again.startsWith("Connected")) return "Connection lost. $again"
                try { runShell(m, command) } catch (t2: Throwable) { "Error: ${describe(t2)}" }
            }
        } finally {
            running.decrementAndGet()
        }
    }

    // ---------- remembered facts about the headset ----------

    /** Reads the model and the supported refresh rates in a single call. */
    private fun loadFacts(ctx: Context) {
        val out = shell(ctx, "getprop ro.product.model; echo @@; dumpsys display | grep -o 'fps=[0-9.]*'")
        if (failed(out)) return
        val parts = out.split("@@")
        val model = parts[0].trim()
        if (model.isNotEmpty() && model != "(no output)") cachedModel = model
        if (parts.size > 1) {
            val hz = Regex("""fps=([0-9]+(?:\.[0-9]+)?)""").findAll(parts[1])
                .mapNotNull { it.groupValues[1].toDoubleOrNull() }
                .map { Math.round(it).toInt() }
                .filter { it in 30..240 }
                .toList()
                .maxOrNull()
            if (hz != null) cachedMaxHz = hz
        }
        factsLoaded = true
    }

    /** Loads the facts in the background right after connecting, so the Max buttons are instant. */
    private fun warmUp(ctx: Context) {
        if (factsLoaded || loadingFacts) return
        loadingFacts = true
        workers.execute {
            try { loadFacts(ctx) } catch (_: Throwable) {} finally { loadingFacts = false }
        }
    }

    /** The highest refresh rate this headset says it supports, or null if it can't be read. */
    fun maxRefreshRate(ctx: Context): Int? {
        if (cachedMaxHz == null) loadFacts(ctx)
        return cachedMaxHz
    }

    /** The highest CPU level this headset model offers (Quest 2 and Pro: 6, Quest 3 and 3S: 5). */
    fun maxCpuLevel(ctx: Context): Int {
        if (cachedModel == null) loadFacts(ctx)
        val model = cachedModel ?: ""
        return if (model.contains("Quest 2", true) || model.contains("Quest Pro", true)) 6 else 5
    }

    /** A reason not to max things out right now (low battery or too hot), or null if it's fine. */
    fun maxOutBlocker(ctx: Context): String? {
        val out = shell(ctx, "dumpsys battery | grep -E 'level|temperature'")
        val level = Regex("""level:\s*(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        val tenths = Regex("""temperature:\s*(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull()
        if (level != null && level < 15) return "The battery is at $level%. Plug the headset in first."
        if (tenths != null && tenths >= 460) return "The battery is at ${tenths / 10} degrees C. Let it cool down first."
        return null
    }

    // ---------- keep-alive ----------

    /**
     * Checks the connection every 8 seconds and reconnects by itself if it really dropped.
     * It stays out of the way while a command is running, and one slow answer is not
     * treated as a drop.
     */
    fun startWatchdog(ctx: Context) {
        if (watchdogStarted) return
        watchdogStarted = true
        val app = ctx.applicationContext
        Thread {
            var misses = 0
            var failures = 0
            while (true) {
                try { Thread.sleep(if (failures >= 6) 30_000L else 8_000L) } catch (_: InterruptedException) {}
                try {
                    if (!wantConnected || running.get() > 0) continue
                    val m = AdbManager.get(app)
                    if (m.isConnected && ping(m, 8)) {
                        misses = 0
                        failures = 0
                        continue
                    }
                    misses++
                    if (m.isConnected && misses < 2) continue
                    misses = 0
                    reconnecting = true
                    val message = try { autoConnect(app) } finally { reconnecting = false }
                    if (message.startsWith("Connected")) {
                        failures = 0
                        onMessage?.invoke("Reconnected. $message")
                    } else {
                        failures++
                        onMessage?.invoke(message)
                    }
                } catch (_: Throwable) {
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }
}
