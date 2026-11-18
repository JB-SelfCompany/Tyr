package com.jbselfcompany.tyr.chat.network

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #43: imap-rs cuts BODY[TEXT] at CRLFCRLF, so a bare-LF message arrives blank. */
@RunWith(RobolectricTestRunner::class)
class SmtpSenderTest {
    private val bareLf = Regex("(?<!\r)\n")

    @Test
    fun dataUsesCrlfAndSeparatesHeadersFromBody() {
        val data = captureData { it.send("a@yggmail", "pw", "b@yggmail", "> q\n\nhello\n.dot") }
        assertTrue(data, data.contains("\r\n\r\n> q\r\n\r\nhello\r\n..dot\r\n"))
        assertTrue(data, data.endsWith("\r\n.\r\n"))
        assertFalse(data, bareLf.containsMatchIn(data))
    }

    @Test
    fun headerValuesCannotInjectHeaders() {
        val data = captureData {
            it.send("a@yggmail", "pw", "b@yggmail", "hi", senderNickname = "Bob\r\nX-Evil: 1")
        }
        assertTrue(data, data.contains("\r\nX-Tyr-Nickname: BobX-Evil: 1\r\n"))
        assertFalse(data, data.contains("\nX-Evil"))
    }

    /** Runs [send] against a scripted one-shot SMTP server; returns the raw DATA payload. */
    private fun captureData(send: (SmtpSender) -> SmtpSender.Result): String {
        ServerSocket(0).use { server ->
            val pool = Executors.newSingleThreadExecutor()
            val data = pool.submit<String> {
                server.accept().use { s ->
                    val inp = s.getInputStream()
                    val out = s.getOutputStream()
                    fun reply(r: String) = out.write("$r\r\n".toByteArray()).also { out.flush() }
                    reply("220 test")
                    // EHLO, AUTH LOGIN, user, pass, MAIL, RCPT, DATA
                    for (r in listOf(
                        "250 ok",
                        "334 x",
                        "334 x",
                        "235 ok",
                        "250 ok",
                        "250 ok",
                        "354 go"
                    )) {
                        readUntil(inp) { it.endsWith("\n") }
                        reply(r)
                    }
                    val payload = readUntil(inp) { it.endsWith("\n.\r\n") || it.endsWith("\n.\n") }
                    reply("250 ok")
                    readUntil(inp) { it.endsWith("\n") } // QUIT
                    reply("221 bye")
                    payload
                }
            }
            val result = send(SmtpSender("127.0.0.1", server.localPort))
            assertEquals(SmtpSender.Result.Success::class, result::class)
            return data.get(10, TimeUnit.SECONDS).also { pool.shutdown() }
        }
    }

    // Byte-by-byte so line endings reach the test exactly as sent.
    private fun readUntil(inp: InputStream, done: (String) -> Boolean): String {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            check(b >= 0) { "client closed early: ${buf.toString(Charsets.ISO_8859_1)}" }
            buf.write(b)
            val s = buf.toString(Charsets.ISO_8859_1)
            if (done(s)) return s
        }
    }
}
