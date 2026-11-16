// SPDX-License-Identifier: GPL-3.0-or-later

package com.synaelle.noeud

import com.synaelle.noeud.lib.Answer
import com.synaelle.noeud.lib.Offline
import com.synaelle.noeud.lib.Server
import com.synaelle.noeud.lib.Transport
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONException
import org.json.JSONObject

private const val TIMEOUT_MS = 10_000

/** The platform's HTTP client, so the app carries none of its own. */
class HttpTransport : Transport {
    override fun request(server: Server, method: String, path: String, body: JSONObject?): Answer {
        try {
            val connection = URL(server.url + path).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                // Android's implementation accepts PATCH, unlike the JDK's
                connection.requestMethod = method
                connection.setRequestProperty("Authorization", "Bearer ${server.token}")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
                }
                val status = connection.responseCode
                // a proxy in front of a server that is down
                if (status == 502 || status == 503 || status == 504) throw Offline()
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = stream?.use { it.readBytes().decodeToString() } ?: ""
                return Answer(status, parse(text))
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            throw Offline()
        }
    }

    private fun parse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (_: JSONException) {
        null
    }
}
