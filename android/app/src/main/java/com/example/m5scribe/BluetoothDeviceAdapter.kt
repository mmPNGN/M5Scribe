package com.example.m5scribe

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class BluetoothDeviceAdapter(
    private val onDeviceClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<BluetoothDeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<BluetoothDevice>()

    @SuppressLint("NotifyDataSetChanged")
    fun updateDevices(newDevices: List<BluetoothDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(android.R.id.text1)
        private val addressText: TextView = itemView.findViewById(android.R.id.text2)

        @SuppressLint("MissingPermission")
        fun bind(device: BluetoothDevice) {
            nameText.text = device.name ?: "Unknown Device"
            addressText.text = device.address

            itemView.setOnClickListener {
                onDeviceClick(device)
            }
        }
    }
}
