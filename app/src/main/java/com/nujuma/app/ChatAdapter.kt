package com.nujuma.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private var playingMessage: ChatMessage?,
    private val onPlayTts: (ChatMessage) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubbleLayout: LinearLayout = view.findViewById(R.id.bubbleLayout)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val codeBlockContainer: LinearLayout = view.findViewById(R.id.codeBlockContainer)
        val btnPlayTts: ImageButton = view.findViewById(R.id.btnPlayTts)
    }

    fun updatePlayingState(message: ChatMessage?) {
        playingMessage = message
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChatViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChatViewHolder, position: Int) {
        val msg = messages[position]
        holder.tvMessage.text = msg.message

        val params = holder.bubbleLayout.layoutParams as LinearLayout.LayoutParams
        if (msg.isUser) {
            params.gravity = Gravity.END
            holder.bubbleLayout.setBackgroundResource(R.color.chat_user_bg)
        } else {
            params.gravity = Gravity.START
            holder.bubbleLayout.setBackgroundResource(R.color.chat_bot_bg)
        }
        holder.bubbleLayout.layoutParams = params

        holder.codeBlockContainer.removeAllViews()
        if (msg.codeFiles.isNotEmpty()) {
            holder.codeBlockContainer.visibility = View.VISIBLE
            val context = holder.itemView.context

            for (file in msg.codeFiles) {
                val fileBtn = Button(context).apply {
                    text = "📄 ${file.fileName} (Tahan untuk Opsi)"
                    isAllCaps = false
                    setOnClickListener {
                        showCodeDialog(context, file.fileName, file.content)
                    }
                    setOnLongClickListener { view ->
                        val popup = PopupMenu(context, view)
                        popup.menu.add("Tampilkan Kode")
                        popup.menu.add("Download ke Folder HP")
                        popup.setOnMenuItemClickListener { item ->
                            when (item.title) {
                                "Tampilkan Kode" -> showCodeDialog(context, file.fileName, file.content)
                                "Download ke Folder HP" -> saveFileToDownloads(context, file.fileName, file.content)
                            }
                            true
                        }
                        popup.show()
                        true
                    }
                }
                holder.codeBlockContainer.addView(fileBtn)
            }
        } else {
            holder.codeBlockContainer.visibility = View.GONE
        }

        if (playingMessage == msg) {
            holder.btnPlayTts.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            holder.btnPlayTts.setImageResource(android.R.drawable.ic_media_play)
        }

        holder.btnPlayTts.setOnClickListener {
            onPlayTts(msg)
        }
    }

    private fun showCodeDialog(context: Context, fileName: String, codeContent: String) {
        AlertDialog.Builder(context)
            .setTitle(fileName)
            .setMessage(codeContent)
            .setPositiveButton("Tutup", null)
            .setNeutralButton("Salin") { _, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Code", codeContent)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Kode disalin!", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun saveFileToDownloads(context: Context, fileName: String, content: String) {
        try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
                Toast.makeText(context, "$fileName tersimpan di folder Download!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Gagal membuat file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal menyimpan: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun getItemCount(): Int = messages.size
}
