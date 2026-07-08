package com.videotyper.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved SMB connection. [host] is the server name or IP; credentials are optional (blank user =
 * guest). Passwords are stored in plaintext SharedPreferences — acceptable for a personal-device
 * kids' app, but not a secret vault.
 */
data class SmbServer(
    val id: String,
    val label: String,
    val host: String,
    val username: String,
    val password: String,
    val domain: String,
) {
    /** Credential-free browse root, e.g. smb://host/ (listing here shows the server's shares). */
    fun rootUrl(): String = "smb://$host/"
}

class SmbServersStore(context: Context) {
    private val prefs = context.getSharedPreferences("videotyper", Context.MODE_PRIVATE)

    fun servers(): List<SmbServer> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                SmbServer(
                    id = o.getString("id"),
                    label = o.optString("label", o.getString("host")),
                    host = o.getString("host"),
                    username = o.optString("username", ""),
                    password = o.optString("password", ""),
                    domain = o.optString("domain", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Add or replace (matched by id) and persist. */
    fun save(server: SmbServer) {
        val updated = servers().filterNot { it.id == server.id } + server
        write(updated)
    }

    fun delete(id: String) {
        write(servers().filterNot { it.id == id })
    }

    private fun write(list: List<SmbServer>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("label", it.label)
                    .put("host", it.host)
                    .put("username", it.username)
                    .put("password", it.password)
                    .put("domain", it.domain)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "smb_servers"
    }
}
