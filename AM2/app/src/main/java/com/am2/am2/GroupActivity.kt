package com.am2.am2

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.am2.am2.databinding.ActivityGroupBinding
import org.json.JSONArray
import org.json.JSONObject

class GroupActivity : BaseActivity() {

    private lateinit var binding: ActivityGroupBinding
    private var adapter: ChannelAdapter? = null
    
    private var isWaitingForJoin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        
        WebSocketManager.availableChannels.value?.let { channels ->
            adapter?.setChannels(channels)
        }
        
        WebSocketManager.availableChannels.observe(this) { channels ->
            if (channels != null) {
                adapter?.setChannels(channels)
            }
        }

        WebSocketManager.channelName.observe(this) { _ ->
            if (isWaitingForJoin) {
                isWaitingForJoin = false
                navigateToMain()
            }
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        startActivity(intent)
        finish()
    }

    private fun setupRecyclerView() {
        binding.rvChannels.layoutManager = LinearLayoutManager(this)
        adapter = ChannelAdapter { channel ->
            val slug = channel.optString("slug")
            if (slug.isNotEmpty()) {
                if (slug == WebSocketManager.currentChannelSlug) {
                    navigateToMain()
                } else {
                    isWaitingForJoin = true
                    WebSocketManager.joinChannel(slug)
                }
            }
        }
        binding.rvChannels.adapter = adapter
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val focusedView = currentFocus
            focusedView?.performClick()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    class ChannelAdapter(private val onItemClick: (JSONObject) -> Unit) :
        RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        private var channels = JSONArray()

        fun setChannels(data: JSONArray) {
            val list = mutableListOf<JSONObject>()
            var activeChannel: JSONObject? = null
            val currentSlug = WebSocketManager.currentChannelSlug

            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                if (obj.optString("slug") == currentSlug) {
                    activeChannel = obj
                } else {
                    list.add(obj)
                }
            }

            val sortedArray = JSONArray()
            activeChannel?.let { sortedArray.put(it) }
            list.forEach { sortedArray.put(it) }

            this.channels = sortedArray
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_channel, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val channel = channels.optJSONObject(position) ?: return
            val name = channel.optString("display_name")
            val slug = channel.optString("slug")
            
            holder.tvName.text = name
            
            val isCurrent = (slug == WebSocketManager.currentChannelSlug)
            
            // Indikator visual untuk channel yang aktif saat ini
            if (isCurrent) {
                holder.imgIcon.setColorFilter(Color.parseColor("#FF9800"))
                holder.tvName.setTextColor(Color.parseColor("#FF9800"))
            } else {
                holder.imgIcon.setColorFilter(Color.GRAY)
                holder.tvName.setTextColor(Color.WHITE)
            }

            if (isCurrent && position == 0) {
                 holder.itemView.post { holder.itemView.requestFocus() }
            }

            holder.itemView.setOnClickListener { onItemClick(channel) }
        }

        override fun getItemCount(): Int = channels.length()

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvChannelName)
            val imgIcon: ImageView = view.findViewById(R.id.imgChannel)
        }
    }
}
