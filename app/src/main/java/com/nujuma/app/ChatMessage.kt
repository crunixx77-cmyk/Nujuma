package com.nujuma.app

data class CodeFile(
    val fileName: String,
    val content: String,
    val language: String
)

data class ChatMessage(
    val message: String,
    val isUser: Boolean,
    val codeFiles: List<CodeFile> = emptyList()
)
