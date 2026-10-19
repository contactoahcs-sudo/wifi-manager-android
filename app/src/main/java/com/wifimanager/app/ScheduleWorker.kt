package com.wifimanager.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Se ejecuta en segundo plano (cada ~15 min, el mínimo de Android) y aplica
 * el bloqueo o desbloqueo de cada equipo según sus franjas horarias.
 *
 * Formato de franja: "dias|inicio|fin", varias separadas por ';'.
 * dias = dígitos 0-6 (0=lunes). Ej: "01234|22:00|06:00;56|23:00|08:00"
 */
class ScheduleWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val xi: MiWifiClient? = if (Prefs.isXiaomiConfigured(ctx)) MiWifiClient(Prefs.getIp(ctx), Prefs.getPassword(ctx)) else null
        val hw: HuaweiClient? = if (Prefs.isHuaweiConfigured(ctx)) HuaweiClient(Prefs.getHwIp(ctx), Prefs.getHwUser(ctx), Prefs.getHwPassword(ctx)) else null
        if (xi == null && hw == null) return@withContext Result.success()
        try { xi?.login() } catch (_: Exception) { if (hw == null) return@withContext Result.retry() }

        val now = Calendar.getInstance()
        for (mac in Prefs.allScheduleKeys(ctx)) {
            val sched = Prefs.getSchedule(ctx, mac)
            if (sched.isBlank()) continue
            val shouldBlock = inAnyWindow(sched, now)
            val src = Prefs.getScheduleSource(ctx, mac)
            try {
                if (src.startsWith("Totalplay") && hw != null) {
                    if (shouldBlock) hw.block(mac) else hw.unblock(mac)
                } else if (xi != null) {
                    xi.setBlock(mac, shouldBlock)
                }
            } catch (_: Exception) { /* seguimos con los demás */ }
        }

        // Denegar MAC aleatoria en segundo plano (solo Xiaomi; en el Huawei el filtro es de 8 entradas).
        if (Prefs.isAutoBlockRandom(ctx) && xi != null) {
            Vendors.init(ctx)
            try {
                for (d in xi.deviceList()) {
                    if (Vendors.isRandom(d.mac) && Prefs.getAlias(ctx, d.mac).isBlank() && !d.blocked) {
                        try { xi.setBlock(d.mac, true) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) { }
        }
        Result.success()
    }

    companion object {
        fun inAnyWindow(sched: String, now: Calendar): Boolean {
            // Calendar: MONDAY=2 ... SUNDAY=1. Convertimos a 0=lunes..6=domingo.
            val dow = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7
            val hhmm = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
            for (part in sched.split(";")) {
                val f = part.split("|")
                if (f.size != 3) continue
                val dias = f[0]; val ini = f[1]; val fin = f[2]
                if (ini <= fin) {
                    if (dias.contains(('0' + dow)) && hhmm >= ini && hhmm < fin) return true
                } else {
                    // Cruza medianoche
                    if (hhmm >= ini && dias.contains(('0' + dow))) return true
                    val prev = (dow + 6) % 7
                    if (hhmm < fin && dias.contains(('0' + prev))) return true
                }
            }
            return false
        }
    }
}
