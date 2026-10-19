package com.wifimanager.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var subStatus: TextView
    private lateinit var selBar: View
    private lateinit var btnHideSel: Button
    private lateinit var chips: ChipGroup
    private lateinit var adapter: DeviceAdapter

    /** Todos los equipos leídos (antes de filtrar/ocultar). */
    private var all: List<Device> = emptyList()
    private var lastErrors: List<String> = emptyList()
    private var countHw = 0
    private var countXi = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        Vendors.init(applicationContext)   // carga la base de fabricantes

        status = findViewById(R.id.status)
        subStatus = findViewById(R.id.subStatus)
        selBar = findViewById(R.id.selBar)
        btnHideSel = findViewById(R.id.btnHideSel)
        chips = findViewById(R.id.chips)
        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = DeviceAdapter(emptyList(), ::onBlockToggle, ::onSchedule, ::onRename, ::onSelectionChanged)
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.refresh).setOnClickListener { refresh() }

        // Filtros
        val chipByFilter = mapOf(
            "todos" to R.id.c_todos, "conectados" to R.id.c_conectados, "bloqueados" to R.id.c_bloqueados,
            "desconectados" to R.id.c_desconectados, "totalplay" to R.id.c_totalplay,
            "xiaomi" to R.id.c_xiaomi, "cable" to R.id.c_cable, "wifi" to R.id.c_wifi, "ocultos" to R.id.c_ocultos
        )
        chips.check(chipByFilter[Prefs.getFilter(this)] ?: R.id.c_todos)
        chips.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val f = chipByFilter.entries.firstOrNull { it.value == id }?.key ?: "todos"
            Prefs.setFilter(this, f)
            if (adapter.selecting) adapter.setSelecting(false)
            render()
        }

        // Barra de selección múltiple
        findViewById<Button>(R.id.btnSelOffline).setOnClickListener {
            adapter.selectWhere { !it.online && !it.blocked }
        }
        btnHideSel.setOnClickListener { applySelection() }
        findViewById<Button>(R.id.btnCancelSel).setOnClickListener { adapter.setSelecting(false) }

        if (!Prefs.isConfigured(this)) showConfigDialog() else refresh()
        scheduleWorker()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.config_router))
        menu.add(0, 3, 1, getString(R.string.denegar_mac_aleatoria)).isCheckable = true
        menu.add(0, 5, 2, getString(R.string.config_huawei))
        menu.add(0, 4, 3, getString(R.string.cambiar_pass_wifi))
        menu.add(0, 7, 4, getString(R.string.seleccionar))
        menu.add(0, 8, 5, getString(R.string.herramientas))
        menu.add(0, 6, 6, getString(R.string.diagnostico_huawei))
        menu.add(0, 2, 7, getString(R.string.buscar_actualizacion))
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
            5 -> { showHuaweiDialog(); return true }
            6 -> { runHuaweiDiagnostic(); return true }
            7 -> { adapter.setSelecting(!adapter.selecting); return true }
            8 -> { showTools(); return true }
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
            val ctx = this@MainActivity
            data class Leido(val xiaomi: List<Device>, val hw: List<Device>,
                             val blockedXi: Set<String>, val blockedHw: Set<String>, val errs: List<String>)
            val r = withContext(Dispatchers.IO) {
                val errs = ArrayList<String>()
                var x: List<Device> = emptyList(); var bx: Set<String> = emptySet()
                if (Prefs.isXiaomiConfigured(ctx)) try {
                    val c = MiWifiClient(Prefs.getIp(ctx), Prefs.getPassword(ctx))
                    x = c.deviceList()
                    bx = try { c.blockedMacs() } catch (_: Exception) { emptySet() }
                } catch (e: Exception) { errs.add("Xiaomi: ${e.message}") }
                var h: List<Device> = emptyList(); var bh: Set<String> = emptySet()
                if (Prefs.isHuaweiConfigured(ctx)) try {
                    val c = HuaweiClient(Prefs.getHwIp(ctx), Prefs.getHwUser(ctx), Prefs.getHwPassword(ctx))
                    h = c.deviceList()
                    bh = try { c.blockedMacs() } catch (_: Exception) { emptySet() }
                    c.logout()
                } catch (e: Exception) { errs.add("Totalplay: ${e.message}") }
                Leido(x, h, bx, bh, errs)
            }
            // Unimos por MAC. Si está en los dos, gana el Xiaomi (ese sí se puede bloquear).
            val porMac = LinkedHashMap<String, Device>()
            for (d in r.hw) porMac[d.mac] = d.copy(blocked = d.blocked || r.blockedHw.contains(d.mac))
            for (d in r.xiaomi) porMac[d.mac] = d.copy(blocked = d.blocked || r.blockedXi.contains(d.mac))
            // Bloqueados que ya no aparecen en la lista: los mostramos para poder "Permitir".
            for (m in r.blockedXi) if (!porMac.containsKey(m))
                porMac[m] = Device(m, "", "", Vendors.lookup(m), online = false, blocked = true, source = "Xiaomi", canBlock = true)
            for (m in r.blockedHw) if (!porMac.containsKey(m))
                porMac[m] = Device(m, "", "", Vendors.lookup(m), online = false, blocked = true, source = "Totalplay (Huawei)", canBlock = true)
            all = porMac.values.map { it.copy(alias = Prefs.getAlias(ctx, it.mac)) }
            countHw = r.hw.size; countXi = r.xiaomi.size; lastErrors = r.errs
            render()
            if (Prefs.isAutoBlockRandom(ctx)) enforceRandomBlock(all)
        }
    }

    /** Aplica filtro + ocultos y pinta la lista y el resumen. */
    private fun render() {
        val hidden = Prefs.hiddenMacs(this)
        val f = Prefs.getFilter(this)
        val visibles = if (f == "ocultos") all.filter { hidden.contains(it.mac) } else all.filter { !hidden.contains(it.mac) }
        val lista = when (f) {
            "conectados" -> visibles.filter { it.online && !it.blocked }
            "bloqueados" -> visibles.filter { it.blocked }
            "desconectados" -> visibles.filter { !it.online && !it.blocked }
            "totalplay" -> visibles.filter { it.source.startsWith("Totalplay") }
            "xiaomi" -> visibles.filter { it.source == "Xiaomi" }
            "cable" -> visibles.filter { it.conn == "Cable" }
            "wifi" -> visibles.filter { it.conn == "WiFi" }
            else -> visibles
        }.sortedWith(compareByDescending<Device> { it.online && !it.blocked }
            .thenByDescending { it.blocked }
            // Dentro de cada grupo, primero los de cable, luego WiFi.
            .thenBy { when (it.conn) { "Cable" -> 0; "WiFi" -> 1; else -> 2 } }
            .thenBy { it.displayName().lowercase() })
        adapter.update(lista)

        val online = all.count { it.online && !it.blocked }
        val bloq = all.count { it.blocked }
        val off = all.count { !it.online && !it.blocked }
        status.text = getString(R.string.resumen_hdr, online, bloq, off)
        subStatus.text = buildString {
            append("Totalplay ").append(countHw).append(" · Xiaomi ").append(countXi)
            val cable = all.count { it.conn == "Cable" && it.online }; val wifi = all.count { it.conn == "WiFi" && it.online }
            if (cable + wifi > 0) append(" · cable ").append(cable).append(" · WiFi ").append(wifi)
            if (hidden.isNotEmpty()) append(" · ocultos ").append(hidden.size)
            append(" · mostrando ").append(lista.size)
            if (lastErrors.isNotEmpty()) append("\n").append(lastErrors.joinToString("\n"))
        }
        btnHideSel.text = if (f == "ocultos") getString(R.string.mostrar_sel, adapter.selected.size)
                          else getString(R.string.ocultar_sel, adapter.selected.size)
    }

    private fun onSelectionChanged() {
        selBar.visibility = if (adapter.selecting) View.VISIBLE else View.GONE
        val f = Prefs.getFilter(this)
        btnHideSel.text = if (f == "ocultos") getString(R.string.mostrar_sel, adapter.selected.size)
                          else getString(R.string.ocultar_sel, adapter.selected.size)
    }

    /** Oculta (o, en la vista "Ocultos", vuelve a mostrar) los seleccionados. */
    private fun applySelection() {
        val sel = adapter.selected.toSet()
        if (sel.isEmpty()) { toast(getString(R.string.sel_ninguno)); return }
        val hidden = Prefs.hiddenMacs(this)
        if (Prefs.getFilter(this) == "ocultos") {
            hidden.removeAll(sel); Prefs.setHiddenMacs(this, hidden)
            toast(getString(R.string.restaurados, sel.size))
        } else {
            hidden.addAll(sel); Prefs.setHiddenMacs(this, hidden)
            toast(getString(R.string.ocultados, sel.size))
        }
        adapter.setSelecting(false)
        render()
    }

    // ---- herramientas ----
    private fun showTools() {
        val items = arrayOf(
            getString(R.string.red_invitados),
            getString(R.string.estado_red),
            getString(R.string.reiniciar_xiaomi),
            getString(R.string.cambiar_pass_wifi),
            getString(R.string.diag_avanzado)
        )
        AlertDialog.Builder(this).setTitle(R.string.herramientas)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showGuestDialog()
                    1 -> showNetworkStatus()
                    2 -> confirmReboot()
                    3 -> showChangeWifiPassword()
                    4 -> runHuaweiAdvancedDiagnostic()
                }
            }.setNegativeButton(R.string.cancelar, null).show()
    }

    private fun showGuestDialog() {
        if (!Prefs.isXiaomiConfigured(this)) { showConfigDialog(); return }
        val view = layoutInflater.inflate(R.layout.dialog_guest, null)
        val ssid = view.findViewById<EditText>(R.id.g_ssid)
        val pass = view.findViewById<EditText>(R.id.g_pass)
        lifecycleScope.launch {
            val cur = withContext(Dispatchers.IO) {
                try { MiWifiClient(Prefs.getIp(this@MainActivity), Prefs.getPassword(this@MainActivity)).guestInfo() } catch (_: Exception) { null }
            }
            ssid.setText(cur?.optString("ssid", "") ?: "Invitados")
            if (ssid.text.isNullOrBlank()) ssid.setText("Invitados")
            pass.setText(cur?.optString("password", "") ?: "")
        }
        AlertDialog.Builder(this).setTitle(R.string.red_invitados).setView(view)
            .setPositiveButton(R.string.inv_activar) { _, _ ->
                val sV = ssid.text.toString().trim(); val pV = pass.text.toString()
                when {
                    sV.isEmpty() -> toast(getString(R.string.inv_nombre))
                    pV.isNotEmpty() && pV.length < 8 -> toast(getString(R.string.pass_corta))
                    else -> applyGuest(true, sV, pV)
                }
            }
            .setNeutralButton(R.string.inv_apagar) { _, _ -> applyGuest(false, ssid.text.toString().trim().ifEmpty { "Invitados" }, pass.text.toString()) }
            .setNegativeButton(R.string.cancelar, null)
            .setMessage(getString(R.string.inv_huawei_nota))
            .show()
    }

    private fun applyGuest(on: Boolean, ssid: String, pass: String) {
        status.text = getString(R.string.probando)
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                try { Result.success(MiWifiClient(Prefs.getIp(this@MainActivity), Prefs.getPassword(this@MainActivity)).setGuest(on, ssid, pass)) }
                catch (e: Exception) { Result.failure(e) }
            }
            res.onSuccess { j ->
                if (j.optInt("code", -1) == 0) toast(getString(R.string.inv_ok))
                else AlertDialog.Builder(this@MainActivity).setTitle(R.string.red_invitados)
                    .setMessage(getString(R.string.inv_error, j.toString())).setPositiveButton(android.R.string.ok, null).show()
            }.onFailure { e -> toast(getString(R.string.inv_error, e.message ?: "")) }
            render()
        }
    }

    private fun showNetworkStatus() {
        lifecycleScope.launch {
            val txt = withContext(Dispatchers.IO) {
                val sb = StringBuilder()
                if (Prefs.isXiaomiConfigured(this@MainActivity)) {
                    sb.append("— Xiaomi —\n")
                    val xc = MiWifiClient(Prefs.getIp(this@MainActivity), Prefs.getPassword(this@MainActivity))
                    sb.append(try { xc.wanInfoText() } catch (e: Exception) { "Error: ${e.message}" })
                    sb.append("\n\nWiFi (claves tapadas):\n")
                    sb.append(try { xc.wifiDetailMasked() } catch (e: Exception) { "Error: ${e.message}" })
                    sb.append("\nEquipos crudos del Xiaomi:\n")
                    sb.append(try { xc.rawDeviceSample(4) } catch (e: Exception) { "Error: ${e.message}" })
                    sb.append("\n\n")
                }
                sb.append("— Equipos —\n")
                sb.append("En línea: ").append(all.count { it.online && !it.blocked }).append("\n")
                sb.append("Sin internet: ").append(all.count { it.blocked }).append("\n")
                sb.append("Desconectados: ").append(all.count { !it.online && !it.blocked }).append("\n")
                sb.append("MAC aleatoria: ").append(all.count { Vendors.isRandom(it.mac) }).append("\n")
                sb.toString()
            }
            showReport(getString(R.string.estado_red), txt)
        }
    }

    private fun confirmReboot() {
        if (!Prefs.isXiaomiConfigured(this)) { showConfigDialog(); return }
        AlertDialog.Builder(this).setTitle(R.string.reiniciar_xiaomi).setMessage(R.string.reiniciar_confirma)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                status.text = getString(R.string.reiniciando)
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { try { MiWifiClient(Prefs.getIp(this@MainActivity), Prefs.getPassword(this@MainActivity)).reboot() } catch (_: Exception) {} }
                    toast(getString(R.string.hecho))
                }
            }.setNegativeButton(R.string.cancelar, null).show()
    }

    private fun runHuaweiAdvancedDiagnostic() {
        if (!Prefs.isHuaweiConfigured(this)) { showHuaweiDialog(); return }
        status.text = getString(R.string.probando)
        lifecycleScope.launch {
            val txt = withContext(Dispatchers.IO) {
                try { HuaweiClient(Prefs.getHwIp(this@MainActivity), Prefs.getHwUser(this@MainActivity), Prefs.getHwPassword(this@MainActivity)).capturePages() }
                catch (e: Exception) { "ERROR: ${e.message}" }
            }
            render()
            showReport(getString(R.string.diag_avanzado), txt)
        }
    }

    private fun onBlockToggle(d: Device) {
        val blocking = !d.blocked
        status.text = getString(if (blocking) R.string.bloqueando else R.string.permitiendo)
        lifecycleScope.launch {
            val err: String? = withContext(Dispatchers.IO) {
                try {
                    if (d.source.startsWith("Totalplay")) {
                        val c = HuaweiClient(Prefs.getHwIp(this@MainActivity), Prefs.getHwUser(this@MainActivity), Prefs.getHwPassword(this@MainActivity))
                        try { if (blocking) c.block(d.mac, d.displayName()) else c.unblock(d.mac) } finally { c.logout() }
                    } else {
                        MiWifiClient(Prefs.getIp(this@MainActivity), Prefs.getPassword(this@MainActivity))
                            .setBlock(d.mac, blocking)
                    }
                    null
                } catch (e: Exception) { e.message ?: e.toString() }
            }
            if (err == null) {
                Toast.makeText(this@MainActivity, R.string.hecho, Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (blocking) R.string.bloquear else R.string.permitir)
                    .setMessage(err)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
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
                Prefs.setScheduleSource(this, d.mac, d.source)
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

    // ---- módem principal Totalplay (Huawei) ----
    private fun showHuaweiDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_huawei, null)
        val ip = view.findViewById<EditText>(R.id.hw_ip)
        val user = view.findViewById<EditText>(R.id.hw_user)
        val pass = view.findViewById<EditText>(R.id.hw_pass)
        ip.setText(Prefs.getHwIp(this))
        user.setText(Prefs.getHwUser(this))

        AlertDialog.Builder(this)
            .setTitle(R.string.config_huawei)
            .setView(view)
            .setPositiveButton(R.string.probar_guardar) { _, _ ->
                val ipV = ip.text.toString().trim().ifEmpty { "192.168.100.1" }
                val userV = user.text.toString().trim().ifEmpty { "root" }
                val passV = pass.text.toString()
                testAndSaveHuawei(ipV, userV, passV)
            }
            .setNeutralButton(R.string.quitar_huawei) { _, _ ->
                Prefs.saveHuawei(this, "192.168.100.1", "root", "")
                refresh()
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun testAndSaveHuawei(ip: String, user: String, password: String) {
        status.text = getString(R.string.probando)
        lifecycleScope.launch {
            val c = HuaweiClient(ip, user, password)
            val result = withContext(Dispatchers.IO) {
                try { c.test(); "ok" } catch (e: Exception) { e.message ?: "error" }
            }
            if (result == "ok") {
                Prefs.saveHuawei(this@MainActivity, ip, user, password)
                Toast.makeText(this@MainActivity, R.string.conectado_ok, Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                status.text = result
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.no_conecto)
                    .setMessage(result + "\n\n" + c.lastDebug.take(1200))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    /** Muestra lo que responde el módem Huawei, para poder ajustar la app a su firmware. */
    private fun runHuaweiDiagnostic() {
        if (!Prefs.isHuaweiConfigured(this)) { showHuaweiDialog(); return }
        status.text = getString(R.string.probando)
        lifecycleScope.launch {
            val c = HuaweiClient(Prefs.getHwIp(this@MainActivity), Prefs.getHwUser(this@MainActivity), Prefs.getHwPassword(this@MainActivity))
            val txt = withContext(Dispatchers.IO) {
                try {
                    val n = c.deviceList().size
                    "Equipos leídos: $n\n\n" + c.lastDebug
                } catch (e: Exception) { "ERROR: ${e.message}\n\n" + c.lastDebug }
            }
            status.text = getString(R.string.diagnostico_huawei)
            showReport(getString(R.string.diagnostico_huawei), txt)
        }
    }

    // ---- cambiar la contraseña del WiFi ----
    private fun showChangeWifiPassword() {
        if (!Prefs.isXiaomiConfigured(this)) { showConfigDialog(); return }
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
            }.onFailure { e ->
                // Puede fallar la RESPUESTA porque el WiFi ya se reinició y este teléfono se cayó.
                status.text = getString(R.string.pass_quiza)
                showReport(getString(R.string.cambiar_pass_wifi), getString(R.string.pass_quiza_msg) + "\n\nDetalle:\n" + (e.message ?: e.toString()))
            }
        }
    }

    /** Muestra un reporte largo con botones Copiar y Compartir (para pegarlo en el chat). */
    private fun showReport(title: String, text: String) {
        val tv = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            textSize = 12f
            setPadding(40, 20, 40, 20)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        AlertDialog.Builder(this).setTitle(title).setView(scroll)
            .setPositiveButton("Copiar") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(title, text))
                toast("Copiado al portapapeles")
            }
            .setNeutralButton("Compartir") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, title); putExtra(Intent.EXTRA_TEXT, text) }
                startActivity(Intent.createChooser(i, title))
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- denegar equipos con MAC aleatoria ----
    // Bloquea los equipos de MAC aleatoria, EXCEPTO los que tengan nombre asignado
    // (esos se consideran de confianza). Así nadie entra con MAC aleatoria salvo tus equipos.
    private fun enforceRandomBlock(devs: List<Device>) {
        val objetivos = devs.filter { it.source == "Xiaomi" && Vendors.isRandom(it.mac) && it.alias.isBlank() && !it.blocked }
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
