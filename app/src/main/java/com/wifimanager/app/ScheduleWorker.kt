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
        if (!Prefs.isConfigured(applicationContext)) return@withContext Result.success()
        val client = MiWifiClient(
            Prefs.getIp(applicationContext),
            Prefs.getPassword(applicationContext)
        )
        try {
            client.login()
        } catch (e: Exception) {
            return@withContext Result.retry()
        }

        val now = Calendar.getInstance()
        for (mac in Prefs.allScheduleKeys(applicationContext)) {
            val sched = Prefs.getSchedule(applicationContext, mac)
            if (sched.isBlank()) continue
            val shouldBlock = inAnyWindow(sched, now)
            try {
                client.setBlock(mac, shouldBlock)
            } catch (_: Exception) { /* seguimos con los demás */ }
        }

        // Denegar MAC aleatoria en segundo plano (excepto equipos con nombre asignado).
        if (Prefs.isAutoBlockRandom(applicationContext)) {
            Vendors.init(applicationContext)
            try {
                for (d in client.deviceList()) {
                    if (Vendors.isRandom(d.mac) &&
                        Prefs.getAlias(applicationContext, d.mac).isBlank() &&
                        !d.blocked
                    ) {
                        try { client.setBlock(d.mac, true) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) { /* si falla la lista, lo reintenta el siguiente ciclo */ }
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
