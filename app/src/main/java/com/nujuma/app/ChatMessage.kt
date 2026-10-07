package com.nujuma.app

data class ChatMessage(
    val message: String,
    val isUser: Boolean,
    val codeBlock: String? = null
)
