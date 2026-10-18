package com.wifimanager.app

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Cliente para routers Xiaomi / Mi Router (ej. Mi Router 4C).
 *
 * Hace lo mismo que harías tú en miwifi.com: inicia sesión con tu contraseña
 * de administrador y usa la API interna del router para listar equipos y
 * aplicar el filtrado MAC (bloquear / permitir). Todo por HTTP a tu router en
 * tu red local. No evita ninguna seguridad.
 */
class MiWifiClient(ip: String, private val password: String) {

    private val base = "http://" + ip.trim()
        .removePrefix("http://").removePrefix("https://").trimEnd('/')

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Volatile private var token: String? = null

    companion object {
        // Clave pública fija usada por el firmware MiWiFi para cifrar la contraseña.
        private const val KEY = "a2ffa5c9be07488bbb04a3a47d3c5f6a"
    }

    private fun sha(input: String, sha256: Boolean): String {
        val md = MessageDigest.getInstance(if (sha256) "SHA-256" else "SHA-1")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun getJson(path: String): JSONObject {
        val req = Request.Builder().url(base + path).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            return JSONObject(body)
        }
    }

    private fun hashPassword(nonce: String, sha256: Boolean): String {
        val pwdKey = sha(password + KEY, sha256)
        return sha(nonce + pwdKey, sha256)
    }

    /** Inicia sesión y guarda el token. Lanza excepción con mensaje claro si falla. */
    fun login() {
        val info = getJson("/cgi-bin/luci/api/xqsystem/init_info")
        val mac = info.optString("id", info.optString("mac", ""))
        val newEncrypt = info.optInt("newEncryptMode", 0) != 0
        val nonce = "0_${mac}_${System.currentTimeMillis() / 1000}_${Random.nextInt(1000, 9999)}"

        for (sha256 in listOf(newEncrypt, !newEncrypt)) {
            val form = FormBody.Builder()
                .add("username", "admin")
                .add("password", hashPassword(nonce, sha256))
                .add("logintype", "web")
                .add("nonce", nonce)
                .build()
            val req = Request.Builder()
                .url("$base/cgi-bin/luci/api/xqsystem/login").post(form).build()
            http.newCall(req).execute().use { resp ->
                val json = JSONObject(resp.body?.string() ?: "{}")
                val t = json.optString("token", "")
                if (t.isNotEmpty()) { token = t; return }
                if (json.optInt("code", -1) == 401) {
                    throw RuntimeException("Contraseña del router incorrecta.")
                }
            }
        }
        throw RuntimeException("No se pudo iniciar sesión en el router. Revisa la IP y la contraseña del panel.")
    }

    private fun ensureToken() {
        if (token == null) login()
    }

    /** Devuelve la lista de equipos conectados al router. */
    fun deviceList(): List<Device> {
        ensureToken()
        val json = getJson("/cgi-bin/luci/;stok=$token/api/misystem/devicelist")
        if (json.optInt("code", -1) != 0) {
            // token caducado -> reintenta una vez
            token = null; ensureToken()
        }
        val json2 = getJson("/cgi-bin/luci/;stok=$token/api/misystem/devicelist")
        val arr = json2.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Device>()
        for (i in 0 until arr.length()) {
            val d = arr.getJSONObject(i)
            val mac = d.optString("mac", "").uppercase()
            val ip = d.optJSONArray("ip")?.optJSONObject(0)?.optString("ip", "") ?: ""
            val name = d.optString("name", "")
            val online = d.optInt("online", 1) == 1
            val blocked = d.optInt("isap", 0) == 0 && d.optInt("ban", 0) == 1
            out.add(
                Device(
                    mac = mac,
                    ip = ip,
                    name = name,
                    vendor = Vendors.lookup(mac),
                    online = online,
                    blocked = blocked
                )
            )
        }
        return out
    }

    /** Bloquea (true) o permite (false) un equipo por su MAC. */
    fun setBlock(mac: String, blocked: Boolean) {
        ensureToken()
        val wan = if (blocked) 1 else 0
        val m = mac.trim().uppercase().replace("-", ":")
        val path = "/cgi-bin/luci/;stok=$token/api/xqnetwork/set_mac_filter?mac=$m&wan=$wan"
        var json = getJson(path)
        if (json.optInt("code", -1) != 0) {
            token = null; ensureToken()
            json = getJson("/cgi-bin/luci/;stok=$token/api/xqnetwork/set_mac_filter?mac=$m&wan=$wan")
        }
        if (json.optInt("code", -1) != 0) {
            throw RuntimeException("El router no aplicó el cambio: $json")
        }
    }

    /** Prueba conexión + contraseña sin bloquear nada. */
    fun test(): Boolean {
        login()
        return token != null
    }
}
