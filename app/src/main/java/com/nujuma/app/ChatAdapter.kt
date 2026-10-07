package com.nujuma.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter(
    private val messages: List<ChatMessage>,
    private val onPlayTts: (String) -> Unit
) : RecyclerView.Adapter<ChatAdapter.ChatViewHolder>() {

    class ChatViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val bubbleLayout: LinearLayout = view.findViewById(R.id.bubbleLayout)
        val tvMessage: TextView = view.findViewById(R.id.tvMessage)
        val codeBlockContainer: LinearLayout = view.findViewById(R.id.codeBlockContainer)
        val tvCode: TextView = view.findViewById(R.id.tvCode)
        val btnCopyCode: Button = view.findViewById(R.id.btnCopyCode)
        val btnPlayTts: ImageButton = view.findViewById(R.id.btnPlayTts)
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

        if (!msg.codeBlock.isNullOrEmpty()) {
            holder.codeBlockContainer.visibility = View.VISIBLE
            holder.tvCode.text = msg.codeBlock
            holder.btnCopyCode.setOnClickListener {
                val clipboard = holder.itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Code", msg.codeBlock)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(holder.itemView.context, "Kode disalin!", Toast.LENGTH_SHORT).show()
            }
        } else {
            holder.codeBlockContainer.visibility = View.GONE
        }

        holder.btnPlayTts.setOnClickListener {
            val fullText = msg.message + (msg.codeBlock ?: "")
            onPlayTts(fullText)
        }
    }

    override fun getItemCount(): Int = messages.size
}
