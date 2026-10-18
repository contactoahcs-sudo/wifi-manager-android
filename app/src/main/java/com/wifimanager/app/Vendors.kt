package com.wifimanager.app

import android.content.Context

/**
 * Fabricante / origen del dispositivo a partir de la MAC (OUI = primeros 3 bytes).
 *
 * Carga la base COMPLETA del IEEE (~35 mil fabricantes) desde assets/oui.txt,
 * así casi ningún equipo queda como "Desconocido". Detecta además las MAC
 * aleatorias que usan los teléfonos modernos por privacidad.
 */
object Vendors {

    // Tabla base (por si el asset no cargó); se completa con la base IEEE.
    private val table: HashMap<String, String> = HashMap()
    @Volatile private var loaded = false

    /** Carga la base completa desde assets. Llamar una vez al iniciar. */
    @Synchronized
    fun init(ctx: Context) {
        if (loaded) return
        try {
            ctx.assets.open("oui.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val i = line.indexOf('\t')
                    if (i == 6) {
                        table[line.substring(0, 6)] = line.substring(i + 1)
                    }
                }
            }
            loaded = true
        } catch (_: Exception) {
            // Si falla, seguimos con lo que haya (puede quedar vacío → "Desconocido").
        }
    }

    private fun normalize(mac: String): String =
        mac.uppercase().filter { it in "0123456789ABCDEF" }

    /** Marca/fabricante del equipo, o un texto claro cuando no se puede. */
    fun lookup(mac: String): String {
        val h = normalize(mac)
        if (h.length < 6) return "Desconocido"
        val first = h.substring(0, 2).toIntOrNull(16) ?: return "Desconocido"
        // Bit "administrada localmente": los teléfonos usan MAC aleatoria por privacidad.
        if (first and 0x02 != 0) return "MAC privada (aleatoria)"
        return table[h.substring(0, 6)] ?: "Fabricante no listado"
    }

    fun size(): Int = table.size

    /** true si la MAC es aleatoria/privada (bit "administrada localmente"). */
    fun isRandom(mac: String): Boolean {
        val h = normalize(mac)
        if (h.length < 2) return false
        val first = h.substring(0, 2).toIntOrNull(16) ?: return false
        return first and 0x02 != 0
    }
}
