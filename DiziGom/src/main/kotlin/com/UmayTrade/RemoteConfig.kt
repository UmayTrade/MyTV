
package com.UmayTrade
import com.lagradost.cloudstream3.app
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.runBlocking

object RemoteConfig {
    private const val DOMAINS_URL = "https://raw.githubusercontent.com/neoser1984/cloudstream-extensions/main/domains.json"

    private var cache: Map<String, String>? = null

    private fun fetch(): Map<String, String> {
        cache?.let { return it }

        return try {
            val json = runBlocking { app.get(DOMAINS_URL, timeout = 8_000L).text }
            val map  = jacksonObjectMapper().readValue<Map<String, String>>(json)
            if (map.isNotEmpty()) cache = map
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * @param key      domains.json içindeki anahtar (örn: "dizigom")
     * @param fallback domains.json'a ulaşılamazsa kullanılacak varsayılan adres
     */
    fun getDomain(key: String, fallback: String): String {
        val remote = fetch()[key]?.trim()?.trimEnd('/')
        return if (!remote.isNullOrBlank()) remote else fallback
    }
}
