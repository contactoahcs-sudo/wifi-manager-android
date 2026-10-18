package com.wifimanager.app

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Cliente para el módem principal Huawei EchoLife (HG8145V5 / HG8245H y similares,
 * como el de Totalplay). Inicia sesión en su panel (normalmente 192.168.100.1)
 * y lee la lista de equipos conectados por cable y por WiFi.
 *
 * Flujo del panel: POST /asp/GetRandCount.asp -> token; POST /login.cgi con
 * usuario + contraseña (base64) + token; luego GET GetLanUserDevInfo.asp.
 */
class HuaweiClient(ip: String, private val user: String, private val password: String) {

    private val base = "http://" + ip.trim()
        .removePrefix("http://").removePrefix("https://").trimEnd('/')

    // Guardamos las cookies de sesión a mano (el panel usa una cookie "SID"/"Cookie").
    private val cookies = HashMap<String, String>()
    @Volatile private var loggedIn = false

    /** Últimas respuestas crudas, para el diagnóstico. */
    var lastDebug: String = ""
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun storeCookies(headers: List<String>) {
        for (h in headers) {
            val kv = h.split(";")[0]
            val i = kv.indexOf('=')
            if (i > 0) cookies[kv.substring(0, i).trim()] = kv.substring(i + 1).trim()
        }
    }

    private fun call(req: Request.Builder, path: String): String {
        val r = req.url(base + path)
        if (cookies.isNotEmpty()) r.header("Cookie", cookieHeader())
        http.newCall(r.build()).execute().use { resp ->
            storeCookies(resp.headers("Set-Cookie"))
            return resp.body?.string() ?: ""
        }
    }

    private fun get(path: String) = call(Request.Builder(), path)

    private fun post(path: String, form: FormBody) = call(Request.Builder().post(form), path)

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun b64(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)

    /** Inicia sesión. Prueba el esquema clásico (base64) y el nuevo (sha256). */
    fun login() {
        cookies.clear()
        // Cookie inicial que pide el panel antes de loguear.
        cookies["Cookie"] = "body:Language:english:id=-1"
        get("/")
        val token = post("/asp/GetRandCount.asp", FormBody.Builder().build())
            .trim().removePrefix("﻿").trim()
        val sb = StringBuilder("token=$token\n")

        for (pass in listOf(b64(password), b64(sha256Hex(password)))) {
            val form = FormBody.Builder()
                .add("UserName", user)
                .add("PassWord", pass)
                .add("Language", "english")
                .add("x.X_HW_Token", token)
                .build()
            val body = post("/login.cgi", form)
            sb.append("login resp (${body.length} chars): ").append(body.take(300)).append("\n")
            // Si entró, el panel devuelve la lista con "UserDevinfo".
            get("/html/bbsp/common/lanuserinfo.asp")
            val probe = get("/html/bbsp/common/GetLanUserDevInfo.asp")
            if (probe.contains("UserDevinfo", ignoreCase = true)) {
                loggedIn = true
                lastDebug = sb.toString()
                return
            }
            sb.append("probe: ").append(probe.take(300)).append("\n")
        }
        lastDebug = sb.toString()
        throw RuntimeException("No se pudo iniciar sesión en el módem Huawei. Revisa usuario/contraseña del panel (en la etiqueta del módem).")
    }

    private fun ensure() { if (!loggedIn) login() }

    /** Lista de equipos conectados al módem (cable + WiFi). */
    fun deviceList(): List<Device> {
        ensure()
        get("/html/bbsp/common/lanuserinfo.asp")
        var body = get("/html/bbsp/common/GetLanUserDevInfo.asp")
        if (!body.contains("UserDevinfo", ignoreCase = true)) {
            loggedIn = false; ensure()
            body = get("/html/bbsp/common/GetLanUserDevInfo.asp")
        }
        lastDebug = lastDebug + "\n" + resumen(body)
        return parse(body)
    }

