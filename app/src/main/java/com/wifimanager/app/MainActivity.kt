package com.wifimanager.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var adapter: DeviceAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        Vendors.init(applicationContext)   // carga la base de fabricantes

        status = findViewById(R.id.status)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = DeviceAdapter(emptyList(), ::onBlockToggle, ::onSchedule, ::onRename)
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.refresh).setOnClickListener { refresh() }

        if (!Prefs.isConfigured(this)) showConfigDialog() else refresh()
        scheduleWorker()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.config_router))
        menu.add(0, 2, 1, getString(R.string.buscar_actualizacion))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { showConfigDialog(); return true }
            2 -> { checkUpdate(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    // ---- configuración del router ----
    private fun showConfigDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_config, null)
        val ip = view.findViewById<EditText>(R.id.ip)
        val pass = view.findViewById<EditText>(R.id.pass)
        ip.setText(Prefs.getIp(this))

        AlertDialog.Builder(this)
            .setTitle(R.string.config_router)
            .setView(view)
            .setPositiveButton(R.string.probar_guardar) { _, _ ->
                val ipV = ip.text.toString().trim().ifEmpty { "192.168.31.1" }
                val passV = pass.text.toString()
                testAndSave(ipV, passV)
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun testAndSave(ip: String, password: String) {
        status.text = getString(R.string.probando)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { MiWifiClient(ip, password).test(); "ok" }
                catch (e: Exception) { e.message ?: "error" }
            }
            if (result == "ok") {
                Prefs.save(this@MainActivity, ip, password)
                Toast.makeText(this@MainActivity, R.string.conectado_ok, Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                status.text = result
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.no_conecto)
                    .setMessage(result)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    // ---- lista de equipos ----
    private fun refresh() {
        if (!Prefs.isConfigured(this)) { showConfigDialog(); return }
        status.text = getString(R.string.cargando)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val c = MiWifiClient(Prefs.getIp(this@MainActivity), Prefs.getPassword(this@MainActivity))
                    Result.success(c.deviceList())
                } catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess { devs ->
                // Aplicamos el apodo guardado (si existe) a cada equipo antes de mostrarlo.
                val conApodo = devs.map { it.copy(alias = Prefs.getAlias(this@MainActivity, it.mac)) }
                adapter.update(conApodo)
                status.text = getString(R.string.equipos_conectados, devs.size)
            }.onFailure { e ->
                status.text = e.message ?: getString(R.string.error_generico)
            }
        }
    }

    private fun onBlockToggle(d: Device) {
        val blocking = !d.blocked
        status.text = getString(if (blocking) R.string.bloqueando else R.string.permitiendo)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    MiWifiClient(Prefs.getIp(this@MainActivity), Prefs.getPassword(this@MainActivity))
                        .setBlock(d.mac, blocking)
                    true
                } catch (e: Exception) { false }
            }
            Toast.makeText(
                this@MainActivity,
                if (ok) R.string.hecho else R.string.error_generico,
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    // ---- horario simple por equipo ----
    private fun onSchedule(d: Device) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "01234|22:00|06:00"
        input.setText(Prefs.getSchedule(this, d.mac))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.horario_de, d.displayName()))
            .setMessage(R.string.horario_ayuda)
            .setView(input)
            .setPositiveButton(R.string.guardar) { _, _ ->
                Prefs.setSchedule(this, d.mac, input.text.toString().trim())
                Toast.makeText(this, R.string.horario_guardado, Toast.LENGTH_SHORT).show()
                scheduleWorker()
            }
            .setNeutralButton(R.string.quitar) { _, _ ->
                Prefs.setSchedule(this, d.mac, "")
                Toast.makeText(this, R.string.horario_quitado, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    // ---- nombre interno (apodo) por equipo ----
    private fun onRename(d: Device) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        input.hint = getString(R.string.nombre_hint)
        input.setText(Prefs.getAlias(this, d.mac))

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.nombre_de, d.displayName()))
            .setMessage(getString(R.string.nombre_ayuda, d.mac))
            .setView(input)
            .setPositiveButton(R.string.guardar) { _, _ ->
                Prefs.setAlias(this, d.mac, input.text.toString())
                Toast.makeText(this, R.string.nombre_guardado, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNeutralButton(R.string.quitar) { _, _ ->
                Prefs.setAlias(this, d.mac, "")
                Toast.makeText(this, R.string.nombre_quitado, Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun scheduleWorker() {
        val work = PeriodicWorkRequestBuilder<ScheduleWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "horarios", ExistingPeriodicWorkPolicy.UPDATE, work
        )
    }

    // ---- actualización de la app ----
    private fun checkUpdate() {
        status.text = getString(R.string.buscando_update)
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                try { Updater.check() } catch (e: Exception) { Updater.Result(false, 0, e.message ?: "error") }
            }
            status.text = r.nota
            if (r.hayNueva) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.hay_actualizacion)
                    .setMessage(getString(R.string.descargar_instalar_v, r.versionRemota))
                    .setPositiveButton(R.string.actualizar_ya) { _, _ -> downloadAndInstall() }
                    .setNegativeButton(R.string.ahora_no, null)
                    .show()
            } else {
                Toast.makeText(this@MainActivity, r.nota, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun downloadAndInstall() {
        status.text = getString(R.string.descargando_update)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(Updater.download(this@MainActivity)) }
                catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess { file -> installApk(file) }
                .onFailure { e ->
                    status.text = e.message ?: getString(R.string.error_generico)
                    Toast.makeText(this@MainActivity, R.string.error_descarga, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun installApk(file: File) {
        // En Android 8+ el usuario debe permitir "instalar apps de esta fuente" la 1a vez.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permiso_instalar)
                .setMessage(R.string.permiso_instalar_msg)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")))
                }
                .setNegativeButton(R.string.cancelar, null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }
}
