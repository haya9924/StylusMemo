package com.stylusmemo.app.plugin.aimarkdown

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress

class GeminiClientTest {

    @Test
    fun `sends inline image and parses response`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var captured: Pair<String, String>? = null
        server.createContext("/v1beta/models/mock:generateContent") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val query = exchange.requestURI.query.orEmpty()
            val resp =
                """{"candidates":[{"content":{"parts":[{"text":"手書きの文字起こし"},{"text":"その2"}]}}]}"""
            exchange.sendResponseHeaders(200, resp.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(resp.toByteArray()) }
            captured = body to query
        }
        server.start()
        try {
            val client = GeminiClient(
                apiKey = "secret-key",
                model = "mock",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1beta",
            )
            val result = kotlinx.coroutines.runBlocking {
                client.completeTextAndImage("プロンプト", byteArrayOf(0x89.toByte(), 0x50, 0x4E))
            }
            assertEquals("手書きの文字起こし\nその2", result)

            val (body, query) = captured!!
            assertTrue(body.contains("\"text\":\"プロンプト\""))
            assertTrue(body.contains("image/png"))
            assertTrue(query.contains("key=secret-key"))
        } finally {
            server.stop(0)
        }
    }

    @Test(expected = IOException::class)
    fun `throws on http error`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1beta/models/mock:generateContent") { exchange ->
            val resp = "{\"error\":{\"message\":\"invalid api key\"}}"
            exchange.sendResponseHeaders(400, resp.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(resp.toByteArray()) }
        }
        server.start()
        try {
            val client = GeminiClient(
                apiKey = "bad",
                model = "mock",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1beta",
            )
            kotlinx.coroutines.runBlocking {
                client.completeTextAndImage("p", byteArrayOf(1))
            }
        } finally {
            server.stop(0)
        }
    }
}