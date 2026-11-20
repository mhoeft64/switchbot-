package de.switchbot.lock

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Minimaler Client für die SwitchBot API v1.1. Muss in einem Hintergrund-Thread laufen. */
class SwitchBot(private val token: String, private val secret: String) {

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val t = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        val sign = Base64.encodeToString(mac.doFinal((token + t + nonce).toByteArray()), Base64.NO_WRAP)

        val c = URL("https://api.switch-bot.com/v1.1$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("Authorization", token)
        c.setRequestProperty("sign", sign)
        c.setRequestProperty("t", t)
        c.setRequestProperty("nonce", nonce)
        c.setRequestProperty("Content-Type", "application/json; charset=utf8")
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        val json = JSONObject(text)
        if (c.responseCode !in 200..299 || json.optInt("statusCode", 100) != 100) {
            throw RuntimeException(json.optString("message", "HTTP ${c.responseCode}"))
        }
        return json
    }

    /** Liefert (deviceId, Name) des ersten Smart Locks. */
    fun findLock(): Pair<String, String> {
        val list = call("GET", "/devices").getJSONObject("body").getJSONArray("deviceList")
        for (i in 0 until list.length()) {
            val d = list.getJSONObject(i)
            if (d.getString("deviceType").startsWith("Smart Lock")) {
                return d.getString("deviceId") to d.optString("deviceName", "Türschloss")
            }
        }
        throw RuntimeException("Kein Smart Lock im Account gefunden")
    }

    fun isLocked(id: String): Boolean =
        call("GET", "/devices/$id/status").getJSONObject("body").optString("lockState") == "locked"

    fun command(id: String, cmd: String) {
        call("POST", "/devices/$id/commands", JSONObject()
            .put("command", cmd).put("parameter", "default").put("commandType", "command"))
    }
}