    companion object {
        // Cualquier "new Algo( ... )" cuyo contenido traiga una MAC es un equipo.
        // Firmwares vistos: USERDevice(...), USERDeviceNew(...) (Totalplay), stUserDevInfo(...).
        private val ENTRY = Regex("""new\s+([A-Za-z_]\w*)\s*\(([^()]*)\)""", RegexOption.DOT_MATCHES_ALL)
        private val ESC = Regex("""\\x([0-9A-Fa-f]{2})|\\u([0-9A-Fa-f]{4})""")
        private val MAC = Regex("""^[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}$""")
        private val IPV4 = Regex("""^\d{1,3}(?:\.\d{1,3}){3}$""")

        /** El firmware codifica ':' '.' '-' etc. como \x3a, \x2e, \x2d; los decodificamos. */
        private fun unescape(v: String): String = ESC.replace(v) { m ->
            val hex = m.groupValues[1].ifEmpty { m.groupValues[2] }
            hex.toInt(16).toChar().toString()
        }

        private fun clean(v: String?): String {
            val t = unescape(v?.trim()?.trim('"', '\'')?.trim() ?: "")
            return if (t == "--" || t == "-") "" else t
        }

        fun parse(body: String): List<Device> {
            val out = ArrayList<Device>()
            val vistos = HashSet<String>()
            for (m in ENTRY.findAll(body)) {
                if (m.groupValues[1].equals("Array", true)) continue
                val f = m.groupValues[2].split(",").map { clean(it) }
                if (f.size < 3) continue
                // La MAC manda: si no hay, no es un equipo (p. ej. la definición de la función).
                val macIdx = f.indexOfFirst { MAC.matches(it) }
                if (macIdx < 0) continue
                val mac = f[macIdx].uppercase()
                if (!vistos.add(mac)) continue
                val ip = f.firstOrNull { IPV4.matches(it) } ?: ""
                val online = f.any { it.equals("Online", true) } || m.groupValues[1].contains("Wifi", true)
                // Orden conocido: ...,DevStatus(6),PortType,Time,HostName(9),...,UserDevAlias(13)
                val alias = f.getOrNull(13).orEmpty()
                val host = f.getOrNull(9).orEmpty()
                val name = when {
                    alias.isNotBlank() && !alias.equals("Online", true) && !alias.equals("Offline", true) -> alias
                    host.isNotBlank() && !host.equals("Online", true) && !host.equals("Offline", true) -> host
                    else -> f.drop(macIdx + 1).firstOrNull {
                        it.length > 1 && it.any { c -> c.isLetter() } && !IPV4.matches(it) &&
                            !it.equals("Online", true) && !it.equals("Offline", true) &&
                            !it.equals("DHCP", true) && !it.equals("Static", true) &&
                            !it.startsWith("LAN", true) && !it.startsWith("SSID", true) && !it.startsWith("WLAN", true)
                    } ?: ""
                }
                out.add(Device(
                    mac = mac, ip = ip, name = name,
                    vendor = Vendors.lookup(mac),
                    online = online, blocked = false,
                    source = "Totalplay (Huawei)", canBlock = false
                ))
            }
            return out
        }

        /** Resumen útil para ajustar el lector: constructores encontrados y el trozo con la lista. */
        fun resumen(body: String): String {
            val ctors = ENTRY.findAll(body).map { it.groupValues[1] }.groupingBy { it }.eachCount()
            val sb = StringBuilder()
            sb.append("Tamaño respuesta: ${body.length} chars\n")
            sb.append("Constructores: $ctors\n").append("Equipos con MAC: ${parse(body).size}\n")
            val i = body.indexOf("UserDevinfo = new Array").let { if (it < 0) body.indexOf("new Array(") else it }
            if (i >= 0) {
                sb.append("--- Lista (desde 'new Array') ---\n").append(body.substring(i, minOf(body.length, i + 1800)))
            } else {
                sb.append("--- No se encontró 'new Array(' ---\n").append(body.takeLast(800))
            }
            return sb.toString()
        }
    }

    private val MACFILTER_PAGES = listOf(
        "/html/bbsp/wlanmacfilter/wlanmacfilter.asp",
        "/html/bbsp/macfilter/macfilter.asp",
        "/html/amp/wlanmacfilter/wlanmacfilter.asp",
        "/html/bbsp/wlanaccesscontrol/wlanaccesscontrol.asp"
    )
    private val WLAN_PAGES = listOf(
        "/html/amp/wlanbasic/wlanbasic.asp",
        "/html/bbsp/wlanbasic/wlanbasic.asp",
        "/html/amp/wlanbasic/wlanbasic5g.asp"
    )

    /** MACs que el módem tiene en filtro/lista negra (lectura; mejor esfuerzo). */
    fun blockedMacs(): Set<String> {
        ensure()
        val out = HashSet<String>()
        for (p in MACFILTER_PAGES) {
            val body = try { get(p) } catch (_: Exception) { "" }
            if (body.length < 200 || body.contains("login.cgi") || body.contains("<title>Login")) continue
            // Solo entradas de datos (new stXxx(...)), no HTML.
            for (m in ENTRY.findAll(body)) {
                if (m.groupValues[1].equals("Array", true)) continue
                for (raw in m.groupValues[2].split(",")) {
                    val v = clean(raw).uppercase()
                    if (MAC.matches(v)) out.add(v)
                }
            }
            if (out.isNotEmpty()) break
        }
        return out
    }

    /** Captura las páginas de filtro MAC y WiFi para poder implementar bloqueo/invitados. */
    fun capturePages(): String {
        ensure()
        val sb = StringBuilder()
        for (p in MACFILTER_PAGES + WLAN_PAGES) {
            val body = try { get(p) } catch (e: Exception) { "ERR ${e.message}" }
            sb.append("=== ").append(p).append(" (").append(body.length).append(")\n")
            val i = body.indexOf("new Array(")
            sb.append(if (i >= 0) body.substring(i, minOf(body.length, i + 700)) else body.take(300)).append("\n\n")
        }
        return sb.toString()
    }

    fun test(): Boolean { login(); return loggedIn }
}
