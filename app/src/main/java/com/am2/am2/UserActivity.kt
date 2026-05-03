package com.am2.am2

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.am2.am2.databinding.ActivityUserBinding
import org.json.JSONArray
import org.json.JSONObject

class UserActivity : BaseActivity() {

    private lateinit var binding: ActivityUserBinding
    private var adapter: UserAdapter? = null
    private var audioDeviceManager: AudioDeviceManager? = null
    private lateinit var prefs: SharedPreferences
    private var pttHardwareKey: Int = -1
    private var pttToggleEnabled = false
    private var isPttPressed = false
    private var progressDialog: ProgressDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("AM2_PREFS", Context.MODE_PRIVATE)
        pttHardwareKey = prefs.getInt("ptt_key", -1)
        pttToggleEnabled = prefs.getBoolean("ptt_toggle", false)

        audioDeviceManager = AudioDeviceManager(this)

        setupRecyclerView()

        WebSocketManager.usersOnline.observe(this) { users ->
            updateUserList(users)
        }

        WebSocketManager.activeSpeakersList.observe(this) {
            adapter?.notifyItemRangeChanged(0, adapter?.itemCount ?: 0, "PAYLOAD_SPEAKING")
        }

        WebSocketManager.ptpTargetId.observe(this) {
            WebSocketManager.usersOnline.value?.let { updateUserList(it) }
        }

