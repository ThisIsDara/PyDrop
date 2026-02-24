package com.pydrop.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class DeviceAdapter(
    private val onClick: (Device) -> Unit
) : ListAdapter<Device, DeviceAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Device>() {
            override fun areItemsTheSame(a: Device, b: Device) = a.id == b.id
            override fun areContentsTheSame(a: Device, b: Device) = a == b
        }
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvIp: TextView   = view.findViewById(R.id.tvDeviceIp)
        val btnSend: Button  = view.findViewById(R.id.btnSendToDevice)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = getItem(position)
        holder.tvName.text   = device.name
        holder.tvIp.text     = "${device.address}:${device.httpPort}"
        holder.btnSend.setOnClickListener { onClick(device) }
    }
}
