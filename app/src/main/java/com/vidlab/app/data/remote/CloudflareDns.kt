package com.vidlab.app.data.remote

import okhttp3.Dns
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object CloudflareDns : Dns {
    @Volatile
    var enabled: Boolean = false

    private data class CacheEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    override fun lookup(hostname: String): List<InetAddress> {
        if (!enabled || hostname == "127.0.0.1" || hostname == "localhost" || hostname == "1.1.1.1" || hostname == "1.0.0.1") {
            return Dns.SYSTEM.lookup(hostname)
        }
        val now = System.currentTimeMillis()
        cache[hostname]?.let { entry ->
            if (entry.expiresAtMs > now && entry.addresses.isNotEmpty()) {
                return entry.addresses
            }
        }
        val dohAddresses = queryCloudflareDoh(hostname)
        if (dohAddresses.isNotEmpty()) {
            cache[hostname] = CacheEntry(dohAddresses, now + 5 * 60_000L)
            return dohAddresses
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun queryCloudflareDoh(hostname: String): List<InetAddress> {
        val encoded = URLEncoder.encode(hostname, "UTF-8")
        for (endpoint in listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query")) {
            val result = runCatching {
                val conn = (URL("$endpoint?name=$encoded&type=A").openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/dns-json")
                    connectTimeout = 4_000
                    readTimeout = 4_000
                }
                try {
                    if (conn.responseCode in 200..299) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val answers = JSONObject(body).optJSONArray("Answer") ?: return@runCatching emptyList()
                        val list = mutableListOf<InetAddress>()
                        for (i in 0 until answers.length()) {
                            val ans = answers.optJSONObject(i) ?: continue
                            if (ans.optInt("type") == 1) {
                                val ip = ans.optString("data").trim()
                                if (ip.isNotEmpty()) {
                                    list.add(InetAddress.getByAddress(hostname, InetAddress.getByName(ip).address))
                                }
                            }
                        }
                        list
                    } else emptyList()
                } finally {
                    conn.disconnect()
                }
            }.getOrDefault(emptyList())
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    fun openConnection(url: URL): HttpURLConnection {
        val conn = url.openConnection() as HttpURLConnection
        if (!enabled) return conn
        val host = url.host ?: return conn
        if (host == "127.0.0.1" || host == "localhost") return conn
        val resolved = runCatching { lookup(host) }.getOrDefault(emptyList())
        val targetIp = resolved.firstOrNull() ?: return conn
        if (conn is HttpsURLConnection) {
            val baseFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            conn.sslSocketFactory = object : SSLSocketFactory() {
                override fun getDefaultCipherSuites(): Array<String> = baseFactory.defaultCipherSuites
                override fun getSupportedCipherSuites(): Array<String> = baseFactory.supportedCipherSuites
                override fun createSocket(s: Socket, h: String, port: Int, autoClose: Boolean): Socket =
                    baseFactory.createSocket(s, host, port, autoClose)
                override fun createSocket(h: String, port: Int): Socket {
                    val raw = Socket()
                    raw.connect(InetSocketAddress(targetIp, port), 10_000)
                    return (baseFactory.createSocket(raw, host, port, true) as SSLSocket).apply { startHandshake() }
                }
                override fun createSocket(h: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
                    val raw = Socket()
                    raw.bind(InetSocketAddress(localHost, localPort))
                    raw.connect(InetSocketAddress(targetIp, port), 10_000)
                    return (baseFactory.createSocket(raw, host, port, true) as SSLSocket).apply { startHandshake() }
                }
                override fun createSocket(address: InetAddress, port: Int): Socket = createSocket(host, port)
                override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                    createSocket(host, port, localAddress, localPort)
            }
        }
        return conn
    }
}
