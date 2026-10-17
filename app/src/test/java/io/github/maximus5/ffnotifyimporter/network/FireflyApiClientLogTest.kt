package io.github.maximus5.ffnotifyimporter.network

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** What the client puts into the diagnostic log: how a call ended, never what was sent or received. */
class FireflyApiClientLogTest {

    private lateinit var server: ServerSocket
    private val lines = mutableListOf<String>()

    /** Answered to every request, set per test. */
    private var status = "200 OK"
    private var body = ""

    @Before
    fun start() {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        val reader = socket.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) { /* skip the request head */ }
                        val bytes = body.toByteArray()
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                } catch (e: java.io.IOException) {
                    // closed by the test
                }
            }
        }
    }

    @After
    fun stop() = server.close()

    private fun client(port: Int = server.localPort) = FireflyApiClient(
        "http://127.0.0.1:$port", "tok-SECRET-123", allowCleartext = true,
    ) { lines += it }

    @Test
    fun `an HTTP error logs the status only - not the body, the token or the query`() = runBlocking {
        status = "422 Unprocessable Entity"
        body = """{"message":"BODY-SECRET Shop1234 123.45"}"""
        val result = client().searchTransactionsByQuery("description_is:Shop1234 amount:123.45")

        assertTrue(result is FireflyApiClient.Result.Failure)
        assertEquals(listOf("api GET /api/v1/search/transactions: HTTP 422"), lines)
        val logged = lines.joinToString("\n")
        listOf("BODY-SECRET", "Shop1234", "123.45", "tok-SECRET-123", "127.0.0.1", "Bearer").forEach {
            assertFalse(it, logged.contains(it))
        }
    }

    @Test
    fun `a list page that is not a Firefly list is an error, not an empty page`() = runBlocking {
        body = "<html>BODY-SECRET proxy page</html>"
        val result = client().listCategories()

        assertTrue(result is FireflyApiClient.Result.Failure)
        assertEquals(listOf("api GET /api/v1/categories: unreadable response (page 1)"), lines)
    }

    @Test
    fun `a good list logs its size`() = runBlocking {
        body = """{"data":[{"id":"1","attributes":{"name":"Food"}}],"meta":{"pagination":{"total_pages":1}}}"""
        val result = client().listCategories() as FireflyApiClient.Result.Success

        assertEquals(listOf("Food"), result.value.map { it.name })
        assertEquals(listOf("api GET /api/v1/categories: ok, 1 page(s), 1 items"), lines)
    }

    @Test
    fun `every failed attempt is logged, with the exception type only`() = runBlocking {
        val port = server.localPort
        server.close()
        val result = client(port).testConnection()

        assertTrue(result is FireflyApiClient.Result.Failure)
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("api GET /api/v1/about: attempt 1 failed: "))
        assertTrue(lines[1].startsWith("api GET /api/v1/about: attempt 2 failed: "))
        assertTrue(lines[2].startsWith("api GET /api/v1/about: attempt 3 failed: "))
        assertTrue(lines[3].startsWith("api GET /api/v1/about: failed: "))
        assertFalse(lines.joinToString("\n").contains("127.0.0.1"))
    }
}
