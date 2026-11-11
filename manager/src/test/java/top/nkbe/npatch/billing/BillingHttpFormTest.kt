package top.nkbe.npatch.billing

import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.microg.vending.billing.core.HttpClient
import org.microg.vending.billing.proto.GoogleApiResponse

class BillingHttpFormTest {
    @Test(timeout = 15_000)
    fun formPostSendsValuesAndEscapesReservedCharacters() {
        val form = linkedMapOf(
            "pt" to "dummy token+with&reserved=value/購入",
            "ot" to "1",
            "shpn" to "example.test.client",
            "iabx" to "dummy+encoded/value=",
        )
        val received = CompletableFuture<Pair<String, String>>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val responder = thread(name = "billing-form-test", isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val input = BufferedInputStream(socket.getInputStream())
                        val headers = readHeaders(input)
                        val length = headers.lineSequence()
                            .first { it.startsWith("Content-Length:", ignoreCase = true) }
                            .substringAfter(':').trim().toInt()
                        val body = input.readNBytes(length).toString(UTF_8)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(UTF_8))
                            flush()
                        }
                        received.complete(headers to body)
                    }
                } catch (failure: Throwable) {
                    received.completeExceptionally(failure)
                }
            }
            try {
                runBlocking {
                    HttpClient().post(
                        url = "http://127.0.0.1:${server.localPort}/consume",
                        form = form,
                        adapter = GoogleApiResponse.ADAPTER,
                    )
                }
                val (headers, body) = received.get(5, TimeUnit.SECONDS)
                assertTrue(headers.contains("application/x-www-form-urlencoded", ignoreCase = true))
                val decoded = body.split('&').associate { entry ->
                    val pair = entry.split('=', limit = 2)
                    URLDecoder.decode(pair[0], UTF_8) to URLDecoder.decode(pair[1], UTF_8)
                }
                assertEquals("Billing form must send field values, including the purchase token", form, decoded)
            } finally {
                server.close()
                responder.join(5_000)
            }
        }
    }

    private fun readHeaders(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8_192) {
            val next = input.read()
            check(next >= 0) { "HTTP request ended before its headers" }
            bytes.add(next.toByte())
            if (bytes.size >= 4 && bytes.takeLast(4) == listOf(13, 10, 13, 10).map(Int::toByte)) {
                return bytes.toByteArray().toString(UTF_8)
            }
        }
        error("HTTP request headers exceeded test limit")
    }
}
