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

    /** ¿La respuesta es una página real del panel (y no la de "vuelve a iniciar sesión")? */
    private fun esPagina(body: String): Boolean =
        body.length > 1500 || body.contains("onttoken") || body.contains("new st") || body.contains("UserDevinfo", true)

    /**
     * GET de una página del panel. El módem solo mantiene UNA sesión y la caduca rápido:
     * si contesta con la página corta de "sesión inválida", volvemos a iniciar sesión y reintentamos.
     */
    private fun getPage(path: String): String {
        ensure()
        var body = get(path)
        if (!esPagina(body)) {
            loggedIn = false
            login()
            body = get(path)
        }
        lastPage = body
        return body
    }
    @Volatile private var lastPage: String = ""

    /** Cierra la sesión del panel para no dejarla ocupada (el módem solo admite una). */
    fun logout() {
        if (!loggedIn) return
        try {
            val page = get("/html/bbsp/macfilter/macfilter.asp")
            val token = Regex("""name=["']onttoken["'][^>]*value=["']([0-9A-Fa-f]+)["']""").find(page)?.groupValues?.get(1) ?: ""
            post("/logout.cgi?RequestFile=html/logout.html", FormBody.Builder().add("x.X_HW_Token", token).build())
        } catch (_: Exception) {}
        loggedIn = false
        cookies.clear()
    }

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
            // MACs que el módem reporta como clientes WiFi (stWifiWorkingMode).
            val wifiMacs = HashSet<String>()
            for (m in ENTRY.findAll(body)) {
                if (!m.groupValues[1].contains("Wifi", true)) continue
                for (raw in m.groupValues[2].split(",")) { val v = clean(raw).uppercase(); if (MAC.matches(v)) wifiMacs.add(v) }
            }
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
                // Tipo de conexión: PortType/DevType traen "LAN1".."LAN4" (cable) o "SSID1".. / "WLAN" (WiFi).
                val port = f.drop(macIdx + 1).firstOrNull { it.startsWith("LAN", true) || it.startsWith("SSID", true) || it.startsWith("WLAN", true) } ?: ""
                val conn = when {
                    wifiMacs.contains(mac) -> "WiFi"
                    port.startsWith("LAN", true) -> "Cable"
                    port.startsWith("SSID", true) || port.startsWith("WLAN", true) -> "WiFi"
                    else -> ""
                }
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
                    source = "Totalplay (Huawei)", canBlock = true, conn = conn
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
        "/html/amp/wlanbasic/wlanbasic5g.asp",
        "/html/amp/wlanbasic/WlanBasic.asp",
        "/html/amp/wlanbasic/wlanbasic24g.asp",
        "/html/bbsp/wlan/wlanbasic.asp",
        "/html/amp/wlan/wlanbasic.asp",
        "/html/amp/wlanadvance/wlanadvance.asp",
        "/html/bbsp/wlanmacfilter/wlanmacfilter.asp"
    )

    /** MACs que el módem tiene en filtro/lista negra. */
    fun blockedMacs(): Set<String> {
        ensure()
        val out = HashSet<String>()
        try { filterEntries().forEach { out.add(it.mac) } } catch (_: Exception) {}
        if (out.isNotEmpty()) return out
        for (p in MACFILTER_PAGES) {
            val body = try { getPage(p) } catch (_: Exception) { "" }
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

    /** Captura lo relevante de las páginas de filtro MAC / WiFi para implementar bloqueo e invitados. */
    fun capturePages(): String {
        ensure()
        val sb = StringBuilder()
        val keys = Regex("""setAction|addParameter|RequestFile|webSubmitForm|\.cgi|FilterMode|MACAddress|X_HW_Token|setParameter|SubmitForm|Enable|MacFilter|macFilter|wlanmacfilter|SsidBand|FilterPolicy|\.submit\(""")
        val urlRe = Regex("""(?:href|src|action)\s*=\s*["']([^"']*(?:wlan|macfilter|Wlan|WLAN|ssid)[^"']*)["']""")
        // Enlaces del menú principal: para descubrir la página de WiFi real.
        for (p in listOf("/index.asp", "/html/bbsp/common/leftmenu.asp", "/html/amp/menu/menu.asp")) {
            val body = try { getPage(p) } catch (e: Exception) { "" }
            if (body.length < 200) continue
            val links = urlRe.findAll(body).map { it.groupValues[1] }.toSet()
            sb.append("=== ").append(p).append(" (").append(body.length).append(") enlaces wlan/macfilter:\n")
            links.take(40).forEach { sb.append("  ").append(it).append("\n") }
            sb.append("\n")
        }
        for (p in (MACFILTER_PAGES + WLAN_PAGES).distinct()) {
            val body = try { getPage(p) } catch (e: Exception) { "ERR ${e.message}" }
            sb.append("=== ").append(p).append(" (").append(body.length).append(")\n")
            if (body.length < 300) { sb.append(body.take(120)).append("\n\n"); continue }
            // Página corta sin datos: mostramos su texto para saber qué contestó el módem.
            if (!esPagina(body)) {
                sb.append("?? ").append(body.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(400)).append("\n\n"); continue
            }
            // Primero: cómo envía (set.cgi / addParameter('x.' ...)), que es lo que necesitamos para WiFi.
            var k = 0
            for (line in body.lineSequence()) {
                val t = line.trim()
                if ((t.contains("addParameter(") && (t.contains("'x.") || t.contains("\"x.") || t.contains("'y.") || t.contains("\"y."))) ||
                    t.contains(".cgi?")) {
                    sb.append("> ").append(t.take(200)).append("\n")
                    if (++k >= 120) break
                }
            }
            // Constructores de datos presentes
            val ctors = ENTRY.findAll(body).map { it.groupValues[1] }.groupingBy { it }.eachCount()
            sb.append("ctors=").append(ctors).append("\n")
            // Líneas clave (cómo se arma el envío)
            var n = 0
            for (line in body.lineSequence()) {
                val t = line.trim()
                if (t.isNotEmpty() && keys.containsMatchIn(t)) {
                    sb.append("| ").append(t.take(160)).append("\n")
                    if (++n >= 160) break
                }
            }
            // Formularios e inputs
            for (m in Regex("""<(?:form|input|select)[^>]*>""", RegexOption.IGNORE_CASE).findAll(body).take(40)) {
                sb.append("# ").append(m.value.take(160)).append("\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    // ---------- Bloqueo / permiso en el módem (filtro MAC) ----------
    //
    // Descubierto con el "Diagnóstico avanzado" del firmware Totalplay:
    //  - LAN:  /html/bbsp/macfilter/macfilter.asp   (InternetGatewayDevice.X_HW_Security.MacFilter)
    //          add.cgi?x=...X_HW_Security.MacFilter  con x.SourceMACAddress, x.DeviceAlias
    //          set.cgi?x=...X_HW_Security             con x.MacFilterRight=1 (activar), x.MacFilterPolicy=0 (lista negra)
    //          del.cgi?RequestFile=...                con <domain de la entrada>=  (y x.X_HW_Token)
    //  - WLAN: /html/bbsp/wlanmacfilter/wlanmacfilter.asp (X_HW_Security.WLANMacFilter.N), mismo del.cgi
    //  Cada página trae un token propio: <input name="onttoken" value="...">.
    private val PAGE_LAN = "/html/bbsp/macfilter/macfilter.asp"
    private val PAGE_WLAN = "/html/bbsp/wlanmacfilter/wlanmacfilter.asp"

    data class FilterEntry(val domain: String, val mac: String, val page: String)

    private fun pageToken(body: String): String =
        Regex("""name=["']onttoken["'][^>]*value=["']([0-9A-Fa-f]+)["']""").find(body)?.groupValues?.get(1)
            ?: Regex("""id=["']hwonttoken["'][^>]*value=["']([0-9A-Fa-f]+)["']""").find(body)?.groupValues?.get(1)
            ?: throw RuntimeException("El módem no entregó el token de la página.")

    /** Entradas actuales del filtro MAC (LAN + WLAN). */
    fun filterEntries(): List<FilterEntry> {
        ensure()
        val out = ArrayList<FilterEntry>()
        for (page in listOf(PAGE_LAN, PAGE_WLAN)) {
            val body = try { getPage(page) } catch (_: Exception) { "" }
            if (body.length < 300) continue
            for (m in ENTRY.findAll(body)) {
                if (!m.groupValues[1].equals("stMacFilter", true)) continue
                val f = m.groupValues[2].split(",").map { clean(it) }
                val domain = f.firstOrNull { it.startsWith("InternetGatewayDevice.") } ?: continue
                val mac = f.firstOrNull { MAC.matches(it) }?.uppercase() ?: continue
                out.add(FilterEntry(domain, mac, page))
            }
        }
        return out
    }

    private fun postForm(path: String, params: List<Pair<String, String>>): String {
        val fb = FormBody.Builder()
        for ((k, v) in params) fb.add(k, v)
        return post(path, fb.build())
    }

    /** Bloquea un equipo (lo mete a la lista negra LAN del módem y activa el filtro). */
    fun block(mac: String, alias: String = "") {
        ensure()
        val m = mac.uppercase()
        if (filterEntries().any { it.mac == m }) return
        val page = getPage(PAGE_LAN)
        if (!esPagina(page)) throw RuntimeException("El módem no abrió la página de filtro MAC (respondió: " +
            page.replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim().take(160) + ")")
        val count = ENTRY.findAll(page).count { it.groupValues[1].equals("stMacFilter", true) } - 1
        if (count >= 8) throw RuntimeException("El filtro del módem ya tiene 8 equipos (su máximo). Permite alguno para liberar espacio.")
        val token = pageToken(page)
        postForm("/html/bbsp/macfilter/add.cgi?x=InternetGatewayDevice.X_HW_Security.MacFilter&RequestFile=html/bbsp/macfilter/macfilter.asp",
            listOf("x.SourceMACAddress" to m, "x.DeviceAlias" to alias.take(32).ifEmpty { "WifiManager" }, "x.X_HW_Token" to token))
        // Activar el filtro en modo lista negra (0). Token nuevo por cada envío.
        val page2 = getPage(PAGE_LAN)
        postForm("/html/bbsp/macfilter/set.cgi?x=InternetGatewayDevice.X_HW_Security&RequestFile=html/bbsp/macfilter/macfilter.asp",
            listOf("x.MacFilterRight" to "1", "x.MacFilterPolicy" to "0", "x.X_HW_Token" to pageToken(page2)))
        if (filterEntries().none { it.mac == m }) throw RuntimeException("El módem no guardó el bloqueo.")
    }

    /** Permite un equipo (lo quita de la lista negra LAN o WLAN donde esté). */
    fun unblock(mac: String) {
        ensure()
        val m = mac.uppercase()
        val entries = filterEntries().filter { it.mac == m }
        if (entries.isEmpty()) return
        for (e in entries) {
            val dir = e.page.substringBeforeLast('/')
            val reqFile = e.page.removePrefix("/")
            val token = pageToken(getPage(e.page))
            postForm("$dir/del.cgi?RequestFile=$reqFile", listOf(e.domain to "", "x.X_HW_Token" to token))
        }
        if (filterEntries().any { it.mac == m }) throw RuntimeException("El módem no quitó el bloqueo.")
    }

    fun test(): Boolean { login(); return loggedIn }
}
