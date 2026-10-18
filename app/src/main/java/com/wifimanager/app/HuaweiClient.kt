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
        lastDebug = lastDebug + "\n--- GetLanUserDevInfo ---\n" + body.take(2000)
        return parse(body)
    }

    companion object {
        // new USERDevice("Domain","IpAddr","MacAddr","Port","PortID","DevStatus","PortType","Time","HostName",...)
        private val ENTRY = Regex("""new\s+USERDevice\s*\((.*?)\)\s*[,)]""", RegexOption.DOT_MATCHES_ALL)
        private val MAC = Regex("""([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})""")
        private val IPV4 = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""")

        fun parse(body: String): List<Device> {
            val out = ArrayList<Device>()
            for (m in ENTRY.findAll(body)) {
                val fields = m.groupValues[1].split(",").map { it.trim().trim('"') }
                val mac = fields.firstOrNull { MAC.matches(it) }?.uppercase() ?: continue
                val ip = fields.firstOrNull { IPV4.matches(it) } ?: ""
                val online = fields.any { it.equals("Online", true) }
                // El nombre suele ser el 9no campo; si no, tomamos el primero "con letras" que no sea dominio/estado.
                val name = fields.getOrNull(8)?.takeIf { it.isNotBlank() && it != "-" }
                    ?: fields.drop(3).firstOrNull {
                        it.isNotBlank() && !MAC.matches(it) && !IPV4.matches(it) &&
                            !it.equals("Online", true) && !it.equals("Offline", true) &&
                            it.any { c -> c.isLetter() }
                    } ?: ""
                out.add(Device(
                    mac = mac, ip = ip, name = name,
                    vendor = Vendors.lookup(mac),
                    online = online, blocked = false,
                    source = "Totalplay (Huawei)", canBlock = false
                ))
            }
            return out
        }
    }

    fun test(): Boolean { login(); return loggedIn }
}
