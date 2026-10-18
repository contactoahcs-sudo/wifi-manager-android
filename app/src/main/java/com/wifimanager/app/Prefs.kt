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
    fun isXiaomiConfigured(c: Context): Boolean = getPassword(c).isNotEmpty()

    // Módem principal Huawei (Totalplay). Panel normalmente 192.168.100.1, usuario root.
    fun getHwIp(c: Context): String = sp(c).getString("hw_ip", "192.168.100.1") ?: "192.168.100.1"
    fun getHwUser(c: Context): String = sp(c).getString("hw_user", "root") ?: "root"
    fun getHwPassword(c: Context): String = sp(c).getString("hw_pass", "") ?: ""
    fun isHuaweiConfigured(c: Context): Boolean = getHwPassword(c).isNotEmpty()
    fun saveHuawei(c: Context, ip: String, user: String, pass: String) {
        sp(c).edit().putString("hw_ip", ip).putString("hw_user", user).putString("hw_pass", pass).apply()
    }

    /** Hay al menos un equipo configurado (Xiaomi o Huawei). */
    fun isConfigured(c: Context): Boolean = isXiaomiConfigured(c) || isHuaweiConfigured(c)

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

    // Denegar automáticamente a los equipos con MAC aleatoria.
    fun isAutoBlockRandom(c: Context): Boolean = sp(c).getBoolean("auto_block_random", false)
    fun setAutoBlockRandom(c: Context, value: Boolean) {
        sp(c).edit().putBoolean("auto_block_random", value).apply()
    }

    // Apodo / nombre interno por equipo. Se guarda permanentemente (SharedPreferences),
    // sobrevive al cerrar y reabrir la app. Útil para equipos con MAC aleatoria.
    fun getAlias(c: Context, mac: String): String = sp(c).getString("alias_$mac", "") ?: ""
    fun setAlias(c: Context, mac: String, value: String) {
        val v = value.trim()
        if (v.isEmpty()) sp(c).edit().remove("alias_$mac").apply()
        else sp(c).edit().putString("alias_$mac", v).apply()
    }
}
