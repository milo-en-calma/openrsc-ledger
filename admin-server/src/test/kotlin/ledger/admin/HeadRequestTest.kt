package ledger.admin

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import ledger.core.game.SqliteGameData
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A HEAD request gets the same status and headers as a GET to the same address, and no body,
 * so a tool checking whether a page is up with HEAD sees what a browser would. Runs on the
 * Netty engine the ledger serves with, on a free port, sending each request as raw text.
 */
class HeadRequestTest {
    @Test
    fun `every address that answers GET answers HEAD the same way, without a body`() = onNetty { port ->
        val addresses = listOf(
            "/", "/players/1", "/leaderboards/woodcut", "/index.html",
            "/api/health", "/api/players", "/api/players/1", "/api/economy", OPENAPI_PATH,
        )
        for (address in addresses) {
            val get = send(port, "GET", address)
            val head = send(port, "HEAD", address)
            assertEquals(200, get.status, "GET $address")
            assertEquals(get.status, head.status, "HEAD $address")
            assertEquals(get.headers["content-type"], head.headers["content-type"], "HEAD $address")
            get.headers["content-length"]?.let { assertEquals(it, head.headers["content-length"], "HEAD $address") }
            assertTrue(get.body.isNotEmpty(), "GET $address answered no body")
            assertEquals("", head.body, "HEAD $address answered a body")
        }
    }

    @Test
    fun `a HEAD request for a mistaken API address gets the same error status as GET`() = onNetty { port ->
        for ((address, status) in listOf("/api/nope" to 404, "/api/players/abc" to 400, "/api/players/999" to 404, "/api/economy?x=1" to 400, "/api/players?search=a&search=b" to 400, "/api/history/gains" to 400)) {
            assertEquals(status, send(port, "GET", address).status, "GET $address")
            val head = send(port, "HEAD", address)
            assertEquals(status, head.status, "HEAD $address")
            assertEquals("", head.body, "HEAD $address answered a body")
        }
    }

    private data class Answer(val status: Int, val headers: Map<String, String>, val body: String)

    /** Sends [method] [target] as raw text, and reads the whole answer until the server closes. */
    private fun send(port: Int, method: String, target: String): Answer = Socket(HOST, port).use { socket ->
        socket.getOutputStream().write("$method $target HTTP/1.1\r\nHost: $HOST\r\nConnection: close\r\n\r\n".toByteArray())
        val text = socket.getInputStream().readAllBytes().toString(Charsets.UTF_8)
        val head = text.substringBefore("\r\n\r\n").lines()
        val headers = head.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        Answer(status = head.first().substringAfter(' ').take(3).toInt(), headers = headers, body = text.substringAfter("\r\n\r\n", ""))
    }

    /** Runs [block] against the admin module on Netty, on a free port, over the test database's invented players. */
    private fun onNetty(block: (port: Int) -> Unit) {
        val server = embeddedServer(Netty, host = HOST, port = 0) {
            adminModule(lastCopy = { COPIED }, gameData = SqliteGameData(testDatabase()))
        }.start(wait = false)
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop(100, 1_000)
        }
    }
}
