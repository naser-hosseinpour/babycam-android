package com.example.ipcam

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Minimal mDNS responder: answers A queries for "<host>.local" with this device's IPv4,
 * so you can open http://<host>.local:8080/ instead of typing the IP address.
 */
class MdnsResponder(private val context: Context, private val host: String) {
    private val group = InetAddress.getByName("224.0.0.251")
    private val port = 5353
    @Volatile private var running = false
    private var socket: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private val nameBytes = encodeName("$host.local")

    fun start() {
        running = true
        thread(name = "mdns") {
            try {
                val wifi = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as WifiManager
                lock = wifi.createMulticastLock("ipcam-mdns").apply {
                    setReferenceCounted(false); acquire()
                }
                val s = MulticastSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                s.joinGroup(group)
                socket = s
                // Announce a few times so caches populate quickly.
                repeat(3) {
                    try { sendAnswer(s) } catch (_: Exception) {}
                    try { Thread.sleep(250) } catch (_: Exception) {}
                }
                val buf = ByteArray(2048)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { s.receive(pkt) } catch (e: Exception) { if (!running) break else continue }
                    if (isQueryForUs(pkt.data, pkt.length)) {
                        try { sendAnswer(s) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        running = false
        try { socket?.leaveGroup(group) } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { lock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
    }

    private fun currentIpv4(): ByteArray? {
        try {
            for (intf in NetworkInterface.getNetworkInterfaces()) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val h = addr.hostAddress ?: continue
                        if (h.startsWith("127.")) continue
                        return addr.address
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun isQueryForUs(data: ByteArray, len: Int): Boolean {
        if (len < 12) return false
        if ((data[2].toInt() and 0x80) != 0) return false // skip responses
        return indexOf(data, len, nameBytes) >= 0
    }

    private fun sendAnswer(s: MulticastSocket) {
        val ip = currentIpv4() ?: return
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out.add(((v shr 8) and 0xFF).toByte()); out.add((v and 0xFF).toByte()) }
        u16(0)          // id
        u16(0x8400)     // flags: response, authoritative
        u16(0)          // qdcount
        u16(1)          // ancount
        u16(0)          // nscount
        u16(0)          // arcount
        for (b in nameBytes) out.add(b)
        u16(0x0001)     // type A
        u16(0x8001)     // class IN + cache-flush
        out.add(0); out.add(0); out.add(0); out.add(120.toByte()) // TTL 120s
        u16(4)          // rdlength
        for (b in ip) out.add(b)
        val bytes = ByteArray(out.size) { out[it] }
        s.send(DatagramPacket(bytes, bytes.size, group, port))
    }

    companion object {
        private fun encodeName(name: String): ByteArray {
            val out = ArrayList<Byte>()
            for (label in name.split('.')) {
                val lb = label.toByteArray(Charsets.US_ASCII)
                out.add(lb.size.toByte())
                for (b in lb) out.add(b)
            }
            out.add(0)
            return ByteArray(out.size) { out[it] }
        }

        private fun indexOf(data: ByteArray, len: Int, pattern: ByteArray): Int {
            if (pattern.isEmpty() || len < pattern.size) return -1
            var i = 0
            while (i <= len - pattern.size) {
                var j = 0
                while (j < pattern.size && data[i + j] == pattern[j]) j++
                if (j == pattern.size) return i
                i++
            }
            return -1
        }
    }
}
