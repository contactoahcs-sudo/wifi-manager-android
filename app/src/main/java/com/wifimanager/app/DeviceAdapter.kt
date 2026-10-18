package com.wifimanager.app

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private var items: List<Device>,
    private val onBlock: (Device) -> Unit,
    private val onSchedule: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.name)
        val estado: TextView = v.findViewById(R.id.estado)
        val detalle: TextView = v.findViewById(R.id.detalle)
        val btnBlock: Button = v.findViewById(R.id.btnBlock)
        val btnSchedule: Button = v.findViewById(R.id.btnSchedule)
    }

    fun update(list: List<Device>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val d = items[position]
        h.name.text = d.displayName()
        h.detalle.text = "IP: ${d.ip}\nMAC: ${d.mac}\nFabricante: ${d.vendor}"

        if (d.blocked) {
            h.estado.text = "Bloqueado"
            h.estado.setTextColor(Color.parseColor("#FF7B72"))
            h.btnBlock.text = "Permitir"
        } else {
            h.estado.text = if (d.online) "Conectado" else "Ausente"
            h.estado.setTextColor(Color.parseColor(if (d.online) "#56D364" else "#8B97A7"))
            h.btnBlock.text = "Bloquear"
        }

        h.btnBlock.setOnClickListener { onBlock(d) }
        h.btnSchedule.setOnClickListener { onSchedule(d) }
    }
}
