package com.nujuma.app

import android.content.Context
import android.content.SharedPreferences

data class Account(val name: String, val apiKey: String)

class AccountManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nujuma_accounts", Context.MODE_PRIVATE)

    fun saveAccount(name: String, apiKey: String) {
        prefs.edit().putString(name, apiKey).apply()
        setActiveAccount(name)
    }

    fun setActiveAccount(name: String) {
        prefs.edit().putString("ACTIVE_ACCOUNT", name).apply()
    }

    fun getActiveApiKey(): String? {
        val activeName = prefs.getString("ACTIVE_ACCOUNT", null) ?: return null
        return prefs.getString(activeName, null)
    }

    fun getActiveName(): String {
        return prefs.getString("ACTIVE_ACCOUNT", "Nujuma") ?: "Nujuma"
    }

    fun getAllAccounts(): List<Account> {
        val list = mutableListOf<Account>()
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            if (key != "ACTIVE_ACCOUNT" && value is String) {
                list.add(Account(key, value))
            }
        }
        return list
    }
}
