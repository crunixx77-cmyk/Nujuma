package com.nujuma.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val RECORD_AUDIO_PERMISSION_CODE = 101

    private lateinit var accountManager: AccountManager
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    
    private var selectedModel = "gemini-1.5-flash"
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    private lateinit var tvProfileName: TextView
    private lateinit var tvModelName: TextView
    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnMic: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        accountManager = AccountManager(this)
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        tvProfileName = findViewById(R.id.tvProfileName)
        tvModelName = findViewById(R.id.tvModelName)
        rvChat = findViewById(R.id.rvChat)
        etInput = findViewById(R.id.etInput)
        btnMic = findViewById(R.id.btnMic)

        chatAdapter = ChatAdapter(messages) { textToSpeak ->
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, null)
        }
        rvChat.layoutManager = LinearLayoutManager(this)
        rvChat.adapter = chatAdapter

        findViewById<LinearLayout>(R.id.profileArea).setOnClickListener {
            showModelSelectionDialog()
        }

        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener { view ->
            showPopupMenu(view)
        }

        findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            handleSendMessage()
        }

        findViewById<ImageButton>(R.id.btnAttach).setOnClickListener {
            Toast.makeText(this, "Membuka File Manager...", Toast.LENGTH_SHORT).show()
        }

        setupMicGesture()
        checkAuthAndInit()
    }

    private fun checkAuthAndInit() {
        if (accountManager.getActiveApiKey() == null) {
            showApiKeyDialog(isMandatory = true)
        } else {
            updateProfileUI()
        }
    }

    private fun updateProfileUI() {
        tvProfileName.text = accountManager.getActiveName()
        tvModelName.text = selectedModel
    }

    private fun showApiKeyDialog(isMandatory: Boolean) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Nujuma - Setup Akun")
        builder.setCancelable(!isMandatory)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 16, 32, 16)

        val etName = EditText(this)
        etName.hint = "Nama Profil"
        layout.addView(etName)

        val etKey = EditText(this)
        etKey.hint = "API Key Google AI Studio"
        layout.addView(etKey)

        builder.setView(layout)
        builder.setPositiveButton("Verifikasi & Simpan", null)

        val dialog = builder.create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text.toString().trim()
            val key = etKey.text.toString().trim()

            if (name.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "Isi semua kolom!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Toast.makeText(this, "Memverifikasi API Key...", Toast.LENGTH_SHORT).show()
            thread {
                val isValid = GeminiApiClient.validateApiKey(key)
                runOnUiThread {
                    if (isValid) {
                        accountManager.saveAccount(name, key)
                        updateProfileUI()
                        dialog.dismiss()
                        Toast.makeText(this, "Berhasil masuk!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "API Key TIDAK VALID! Coba lagi.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun showModelSelectionDialog() {
        val apiKey = accountManager.getActiveApiKey() ?: return
        Toast.makeText(this, "Memuat daftar model...", Toast.LENGTH_SHORT).show()
        thread {
            val models = GeminiApiClient.getActiveModels(apiKey)
            runOnUiThread {
                val builder = AlertDialog.Builder(this)
                builder.setTitle("Pilih Model Gemini (Nujuma)")
                builder.setItems(models.toTypedArray()) { _, which ->
                    selectedModel = models[which]
                    tvModelName.text = selectedModel
                    Toast.makeText(this, "Model diganti: $selectedModel", Toast.LENGTH_SHORT).show()
                }
                builder.show()
            }
        }
    }

    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add("Obrolan Baru")
        popup.menu.add("Riwayat Obrolan")
        popup.menu.add("Tambah / Ganti Akun")
        popup.menu.add("Pengaturan Suara (TTS)")

        popup.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Obrolan Baru" -> {
                    messages.clear()
                    chatAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "Sesi obrolan baru dimulai", Toast.LENGTH_SHORT).show()
                }
                "Riwayat Obrolan" -> {
                    Toast.makeText(this, "Riwayat obrolan tersimpan", Toast.LENGTH_SHORT).show()
                }
                "Tambah / Ganti Akun" -> {
                    showAccountManagerDialog()
                }
                "Pengaturan Suara (TTS)" -> {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                }
            }
            true
        }
        popup.show()
    }

    private fun showAccountManagerDialog() {
        val accounts = accountManager.getAllAccounts()
        val names = accounts.map { it.name }.toMutableList()
        names.add("+ Tambah Akun Baru")

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Kelola Akun Nujuma")
        builder.setItems(names.toTypedArray()) { _, which ->
            if (which == names.size - 1) {
                showApiKeyDialog(isMandatory = false)
            } else {
                accountManager.setActiveAccount(names[which])
                updateProfileUI()
                Toast.makeText(this, "Ganti ke akun: ${names[which]}", Toast.LENGTH_SHORT).show()
            }
        }
        builder.show()
    }

    private fun handleSendMessage() {
        val text = etInput.text.toString().trim()
        val apiKey = accountManager.getActiveApiKey()
        if (text.isEmpty() || apiKey == null) return

        messages.add(ChatMessage(text, isUser = true))
        chatAdapter.notifyItemInserted(messages.size - 1)
        rvChat.scrollToPosition(messages.size - 1)
        etInput.setText("")

        thread {
            val rawResponse = GeminiApiClient.sendMessage(apiKey, selectedModel, text)
            
            var textPart = rawResponse
            var codePart: String? = null

            val regex = Regex("```(?:[a-zA-Z]+)?\\n?([\\s\\S]*?)```")
            val match = regex.find(rawResponse)

            if (match != null) {
                codePart = match.groupValues[1].trim()
                textPart = rawResponse.replace(match.value, "").trim()
            }

            runOnUiThread {
                messages.add(
                    ChatMessage(
                        if (textPart.isEmpty()) "Kode:" else textPart,
                        isUser = false,
                        codeBlock = codePart
                    )
                )
                chatAdapter.notifyItemInserted(messages.size - 1)
                rvChat.scrollToPosition(messages.size - 1)
            }
        }
    }

    private fun setupMicGesture() {
        btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    checkPermissionAndStartVoice()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    speechRecognizer?.stopListening()
                    true
                }
                else -> false
            }
        }
    }

    private fun checkPermissionAndStartVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        } else {
            startVoiceRecognition()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Izin mikrofon diberikan. Tekan kembali untuk rekam.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Izin mikrofon diperlukan untuk fitur rekaman suara.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    etInput.setText(matches[0])
                    handleSendMessage()
                }
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale("id", "ID")
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
