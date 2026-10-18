package com.wifimanager.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Busca e instala actualizaciones de la app desde los "Releases" de GitHub.
 *
 * El repo debe ser público para que el teléfono pueda descargar el APK sin
 * credenciales. Cada versión se publica con una etiqueta tipo "v2" (el número
 * coincide con versionCode). Si hay una etiqueta mayor que la instalada, se
 * descarga el APK y se lanza el instalador de Android.
 */
object Updater {
    private const val OWNER = "contactoahcs-sudo"
    private const val REPO = "wifi-manager-android"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    private const val APK_URL =
        "https://github.com/$OWNER/$REPO/releases/latest/download/WifiManager.apk"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class Result(val hayNueva: Boolean, val versionRemota: Int, val nota: String)

    /** Consulta la última versión publicada. No descarga nada. */
    fun check(): Result {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) {
                return Result(false, 0, "Aún no hay versiones publicadas.")
            }
            if (!resp.isSuccessful) {
                return Result(false, 0, "No se pudo consultar (código ${resp.code}).")
            }
            val json = JSONObject(resp.body?.string() ?: "{}")
            val tag = json.optString("tag_name", "")          // ej. "v2"
            val remote = tag.filter { it.isDigit() }.toIntOrNull() ?: 0
            val actual = BuildConfig.VERSION_CODE
            return if (remote > actual)
                Result(true, remote, "Hay una versión nueva (v$remote).")
            else
                Result(false, remote, "Ya tienes la última versión.")
        }
    }

    /** Descarga el APK más reciente a la caché y devuelve el archivo. */
    fun download(ctx: Context): File {
        val dir = ctx.externalCacheDir ?: ctx.cacheDir
        val out = File(dir, "WifiManager-update.apk")
        val req = Request.Builder().url(APK_URL).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Descarga falló (${resp.code}).")
            resp.body?.byteStream()?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            } ?: throw RuntimeException("Respuesta vacía al descargar.")
        }
        return out
    }
}
