package com.audio.speakercombiner

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class AudioDeviceAdapter(
    private val devices: MutableList<OutputDeviceConfig>,
    private val onDeviceUpdated: (OutputDeviceConfig) -> Unit
) : RecyclerView.Adapter<AudioDeviceAdapter.DeviceViewHolder>() {

    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val swEnable: SwitchMaterial = view.findViewById(R.id.swDeviceEnable)
        val btnRole: Button = view.findViewById(R.id.btnDeviceRole)
        val sliderDelay: Slider = view.findViewById(R.id.sliderDelay)
        val tvDelayValue: TextView = view.findViewById(R.id.tvDelayValue)
        val sliderVolume: Slider = view.findViewById(R.id.sliderDeviceVolume)
        val tvVolumeValue: TextView = view.findViewById(R.id.tvDeviceVolumeValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_output_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val dev = devices[position]

        holder.tvName.text = dev.name
        holder.swEnable.isChecked = dev.isEnabled
        holder.btnRole.text = dev.role.displayName

        holder.sliderDelay.value = dev.delayMs.toFloat()
        holder.tvDelayValue.text = "${dev.delayMs} ms"

        holder.sliderVolume.value = dev.volumePercent.toFloat()
        holder.tvVolumeValue.text = "${dev.volumePercent}%"

        // Toggle device on/off
        holder.swEnable.setOnCheckedChangeListener { _, isChecked ->
            dev.isEnabled = isChecked
            onDeviceUpdated(dev)
        }

        // Pick Role popup dialog
        holder.btnRole.setOnClickListener {
            val roles = SpeakerRole.values()
            val roleNames = roles.map { it.displayName }.toTypedArray()
            val currentIdx = roles.indexOf(dev.role)

            AlertDialog.Builder(holder.itemView.context, R.style.Theme_SpeakerCombiner_Dialog)
                .setTitle("Select Role for ${dev.name}")
                .setSingleChoiceItems(roleNames, currentIdx) { dialog, which ->
                    dev.role = roles[which]
                    holder.btnRole.text = dev.role.displayName
                    onDeviceUpdated(dev)
                    dialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Delay slider (0 to 500 ms)
        holder.sliderDelay.addOnChangeListener { _, value, _ ->
            dev.delayMs = value.toInt()
            holder.tvDelayValue.text = "${dev.delayMs} ms"
            onDeviceUpdated(dev)
        }

        // Volume slider (0 to 100%)
        holder.sliderVolume.addOnChangeListener { _, value, _ ->
            dev.volumePercent = value.toInt()
            holder.tvVolumeValue.text = "${dev.volumePercent}%"
            onDeviceUpdated(dev)
        }
    }

    override fun getItemCount(): Int = devices.size

    fun updateList(newList: List<OutputDeviceConfig>) {
        devices.clear()
        devices.addAll(newList)
        notifyDataSetChanged()
    }
}
