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
    private val pool = Executors.newSingleThreadExecutor()
    private val workers = Executors.newCachedThreadPool()
    private val connectLock = Any()

    /** Set once a connection has worked; the keep-alive then tries to keep it. */
    @Volatile var wantConnected = false
    /** Where the keep-alive reports what it did. */
    @Volatile var onMessage: ((String) -> Unit)? = null
    @Volatile private var watchdogStarted = false

    fun run(block: () -> String, onResult: (String) -> Unit) {
        pool.execute {
            val text = try { block() } catch (t: Throwable) { "Error: ${describe(t)}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(text) }
        }
    }

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
    private fun runShell(m: AdbManager, command: String, timeoutSec: Long = 20): String {
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

    /** True only if the headset really answers (not just "looks connected"). */
    fun isAlive(ctx: Context): Boolean {
        val m = AdbManager.get(ctx)
        if (!m.isConnected) return false
        return try { runShell(m, "echo ok", 4).trim() == "ok" } catch (_: Throwable) { false }
    }

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
        if (isAlive(ctx)) {
            wantConnected = true
            return@synchronized "Connected (already)."
        }
        dropConnection(m)
        val notes = mutableListOf<String>()

        // 1) the ADB library's own search
        try { m.autoConnect(ctx, 10_000L) } catch (t: Throwable) { notes.add(describe(t)) }
        if (m.isConnected) {
            wantConnected = true
            return@synchronized "Connected (found automatically)."
        }

        // 2) our own search for this headset's Wireless Debugging service
        val found = discoverTls(ctx, 6_000L)
        if (found != null) {
            try {
                if (m.connect(found.first, found.second)) {
                    wantConnected = true
                    return@synchronized "Connected to ${found.first}:${found.second}."
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
                    if (m.connect(h, port)) {
                        wantConnected = true
                        return@synchronized "Connected to $h:$port."
                    }
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
        if (!m.isConnected) return "Not connected."
        return try {
            runShell(m, command)
        } catch (t: Throwable) {
            if (!retry) return "Error: ${describe(t)}"
            val again = autoConnect(ctx)
            if (!again.startsWith("Connected")) return "Connection lost. $again"
            try { runShell(m, command) } catch (t2: Throwable) { "Error: ${describe(t2)}" }
        }
    }

    /** The highest refresh rate this headset says it supports, or null if it can't be read. */
    fun maxRefreshRate(ctx: Context): Int? {
        val out = shell(ctx, "dumpsys display | grep -o 'fps=[0-9.]*'")
        return Regex("""fps=([0-9]+(?:\.[0-9]+)?)""").findAll(out)
            .mapNotNull { it.groupValues[1].toDoubleOrNull() }
            .map { Math.round(it).toInt() }
            .filter { it in 30..240 }
            .toList()
            .maxOrNull()
    }

    /** Checks the connection every 10 seconds and reconnects by itself if it dropped. */
    fun startWatchdog(ctx: Context) {
        if (watchdogStarted) return
        watchdogStarted = true
        val app = ctx.applicationContext
        Thread {
            var failures = 0
            while (true) {
                try { Thread.sleep(if (failures >= 6) 30_000L else 10_000L) } catch (_: InterruptedException) {}
                try {
                    if (wantConnected) {
                        val message = autoConnect(app)
                        if (message == "Connected (already).") {
                            failures = 0
                        } else {
                            if (message.startsWith("Connected")) {
                                failures = 0
                                onMessage?.invoke("Reconnected. $message")
                            } else {
                                failures++
                                onMessage?.invoke(message)
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }
}
