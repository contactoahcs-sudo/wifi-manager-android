package com.wifimanager.app

import android.content.Context

/**
 * Guarda IP, contraseña del router y horarios SOLO en el teléfono
 * (SharedPreferences privadas de la app). Nada sale del dispositivo.
 */
object Prefs {
    private const val FILE = "wifimanager"
    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getIp(c: Context): String = sp(c).getString("ip", "192.168.31.1") ?: "192.168.31.1"
    fun getPassword(c: Context): String = sp(c).getString("password", "") ?: ""
    fun isConfigured(c: Context): Boolean = getPassword(c).isNotEmpty()

    fun save(c: Context, ip: String, password: String) {
        sp(c).edit().putString("ip", ip).putString("password", password).apply()
    }

    // Horarios: por MAC guardamos franjas "dias|inicio|fin" separadas por ';'
    // dias = dígitos 0-6 (0=lunes). Ej: "01234|22:00|06:00"
    fun getSchedule(c: Context, mac: String): String = sp(c).getString("sched_$mac", "") ?: ""
    fun setSchedule(c: Context, mac: String, value: String) {
        sp(c).edit().putString("sched_$mac", value).apply()
    }
    fun allScheduleKeys(c: Context): List<String> =
        sp(c).all.keys.filter { it.startsWith("sched_") }.map { it.removePrefix("sched_") }

    // Apodo / nombre interno por equipo. Se guarda permanentemente (SharedPreferences),
    // sobrevive al cerrar y reabrir la app. Útil para equipos con MAC aleatoria.
    fun getAlias(c: Context, mac: String): String = sp(c).getString("alias_$mac", "") ?: ""
    fun setAlias(c: Context, mac: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) sp(c).edit().remove("alias_$mac").apply()
        else sp(c).edit().putString("alias_$mac", v).apply()
    }
}
