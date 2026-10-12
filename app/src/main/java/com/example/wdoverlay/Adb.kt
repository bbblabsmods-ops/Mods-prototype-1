package com.example.wdoverlay

import android.content.Context
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
import java.util.concurrent.Executors

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

    fun run(block: () -> String, onResult: (String) -> Unit) {
        pool.execute {
            val text = try { block() } catch (t: Throwable) { "Error: ${t.message ?: t.javaClass.simpleName}" }
            android.os.Handler(android.os.Looper.getMainLooper()).post { onResult(text) }
        }
    }

    fun pair(ctx: Context, host: String, port: Int, code: String): String {
        val ok = AdbManager.get(ctx).pair(host, port, code)
        return if (ok) "Paired. Now enter the debug port and press Connect." else "Pairing failed."
    }

    fun connect(ctx: Context, host: String, port: Int): String {
        val ok = AdbManager.get(ctx).connect(host, port)
        return if (ok) "Connected." else "Connect failed."
    }

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

    /** The port Wireless Debugging is listening on, if the system will tell us. */
    private fun tlsPort(): Int? = try {
        val p = Runtime.getRuntime().exec(arrayOf("getprop", "service.adb.tls.port"))
        p.inputStream.bufferedReader().readText().trim().toIntOrNull()?.takeIf { it > 0 }
    } catch (_: Throwable) {
        null
    }

    /**
     * Finds Wireless Debugging by itself (no IP or port to type) and connects.
     * You still have to pair once with the pairing code.
     * force = true drops the current connection first (use it after Wi-Fi has been toggled).
     */
    fun autoConnect(ctx: Context, force: Boolean = false): String {
        val m = AdbManager.get(ctx)
        if (force) {
            try { (m as? java.io.Closeable)?.close() } catch (_: Throwable) {}
        }
        if (m.isConnected) return "Connected (already)."
        try { m.autoConnect(ctx, 10_000L) } catch (_: Throwable) {}
        if (m.isConnected) return "Connected (found automatically)."
        val port = tlsPort()
        if (port != null) {
            for (host in listOfNotNull(wifiIp(), "127.0.0.1")) {
                try { if (m.connect(host, port)) return "Connected to $host:$port." } catch (_: Throwable) {}
            }
        }
        return "Couldn't connect automatically. Turn on Wireless Debugging (and pair once), or type the IP and port by hand."
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

    fun isConnected(ctx: Context): Boolean = AdbManager.get(ctx).isConnected

    fun shell(ctx: Context, command: String): String {
        val m = AdbManager.get(ctx)
        if (!m.isConnected) return "Not connected."
        val stream = m.openStream("shell:$command")
        return stream.openInputStream().bufferedReader().use { it.readText() }.trim().ifEmpty { "(no output)" }
    }
}
