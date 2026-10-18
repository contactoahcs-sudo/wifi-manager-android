package com.wifimanager.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private var items: List<Device>,
    private val onBlock: (Device) -> Unit,
    private val onSchedule: (Device) -> Unit,
    private val onRename: (Device) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    /** Modo selección múltiple (para ocultar/mostrar varios). */
    var selecting: Boolean = false
        private set
    val selected = LinkedHashSet<String>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val check: CheckBox = v.findViewById(R.id.check)
        val name: TextView = v.findViewById(R.id.name)
        val estado: TextView = v.findViewById(R.id.estado)
        val detalle: TextView = v.findViewById(R.id.detalle)
        val actions: View = v.findViewById(R.id.actions)
        val btnBlock: Button = v.findViewById(R.id.btnBlock)
        val btnSchedule: Button = v.findViewById(R.id.btnSchedule)
        val btnRename: Button = v.findViewById(R.id.btnRename)
    }

    fun update(list: List<Device>) {
        items = list
        notifyDataSetChanged()
    }

    fun current(): List<Device> = items

    fun setSelecting(on: Boolean) {
        selecting = on
        if (!on) selected.clear()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectWhere(pred: (Device) -> Boolean) {
        for (d in items) if (pred(d)) selected.add(d.mac)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        h.name.text = d.displayName()
        val realName = if (d.alias.isNotBlank() && d.name.isNotBlank()) "\nNombre en red: ${d.name}" else ""
        h.detalle.text = "IP: ${d.ip.ifBlank { "—" }}\nMAC: ${d.mac}\nOrigen: ${d.vendor}\nRed: ${d.source}$realName"

        when {
            d.blocked -> {
                h.estado.text = h.itemView.context.getString(R.string.sin_internet)
                h.estado.setTextColor(Color.parseColor("#FF7B72"))
                h.btnBlock.text = h.itemView.context.getString(R.string.permitir)
            }
            d.online -> {
                h.estado.text = "Conectado"
                h.estado.setTextColor(Color.parseColor("#56D364"))
                h.btnBlock.text = h.itemView.context.getString(R.string.bloquear)
            }
            else -> {
                h.estado.text = "Desconectado"
                h.estado.setTextColor(Color.parseColor("#8B97A7"))
                h.btnBlock.text = h.itemView.context.getString(R.string.bloquear)
            }
        }

        // Selección múltiple
        h.check.visibility = if (selecting) View.VISIBLE else View.GONE
        h.check.setOnCheckedChangeListener(null)
        h.check.isChecked = selected.contains(d.mac)
        h.check.setOnCheckedChangeListener { _, on ->
            if (on) selected.add(d.mac) else selected.remove(d.mac)
            onSelectionChanged()
        }
        h.actions.visibility = if (selecting) View.GONE else View.VISIBLE
        h.itemView.setOnClickListener { if (selecting) h.check.toggle() }

        val vis = if (d.canBlock) View.VISIBLE else View.GONE
        h.btnBlock.visibility = vis
        h.btnSchedule.visibility = vis
        h.btnBlock.setOnClickListener { onBlock(d) }
        h.btnSchedule.setOnClickListener { onSchedule(d) }
        h.btnRename.setOnClickListener { onRename(d) }
    }
}
