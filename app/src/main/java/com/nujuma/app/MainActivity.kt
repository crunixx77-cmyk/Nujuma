package com.nujuma.app

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread

data class CodeFile(
    val fileName: String,
    val content: String,
    val language: String
)

data class ChatMessage(
    val message: String,
    val isUser: Boolean,
    val codeFiles: List<CodeFile> = emptyList(),
    val attachmentBase64: String? = null,
    val attachmentMimeType: String? = null
)

data class Account(val name: String, val apiKey: String)

class AccountManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("nujuma_accounts", Context.MODE_PRIVATE)

    fun getAllAccounts(): List<Account> {
        val jsonStr = prefs.getString("accounts_list", "[]")
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Account>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(Account(obj.getString("name"), obj.getString("apiKey")))
        }
        return list
    }

    fun getActiveApiKey(): String? {
        val activeName = prefs.getString("active_account", null) ?: return getAllAccounts().firstOrNull()?.apiKey
        return getAllAccounts().find { it.name == activeName }?.apiKey
    }

    fun getActiveName(): String {
        return prefs.getString("active_account", null) ?: getAllAccounts().firstOrNull()?.name ?: "Default User"
    }

    fun saveAccount(name: String, apiKey: String) {
        val accounts = getAllAccounts().toMutableList()
        val existingIndex = accounts.indexOfFirst { it.name == name }
        if (existingIndex >= 0) {
            accounts[existingIndex] = Account(name, apiKey)
        } else {
            accounts.add(Account(name, apiKey))
        }
        saveAccountsList(accounts)
        setActiveAccount(name)
    }

    fun setActiveAccount(name: String) {
        prefs.edit().putString("active_account", name).apply()
    }

    private fun saveAccountsList(accounts: List<Account>) {
        val array = JSONArray()
        for (acc in accounts) {
            val obj = JSONObject()
            obj.put("name", acc.name)
            obj.put("apiKey", acc.apiKey)
            array.put(obj)
        }
        prefs.edit().putString("accounts_list", array.toString()).apply()
    }
}

