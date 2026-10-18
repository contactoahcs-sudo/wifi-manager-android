package com.wifimanager.app

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
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
                    blocked = blocked,
                    source = "Xiaomi",
                    canBlock = true
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

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** Lee la configuración actual de WiFi (todas las bandas). */
    private fun wifiDetail(): org.json.JSONArray {
        ensureToken()
        var json = getJson("/cgi-bin/luci/;stok=$token/api/xqnetwork/wifi_detail_all")
        if (json.optInt("code", -1) != 0) {
            token = null; ensureToken()
            json = getJson("/cgi-bin/luci/;stok=$token/api/xqnetwork/wifi_detail_all")
        }
        return json.optJSONArray("info") ?: org.json.JSONArray()
    }

    /**
     * Cambia la contraseña del WiFi en todas las bandas (2.4 y 5 GHz),
     * conservando el nombre de red (SSID), el cifrado y el canal actuales.
     * Reinicia el WiFi: TODOS los equipos se desconectan y deben reconectarse
     * con la nueva contraseña. Devuelve cuántas bandas se cambiaron.
     */
    fun setWifiPassword(newPassword: String): Int {
        val info = wifiDetail()
        var changed = 0
        for (i in 0 until info.length()) {
            val b = info.getJSONObject(i)
            val ssid = b.optString("ssid", "")
            if (ssid.isEmpty()) continue
            val idx = b.optInt("wifiIndex", i + 1)
            val encType = b.optString("encryption", "psk2").ifEmpty { "psk2" }
            val ch = b.optString("channel", "0")
            val bw = b.optString("bandwidth", "0")
            val hidden = b.optString("hidden", "0")
            val on = b.optString("on", "1")
            val params = "wifiIndex=$idx&on=$on&ssid=${enc(ssid)}" +
                "&encryption=$encType&password=${enc(newPassword)}" +
                "&channel=$ch&bandwidth=$bw&hidden=$hidden"
            var r = getJson("/cgi-bin/luci/;stok=$token/api/xqnetwork/set_wifi?$params")
            if (r.optInt("code", -1) != 0) {
                token = null; ensureToken()
                r = getJson("/cgi-bin/luci/;stok=$token/api/xqnetwork/set_wifi?$params")
            }
            if (r.optInt("code", -1) == 0) changed++
        }
        if (changed == 0) throw RuntimeException("El router no aceptó el cambio de contraseña.")
        return changed
    }

    private fun api(path: String): JSONObject {
        ensureToken()
        var j = getJson("/cgi-bin/luci/;stok=$token/api/$path")
        if (j.optInt("code", -1) != 0 && j.optInt("code", -1) == 401) {
            token = null; ensureToken()
            j = getJson("/cgi-bin/luci/;stok=$token/api/$path")
        }
        return j
    }

    /** MACs en la lista negra del router (bloqueados aunque ya no aparezcan conectados). */
    fun blockedMacs(): Set<String> {
        val out = HashSet<String>()
        for (p in listOf("xqnetwork/macfilter_info", "xqnetwork/wifi_macfilter_info")) {
            try {
                val j = api(p)
                if (j.optInt("code", -1) != 0) continue
                val arr = j.optJSONArray("list") ?: j.optJSONArray("macfilter") ?: continue
                for (i in 0 until arr.length()) {
                    val m = arr.optJSONObject(i)?.optString("mac", "")?.uppercase() ?: ""
                    if (m.length == 17) out.add(m)
                }
                if (out.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        return out
    }

    /** Info de la red de invitados (wifiIndex 3) si el router la expone. */
    fun guestInfo(): JSONObject? {
        val info = wifiDetail()
        for (i in 0 until info.length()) {
            val b = info.getJSONObject(i)
            if (b.optInt("wifiIndex", -1) == 3) return b
        }
        return null
    }

    /** Crea/activa (on=true) o apaga la red de invitados. Devuelve la respuesta cruda. */
    fun setGuest(on: Boolean, ssid: String, password: String): JSONObject {
        val cur = try { guestInfo() } catch (_: Exception) { null }
        val enc = if (password.isEmpty()) "none" else (cur?.optString("encryption", "psk2")?.ifEmpty { "psk2" } ?: "psk2")
        val params = "wifiIndex=3&on=${if (on) 1 else 0}&ssid=${enc(ssid)}&encryption=$enc&password=${enc(password)}"
        return api("xqnetwork/set_wifi_without_restart?$params")
    }

    fun reboot(): JSONObject = api("xqsystem/reboot")

    /** Estado WAN (IP pública, etc.) como texto legible. */
    fun wanInfoText(): String {
        val j = api("xqnetwork/wan_info")
        val info = j.optJSONObject("info") ?: return "Sin datos de WAN"
        val ipv4 = info.optJSONArray("ipv4")?.optJSONObject(0)
        val sb = StringBuilder()
        sb.append("Enlace: ").append(info.optString("link", "?")).append("\n")
        sb.append("Tipo: ").append(info.optJSONObject("details")?.optString("wanType", info.optString("wanType", "?")) ?: "?").append("\n")
        if (ipv4 != null) sb.append("IP WAN: ").append(ipv4.optString("ip", "?")).append("\n")
        sb.append("Uptime: ").append(info.optString("uptime", "?"))
        return sb.toString()
    }

    /** Prueba conexión + contraseña sin bloquear nada. */
    fun test(): Boolean {
        login()
        return token != null
    }
}
