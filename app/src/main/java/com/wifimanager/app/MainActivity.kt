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
        menu.add(0, 3, 1, getString(R.string.denegar_mac_aleatoria)).isCheckable = true
        menu.add(0, 4, 2, getString(R.string.cambiar_pass_wifi))
        menu.add(0, 2, 3, getString(R.string.buscar_actualizacion))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(3)?.isChecked = Prefs.isAutoBlockRandom(this)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> { showConfigDialog(); return true }
            2 -> { checkUpdate(); return true }
            3 -> { toggleAutoBlockRandom(); return true }
            4 -> { showChangeWifiPassword(); return true }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toggleAutoBlockRandom() {
        if (Prefs.isAutoBlockRandom(this)) {
            // Estaba activo → desactivar directo.
            Prefs.setAutoBlockRandom(this, false)
            Toast.makeText(this, R.string.denegar_desactivado, Toast.LENGTH_SHORT).show()
            invalidateOptionsMenu()
        } else {
            // Explicamos antes de activar (bloquea a terceros).
            AlertDialog.Builder(this)
                .setTitle(R.string.denegar_mac_aleatoria)
                .setMessage(R.string.denegar_explicacion)
                .setPositiveButton(R.string.activar) { _, _ ->
                    Prefs.setAutoBlockRandom(this, true)
                    Toast.makeText(this, R.string.denegar_activado, Toast.LENGTH_SHORT).show()
                    invalidateOptionsMenu()
                    refresh()
                }
                .setNegativeButton(R.string.cancelar, null)
                .show()
        }
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
                if (Prefs.isAutoBlockRandom(this@MainActivity)) enforceRandomBlock(conApodo)
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

    // ---- cambiar la contraseña del WiFi ----
    private fun showChangeWifiPassword() {
        if (!Prefs.isConfigured(this)) { showConfigDialog(); return }
        val view = layoutInflater.inflate(R.layout.dialog_wifi_pass, null)
        val p1 = view.findViewById<EditText>(R.id.pass1)
        val p2 = view.findViewById<EditText>(R.id.pass2)

        AlertDialog.Builder(this)
            .setTitle(R.string.cambiar_pass_wifi)
            .setMessage(R.string.cambiar_pass_aviso)
            .setView(view)
            .setPositiveButton(R.string.cambiar) { _, _ ->
                val a = p1.text.toString()
                val b = p2.text.toString()
                when {
                    a.length < 8 -> toast(getString(R.string.pass_corta))
                    a != b -> toast(getString(R.string.pass_no_coincide))
                    else -> doChangeWifiPassword(a)
                }
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun doChangeWifiPassword(newPass: String) {
        status.text = getString(R.string.cambiando_pass)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try {
                    val n = MiWifiClient(
                        Prefs.getIp(this@MainActivity),
                        Prefs.getPassword(this@MainActivity)
                    ).setWifiPassword(newPass)
                    Result.success(n)
                } catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess {
                status.text = getString(R.string.pass_cambiada)
                // Al reiniciar el WiFi este teléfono se desconecta; avisamos y no refrescamos.
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.pass_cambiada)
                    .setMessage(R.string.pass_cambiada_msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }.onFailure {
                // Puede fallar la RESPUESTA porque el WiFi ya se reinició y este teléfono se cayó.
                status.text = getString(R.string.pass_quiza)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.cambiar_pass_wifi)
                    .setMessage(R.string.pass_quiza_msg)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- denegar equipos con MAC aleatoria ----
    // Bloquea los equipos de MAC aleatoria, EXCEPTO los que tengan nombre asignado
    // (esos se consideran de confianza). Así nadie entra con MAC aleatoria salvo tus equipos.
    private fun enforceRandomBlock(devs: List<Device>) {
        val objetivos = devs.filter { Vendors.isRandom(it.mac) && it.alias.isBlank() && !it.blocked }
        if (objetivos.isEmpty()) return
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) {
                var c = 0
                try {
                    val client = MiWifiClient(
                        Prefs.getIp(this@MainActivity),
                        Prefs.getPassword(this@MainActivity)
                    )
                    for (t in objetivos) {
                        try { client.setBlock(t.mac, true); c++ } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
                c
            }
            if (n > 0) {
                Toast.makeText(this@MainActivity, getString(R.string.auto_bloqueados, n), Toast.LENGTH_SHORT).show()
                refresh()
            }
        }
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