object GeminiApiClient {
    fun validateApiKey(apiKey: String): Boolean {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.responseCode == 200
        } catch (e: Exception) {
            false
        }
    }

    fun getActiveModels(apiKey: String): List<String> {
        val models = mutableListOf<String>()
        try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                val jsonResponse = JSONObject(sb.toString())
                val modelsArray = jsonResponse.getJSONArray("models")
                for (i in 0 until modelsArray.length()) {
                    val obj = modelsArray.getJSONObject(i)
                    val name = obj.getString("name").replace("models/", "")
                    val methods = obj.optJSONArray("supportedGenerationMethods")
                    var supportsGenerateContent = false
                    if (methods != null) {
                        for (j in 0 until methods.length()) {
                            if (methods.getString(j) == "generateContent") {
                                supportsGenerateContent = true
                                break
                            }
                        }
                    }
                    if (supportsGenerateContent) {
                        models.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            models.add("gemini-1.5-flash")
        }
        return if (models.isEmpty()) listOf("gemini-1.5-flash") else models
    }

    fun sendMessage(apiKey: String, model: String, history: List<ChatMessage>): String {
        return try {
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val jsonBody = JSONObject()
            val contents = JSONArray()

            for (msg in history) {
                val roleStr = if (msg.isUser) "user" else "model"
                val codeTexts = msg.codeFiles.joinToString("\n") { "```${it.language}\n// filename: ${it.fileName}\n${it.content}\n```" }
                val fullText = msg.message + if (codeTexts.isNotEmpty()) "\n$codeTexts" else ""

                val parts = JSONArray()
                if (!msg.attachmentBase64.isNullOrEmpty() && !msg.attachmentMimeType.isNullOrEmpty()) {
                    val inlineData = JSONObject()
                        .put("mime_type", msg.attachmentMimeType)
                        .put("data", msg.attachmentBase64)
                    parts.put(JSONObject().put("inline_data", inlineData))
                }

                parts.put(JSONObject().put("text", fullText))
                contents.put(JSONObject().put("role", roleStr).put("parts", parts))
            }

            jsonBody.put("contents", contents)

            val toolsArray = JSONArray().put(JSONObject().put("googleSearch", JSONObject()))
            jsonBody.put("tools", toolsArray)

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(jsonBody.toString())
            writer.flush()
            writer.close()

            if (conn.responseCode == 200) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()
                val jsonResponse = JSONObject(sb.toString())
                val candidates = jsonResponse.getJSONArray("candidates")
                candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            } else {
                val errorStream = conn.errorStream
                val errorMsg = if (errorStream != null) BufferedReader(InputStreamReader(errorStream)).readText() else ""
                "Error: Kode Respon ${conn.responseCode} - $errorMsg"
            }
        } catch (e: Exception) {
            "Error: ${e.localizedMessage}"
        }
    }
}

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private var playingMessage: ChatMessage?,
    private val onTtsClicked: (ChatMessage) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    fun updatePlayingState(msg: ChatMessage?) {
        playingMessage = msg
        notifyDataSetChanged()
    }

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val tvCodeInfo: TextView = view.findViewById(R.id.tvCodeInfo)
        val btnPlayTts: ImageButton = view.findViewById(R.id.btnPlayTts)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) 1 else 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val layoutRes = if (viewType == 1) R.layout.item_chat_user else R.layout.item_chat_ai
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        holder.tvMessage.text = msg.message

        if (msg.codeFiles.isNotEmpty()) {
            holder.tvCodeInfo.visibility = View.VISIBLE
            val fileNames = msg.codeFiles.joinToString(", ") { it.fileName }
            holder.tvCodeInfo.text = "Lampiran Kode: $fileNames"
        } else {
            holder.tvCodeInfo.visibility = View.GONE
        }

        if (!msg.isUser) {
            holder.btnPlayTts.visibility = View.VISIBLE
            val isPlaying = (playingMessage == msg)
            holder.btnPlayTts.setImageResource(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            holder.btnPlayTts.setOnClickListener {
                onTtsClicked(msg)
            }
        } else {
            holder.btnPlayTts.visibility = View.GONE
        }
    }

    override fun getItemCount() = messages.size
}

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private val RECORD_AUDIO_PERMISSION_CODE = 101
    private lateinit var accountManager: AccountManager
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var selectedModel = "gemini-1.5-flash"
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var playingMessage: ChatMessage? = null
    private var isFromVoiceInput = false

    private lateinit var tvProfileName: TextView
    private lateinit var tvModelName: TextView
    private lateinit var rvChat: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnMic: ImageButton

    private var currentAttachmentBase64: String? = null
    private var currentAttachmentMimeType: String? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            try {
                val mimeType = contentResolver.getType(it) ?: "*/*"
                if (mimeType.startsWith("image/") || mimeType.startsWith("audio/")) {
                    contentResolver.openInputStream(it)?.use { inputStream ->
                        val bytes = inputStream.readBytes()
                        currentAttachmentBase64 = Base64.encodeToString(bytes, Base64.DEFAULT)
                        currentAttachmentMimeType = mimeType
                        val typeName = if (mimeType.startsWith("image/")) "Gambar" else "Audio"
                        val currentText = etInput.text.toString()
                        val newText = if (currentText.isEmpty()) "[$typeName terlampir]" else "$currentText\n[$typeName terlampir]"
                        etInput.setText(newText)
                        Toast.makeText(this, "$typeName berhasil dilampirkan!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    contentResolver.openInputStream(it)?.use { inputStream ->
                        val contentText = inputStream.bufferedReader().readText()
                        val currentText = etInput.text.toString()
                        val newText = if (currentText.isEmpty()) contentText else "$currentText\n$contentText"
                        etInput.setText(newText)
                        Toast.makeText(this, "File teks berhasil dilampirkan!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Gagal membaca file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

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

        chatAdapter = ChatAdapter(messages, playingMessage) { msg ->
            toggleTts(msg)
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
            handleSendMessage(isVoice = false)
        }

        findViewById<ImageButton>(R.id.btnAttach).setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        setupMicGesture()
        checkAuthAndInit()
    }

    private fun stopAllTts() {
        try {
            if (tts?.isSpeaking == true) {
                tts?.stop()
            }
        } catch (e: Exception) {}

        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null
        } catch (e: Exception) {}

        playingMessage = null
        chatAdapter.updatePlayingState(null)
    }

    private fun toggleTts(msg: ChatMessage) {
        if (playingMessage == msg && (tts?.isSpeaking == true || mediaPlayer?.isPlaying == true)) {
            stopAllTts()
        } else {
            stopAllTts()
            playingMessage = msg
            chatAdapter.updatePlayingState(msg)

            val textToSpeak = msg.message
            val prefs = getSharedPreferences("nujuma_prefs", MODE_PRIVATE)
            val ttsMode = prefs.getString("tts_mode", "builtin") ?: "builtin"

            if (ttsMode == "builtin") {
                val params = Bundle()
                params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "nujuma_tts")
                tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, "nujuma_tts")
            } else {
                val gender = if (ttsMode == "serverless_cowok") "cowok" else "cewek"
                val encodedText = URLEncoder.encode(textToSpeak, "UTF-8")
                val urlString = "https://tts-phi-sand.vercel.app/tts?text=$encodedText&gender=$gender"

                thread {
                    try {
                        val mp = MediaPlayer().apply {
                            setDataSource(urlString)
                            prepare()
                            start()
                            setOnCompletionListener {
                                runOnUiThread {
                                    if (playingMessage == msg) {
                                        playingMessage = null
                                        chatAdapter.updatePlayingState(null)
                                    }
                                    release()
                                    mediaPlayer = null
                                }
                            }
                        }
                        mediaPlayer = mp
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "Gagal memutar TTS Serverless: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                            playingMessage = null
                            chatAdapter.updatePlayingState(null)
                        }
                    }
                }
            }
        }
    }

    private fun saveCurrentSession() {
        if (messages.isEmpty()) return
        val prefs = getSharedPreferences("nujuma_chat_history", MODE_PRIVATE)
        val array = JSONArray()
        for (msg in messages) {
            val obj = JSONObject()
            obj.put("message", msg.message)
            obj.put("isUser", msg.isUser)
            val filesArray = JSONArray()
            for (f in msg.codeFiles) {
                val fObj = JSONObject()
                fObj.put("fileName", f.fileName)
                fObj.put("content", f.content)
                fObj.put("language", f.language)
                filesArray.put(fObj)
            }
            obj.put("codeFiles", filesArray)
            if (msg.attachmentBase64 != null) {
                obj.put("attachmentBase64", msg.attachmentBase64)
                obj.put("attachmentMimeType", msg.attachmentMimeType)
            }
            array.put(obj)
        }
        val sessionId = "Sesi ${System.currentTimeMillis()}"
        prefs.edit().putString(sessionId, array.toString()).apply()
    }

    private fun showHistoryDialog() {
        val prefs = getSharedPreferences("nujuma_chat_history", MODE_PRIVATE)
        val keys = prefs.all.keys.toList()
        if (keys.isEmpty()) {
            Toast.makeText(this, "Belum ada riwayat tersimpan", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Riwayat Obrolan")
        builder.setItems(keys.toTypedArray()) { _, which ->
            val selectedKey = keys[which]
            val jsonStr = prefs.getString(selectedKey, null) ?: return@setItems
            val array = JSONArray(jsonStr)
            messages.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val filesArray = obj.optJSONArray("codeFiles")
                val codeFiles = mutableListOf<CodeFile>()
                if (filesArray != null) {
                    for (j in 0 until filesArray.length()) {
                        val fObj = filesArray.getJSONObject(j)
                        codeFiles.add(
                            CodeFile(
                                fileName = fObj.getString("fileName"),
                                content = fObj.getString("content"),
                                language = fObj.getString("language")
                            )
                        )
                    }
                }
                val attBase64 = if (obj.has("attachmentBase64")) obj.getString("attachmentBase64") else null
                val attMime = if (obj.has("attachmentMimeType")) obj.getString("attachmentMimeType") else null

                messages.add(
                    ChatMessage(
                        message = obj.getString("message"),
                        isUser = obj.getBoolean("isUser"),
                        codeFiles = codeFiles,
                        attachmentBase64 = attBase64,
                        attachmentMimeType = attMime
                    )
                )
            }
            chatAdapter.notifyDataSetChanged()
            Toast.makeText(this, "Riwayat berhasil dimuat!", Toast.LENGTH_SHORT).show()
        }
        builder.show()
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

    private fun showTtsSettingsDialog() {
        val prefs = getSharedPreferences("nujuma_prefs", MODE_PRIVATE)
        val currentMode = prefs.getString("tts_mode", "builtin") ?: "builtin"

        val options = arrayOf("TTS Bawaan HP", "TTS Serverless (Cewek)", "TTS Serverless (Cowok)")
        val modeKeys = arrayOf("builtin", "serverless_cewek", "serverless_cowok")
        val currentIndex = modeKeys.indexOf(currentMode).coerceAtLeast(0)

        val builder = AlertDialog.Builder(this)
        builder.setTitle("Pengaturan Suara (TTS)")
        builder.setSingleChoiceItems(options, currentIndex) { dialog, which ->
            val chosenMode = modeKeys[which]
            prefs.edit().putString("tts_mode", chosenMode).apply()
            Toast.makeText(this, "TTS disetel ke: ${options[which]}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        builder.show()
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
                    saveCurrentSession()
                    messages.clear()
                    chatAdapter.notifyDataSetChanged()
                    Toast.makeText(this, "Sesi disimpan & obrolan baru dimulai", Toast.LENGTH_SHORT).show()
                }
                "Riwayat Obrolan" -> {
                    showHistoryDialog()
                }
                "Tambah / Ganti Akun" -> {
                    showAccountManagerDialog()
                }
                "Pengaturan Suara (TTS)" -> {
                    showTtsSettingsDialog()
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

    private fun handleSendMessage(isVoice: Boolean = false) {
        val text = etInput.text.toString().trim()
        val apiKey = accountManager.getActiveApiKey()
        if ((text.isEmpty() && currentAttachmentBase64 == null) || apiKey == null) return

        isFromVoiceInput = isVoice

        val userMessage = ChatMessage(
            message = text,
            isUser = true,
            attachmentBase64 = currentAttachmentBase64,
            attachmentMimeType = currentAttachmentMimeType
        )
        messages.add(userMessage)
        chatAdapter.notifyItemInserted(messages.size - 1)
        rvChat.scrollToPosition(messages.size - 1)
        etInput.setText("")
        currentAttachmentBase64 = null
        currentAttachmentMimeType = null

        showThinkingIndicator()

        val historyToSend = messages.filter { it.message != "Nujuma sedang berpikir..." }

        thread {
            val rawResponse = GeminiApiClient.sendMessage(apiKey, selectedModel, historyToSend)

            val codeBlockRegex = Regex("```(\\w+)?\\n([\\s\\S]*?)```")
            val matches = codeBlockRegex.findAll(rawResponse)
            val fileList = mutableListOf<CodeFile>()

            for (match in matches) {
                val lang = match.groupValues[1].ifEmpty { "txt" }
                val fullContent = match.groupValues[2].trim()
                val lines = fullContent.lines().toMutableList()
                var fileName: String? = null

                val checkLimit = minOf(3, lines.size)
                for (i in 0 until checkLimit) {
                    val line = lines[i].trim()
                    val fileNameRegex = Regex("^(?:--|//|#|/\\*)\\s*filename:\\s*([\\w.-]+)", RegexOption.IGNORE_CASE)
                    val nameMatch = fileNameRegex.find(line)
                    if (nameMatch != null) {
                        fileName = nameMatch.groupValues[1]
                        lines.removeAt(i)
                        break
                    }
                }
                val cleanedContent = lines.joinToString("\n").trim()
                val finalFileName = fileName ?: "file_${System.currentTimeMillis()}.$lang"
                fileList.add(CodeFile(fileName = finalFileName, content = cleanedContent, language = lang))
            }

            val cleanMessage = rawResponse.replace(codeBlockRegex, "").trim()

            runOnUiThread {
                hideThinkingIndicator()

                val aiMessage = ChatMessage(
                    message = if (cleanMessage.isEmpty() && fileList.isNotEmpty()) "Kode terlampir:" else cleanMessage,
                    isUser = false,
                    codeFiles = fileList
                )
                messages.add(aiMessage)
                chatAdapter.notifyItemInserted(messages.size - 1)
                rvChat.scrollToPosition(messages.size - 1)

                if (isFromVoiceInput) {
                    toggleTts(aiMessage)
                }
            }
        }
    }

    private fun showThinkingIndicator() {
        val thinkingMsg = ChatMessage("Nujuma sedang berpikir...", isUser = false)
        messages.add(thinkingMsg)
        chatAdapter.notifyItemInserted(messages.size - 1)
        rvChat.scrollToPosition(messages.size - 1)
        rvChat.announceForAccessibility("Nujuma sedang berpikir, mohon tunggu.")
    }

    private fun hideThinkingIndicator() {
        if (messages.isNotEmpty() && messages.last().message == "Nujuma sedang berpikir...") {
            val lastIndex = messages.size - 1
            messages.removeAt(lastIndex)
            chatAdapter.notifyItemRemoved(lastIndex)
        }
    }

    private fun setupMicGesture() {
        btnMic.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    stopAllTts()
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
                    handleSendMessage(isVoice = true)
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
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    runOnUiThread {
                        playingMessage = null
                        chatAdapter.updatePlayingState(null)
                    }
                }
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        playingMessage = null
                        chatAdapter.updatePlayingState(null)
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        saveCurrentSession()
        stopAllTts()
        tts?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
