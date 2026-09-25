package com.sshtunnel.app

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

sealed class ConnState {
    object Disconnected : ConnState()
    object Connecting : ConnState()
    data class Connected(val socksPort: Int) : ConnState()
    data class Failed(val reason: String) : ConnState()
}

data class ServerConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String
)

/**
 * Opens an SSH session and runs a local SOCKS5 proxy on 127.0.0.1:<socksPort>.
 * Every SOCKS CONNECT request is forwarded through the SSH session using a
 * "direct-tcpip" channel. This is equivalent to: ssh -D <socksPort> user@host
 */
class SshManager {

    private var session: Session? = null
    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    val isConnected: Boolean
        get() = session?.isConnected == true && running.get()

    suspend fun connect(
        config: ServerConfig,
        socksPort: Int = 1080,
        log: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        stopInternal()

        log("Connecting to ${config.host}:${config.port}")
        val jsch = JSch()
        val s = jsch.getSession(config.username, config.host, config.port)
        s.setPassword(config.password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.setServerAliveInterval(15_000)
        s.setServerAliveCountMax(3)
        s.connect(15_000)
        session = s
        log("SSH session established")

        val ss = ServerSocket(socksPort, 50, InetAddress.getByName("127.0.0.1"))
        server = ss
        running.set(true)
        log("SOCKS5 proxy listening on 127.0.0.1:$socksPort")

        pool.execute {
            while (running.get()) {
                try {
                    val client = ss.accept()
                    pool.execute { handleClient(client, log) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleClient(client: Socket, log: (String) -> Unit) {
        client.use { sock ->
            try {
                sock.soTimeout = 30_000
                val input = sock.getInputStream()
                val output = sock.getOutputStream()

                // --- SOCKS5 greeting ---
                if (input.read() != 5) return
                val nMethods = input.read()
                if (nMethods < 0) return
                input.skipFully(nMethods)
                output.write(byteArrayOf(5, 0)) // no authentication
                output.flush()

                // --- SOCKS5 request ---
                if (input.read() != 5) return
                val cmd = input.read()
                input.read() // reserved
                val atyp = input.read()

                val host: String = when (atyp) {
                    1 -> { // IPv4
                        val b = ByteArray(4); input.readFully(b)
                        InetAddress.getByAddress(b).hostAddress ?: return
                    }
                    3 -> { // domain name
                        val len = input.read()
                        val b = ByteArray(len); input.readFully(b)
                        String(b)
                    }
                    4 -> { // IPv6
                        val b = ByteArray(16); input.readFully(b)
                        InetAddress.getByAddress(b).hostAddress ?: return
                    }
                    else -> { reply(output, 8); return }
                }
                val port = (input.read() shl 8) or input.read()

                if (cmd != 1) { // only CONNECT is supported
                    reply(output, 7)
                    return
                }

                val current = session
                if (current == null || !current.isConnected) {
                    reply(output, 1)
                    return
                }

                val channel = current.openChannel("direct-tcpip") as ChannelDirectTCPIP
                channel.setHost(host)
                channel.setPort(port)
                val fromRemote = channel.inputStream
                val toRemote = channel.outputStream
                channel.connect(10_000)

                // success reply
                reply(output, 0)
                sock.soTimeout = 0

                val t = Thread { pump(fromRemote, output) }
                t.isDaemon = true
                t.start()
                pump(input, toRemote)
                channel.disconnect()
            } catch (e: Exception) {
                log("Client error: ${e.message}")
            }
        }
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    private fun pump(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(16 * 1024)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
        }
    }

    private fun InputStream.readFully(b: ByteArray) {
        var off = 0
        while (off < b.size) {
            val n = read(b, off, b.size - off)
            if (n < 0) throw java.io.EOFException()
            off += n
        }
    }

    private fun InputStream.skipFully(count: Int) {
        val tmp = ByteArray(count)
        readFully(tmp)
    }

    private fun stopInternal() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        server = null
        session = null
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) { stopInternal() }
}
