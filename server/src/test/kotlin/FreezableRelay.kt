package ch.nokillswit

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A TCP relay to [targetHost]:[targetPort] that goes SILENT — it holds every byte, in both
 * directions — while [frozen]: the shape of a hung database (a network black hole, a frozen VM)
 * rather than a refused connection. It keeps accepting new connections while frozen, so a new
 * connection's startup handshake hangs too. Used by ConnectionPoolTest (the hung-database
 * regressions, `.claude/docs/testing.md`).
 */
class FreezableRelay(private val targetHost: String, private val targetPort: Int) : AutoCloseable {
    private val server = ServerSocket(0)
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Volatile
    var frozen = false
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "freezable-relay-accept") {
            runCatching {
                while (true) {
                    val client = server.accept().also { sockets += it }
                    val upstream = Socket(targetHost, targetPort).also { sockets += it }
                    pump(client, upstream)
                    pump(upstream, client)
                }
            }
        }
    }

    private fun pump(from: Socket, to: Socket) = thread(isDaemon = true, name = "freezable-relay-pump") {
        runCatching {
            val buffer = ByteArray(8192)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    // EOF: pass the half-close on so a backend behind a closed client socket ends.
                    runCatching { to.shutdownOutput() }
                    break
                }
                while (frozen) Thread.sleep(50)
                output.write(buffer, 0, read)
                output.flush()
            }
        }
    }

    /** [url] rewritten to reach its host through this relay. */
    fun relayed(url: String): String = url.replace("$targetHost:$targetPort", "localhost:$port")

    override fun close() {
        frozen = false
        server.close()
        sockets.forEach { runCatching { it.close() } }
    }

    companion object {
        /** A relay in front of the shared Testcontainer's PostgreSQL. */
        fun forTestDatabase(): FreezableRelay {
            val (host, port) = Regex("//([^:/]+):(\\d+)/").find(PostgresTestSupport.r2dbcUrl)!!.destructured
            return FreezableRelay(host, port.toInt())
        }
    }
}
