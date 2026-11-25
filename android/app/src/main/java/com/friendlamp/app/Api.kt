package com.friendlamp.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val status: Int, message: String) : Exception(message)

/** Talks to the FriendLamp server. Call from a background thread. */
object Api {
    /**
     * A request made as this device: adds the code and the device id of the
     * given profile (the one in use if none is named).
     */
    fun call(
        context: Context,
        path: String,
        extra: JSONObject? = null,
        timeoutMs: Int = 8000,
        profile: String? = null
    ): JSONObject {
        val store = Store(context, profile)
        val body = JSONObject().put("code", store.code ?: "").put("id", store.id)
        if (extra != null) {
            for (key in extra.keys()) body.put(key, extra.get(key))
        }
        return request("POST", path, body, timeoutMs)
    }

    fun request(method: String, path: String, body: JSONObject?, timeoutMs: Int = 8000): JSONObject {
        val connection = URL(Config.SERVER + "/api/" + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            // A fresh connection every time: one kept from an earlier call can be dead
            // by the time the widget is tapped again.
            connection.setRequestProperty("Connection", "close")
            connection.useCaches = false
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try {
                JSONObject(text)
            } catch (e: Exception) {
                JSONObject()
            }
            if (status !in 200..299) {
                throw ApiException(status, json.optString("error", "Something went wrong ($status)"))
            }
            return json
        } finally {
            connection.disconnect()
        }
    }
}
