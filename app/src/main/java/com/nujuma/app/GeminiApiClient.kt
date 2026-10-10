package com.nujuma.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

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

                val parts = JSONArray().put(JSONObject().put("text", fullText))
                contents.put(JSONObject().put("role", roleStr).put("parts", parts))
            }

            jsonBody.put("contents", contents)

            // Mengaktifkan fitur Search Grounding agar informasi selalu up-to-date
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