        WebSocketManager.navigateToVideo.observe(this) { navigate ->
            if (navigate) {
                WebSocketManager.resetNavigation()
                val intent = Intent(this, VideoActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                startActivity(intent)
                finish()
            }
        }

        WebSocketManager.ptpHandshakeEvent.observe(this) { event ->
            when (event) {
                is WebSocketManager.PtpHandshakeEvent.Requesting -> {
                    showLoading("Memanggil ${event.userName}...")
                }
                is WebSocketManager.PtpHandshakeEvent.Failed -> {
                    hideLoading()
                    Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
                    WebSocketManager.clearPtpHandshakeEvent()
                }
                is WebSocketManager.PtpHandshakeEvent.Success -> {
                    hideLoading()
                    WebSocketManager.clearPtpHandshakeEvent()
                    if (WebSocketManager.isPtpVideo.value == false) {
                        val intent = Intent(this, MainActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        startActivity(intent)
                        finish()
                    }
                }
                null -> {}
            }
        }
    }

    private fun showLoading(message: String) {
        if (progressDialog == null) {
            progressDialog = ProgressDialog(this)
            progressDialog?.setCancelable(true)
            progressDialog?.setOnCancelListener {
                WebSocketManager.endPtp()
            }
        }
        progressDialog?.setMessage(message)
        if (!progressDialog!!.isShowing) progressDialog?.show()
    }

    private fun hideLoading() {
        progressDialog?.dismiss()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (pttHardwareKey != -1 && keyCode == pttHardwareKey) {
            val isRxOnly = WebSocketManager.isRxOnly.value == true
            val isPtpActive = WebSocketManager.ptpTargetId.value != null
            val voxEnabled = prefs.getBoolean("vox_enabled", false)

            if ((isRxOnly || voxEnabled) && !isPtpActive) {
                if (event?.repeatCount == 0) {
                    val msg = if (voxEnabled) "Mode VOX Aktif: Tombol PTT Dinonaktifkan" else "Mode RX Only"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                }
                return true
            }

            val canTalk = !WebSocketManager.currentChannelSlug.isNullOrEmpty() || isPtpActive
            if (event?.repeatCount == 0 && canTalk) {
                if (pttToggleEnabled) {
                    if (!isPttPressed) {
                        isPttPressed = true
                        startPtt()
                    } else {
                        isPttPressed = false
                        stopPtt()
                    }
                } else {
                    isPttPressed = true
                    startPtt()
                }

                if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, 0)
                }
                return true
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (pttHardwareKey != -1 && keyCode == pttHardwareKey) {
            val isRxOnly = WebSocketManager.isRxOnly.value == true
            val isPtpActive = WebSocketManager.ptpTargetId.value != null

            if ((isRxOnly || prefs.getBoolean("vox_enabled", false)) && !isPtpActive) return true

            if (!pttToggleEnabled) {
                isPttPressed = false
                stopPtt()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun startPtt() {
        val intent = Intent(this, PTTService::class.java).apply {
            action = PTTService.ACTION_START_PTT
        }
        startService(intent)
    }

    private fun stopPtt() {
        val intent = Intent(this, PTTService::class.java).apply {
            action = PTTService.ACTION_STOP_PTT
        }
        startService(intent)
    }

    private fun setupRecyclerView() {
        binding.rvUsers.layoutManager = LinearLayoutManager(this)
        adapter = UserAdapter(
            onAudioClick = { user -> handleAudioClick(user) },
            onVideoClick = { user -> handleVideoClick(user) }
        )
        binding.rvUsers.adapter = adapter
    }

    private fun handleAudioClick(user: JSONObject) {
        val userId = user.optString("id")
        val userName = user.optString("name")
        WebSocketManager.startPtpWith(userId, userName)
    }

    private fun handleVideoClick(user: JSONObject) {
        val userId = user.optString("id")
        val userName = user.optString("name")
        WebSocketManager.startPtpVideoWith(userId, userName)
    }

    private fun updateUserList(users: JSONArray) {
        val filteredUsers = JSONArray()
        val myId = WebSocketManager.myUserId
        val currentPtpId = WebSocketManager.ptpTargetId.value

        for (i in 0 until users.length()) {
            val user = users.optJSONObject(i) ?: continue
            val userId = user.optString("id")
            if (userId != myId && userId != currentPtpId) {
                filteredUsers.put(user)
            }
        }
        adapter?.setUsers(filteredUsers)
        supportActionBar?.title = "Personel Online (${filteredUsers.length()})"
    }

    override fun onResume() {
        super.onResume()
        audioDeviceManager?.start(object : AudioDeviceManager.OnDeviceChangeListener {
            override fun onDeviceChanged(deviceType: String) {}
        })
        pttHardwareKey = prefs.getInt("ptt_key", -1)
        pttToggleEnabled = prefs.getBoolean("ptt_toggle", false)
    }

    override fun onPause() {
        super.onPause()
        audioDeviceManager?.stop()
        hideLoading()
    }

    class UserAdapter(
        private val onAudioClick: (JSONObject) -> Unit,
        private val onVideoClick: (JSONObject) -> Unit
    ) : RecyclerView.Adapter<UserAdapter.ViewHolder>() {

        private var users = JSONArray()

        fun setUsers(data: JSONArray) {
            this.users = data
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_user, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = users.optJSONObject(position) ?: return
            val name = user.optString("name")
            holder.tvName.text = name

            val isSpeaking = WebSocketManager.activeSpeakersList.value?.contains(name) == true
            holder.imgIcon.setColorFilter(if (isSpeaking) Color.RED else Color.parseColor("#BBBBBB"))

            val myPtp = WebSocketManager.isPtpEnabled.value == true
            val myVideo = WebSocketManager.isVideoEnabled.value == true
            val targetPtp = user.optBoolean("enable_p2p", true)
            val targetVideo = user.optBoolean("enable_ptt_video", false)

            val canAudio = myPtp && targetPtp
            val canVideo = myPtp && myVideo && targetPtp && targetVideo

            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true
            holder.itemView.setBackgroundResource(R.drawable.menu_item_background)

            holder.itemView.setOnClickListener {
                if (canAudio) onAudioClick(user)
            }

            holder.layoutUserArea.isFocusable = false
            holder.layoutUserArea.isClickable = false

            holder.ivAudioCall.isFocusable = canAudio
            holder.ivAudioCall.isEnabled = canAudio
            holder.ivAudioCall.alpha = if (canAudio) 1.0f else 0.2f
            if (canAudio) {
                holder.ivAudioCall.setOnClickListener { onAudioClick(user) }
            } else {
                holder.ivAudioCall.setOnClickListener(null)
            }

            holder.ivVideoCall.isFocusable = canVideo
            holder.ivVideoCall.isEnabled = canVideo
            holder.ivVideoCall.alpha = if (canVideo) 1.0f else 0.2f
            if (canVideo) {
                holder.ivVideoCall.setOnClickListener { onVideoClick(user) }
            } else {
                holder.ivVideoCall.setOnClickListener(null)
            }
        }

        override fun getItemCount(): Int = users.length()

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val layoutUserArea: View = view.findViewById(R.id.layoutUserArea)
            val tvName: TextView = view.findViewById(R.id.tvUserName)
            val imgIcon: ImageView = view.findViewById(R.id.imgUserIcon)
            val ivAudioCall: ImageView = view.findViewById(R.id.ivAudioCall)
            val ivVideoCall: ImageView = view.findViewById(R.id.ivVideoCall)
        }
    }
}
