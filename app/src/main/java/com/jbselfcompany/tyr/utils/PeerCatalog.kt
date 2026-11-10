package com.jbselfcompany.tyr.utils

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The public peer list the picker ranks; downloaded here so the native library needs no HTTP client. */
object PeerCatalog {
    /** One catalog peer. `ms` is the catalog's last response time, null when it has none. */
    data class Entry(val uri: String, val country: String, val up: Boolean, val ms: Int?)

    /**
     * Catalog JSON -> peers by country, countries by name, peers online-first then fastest.
     * The catalog groups peers by its source file name ("united-kingdom.md"), which is the country.
     */
    fun byCountry(json: String): Map<String, List<Entry>> {
        val root = org.json.JSONObject(json)
        return root.keys().asSequence().associate { file ->
            val country = countryName(file)
            val peers = root.getJSONObject(file)
            country to sorted(peers.keys().asSequence().map { uri ->
                val p = peers.getJSONObject(uri)
                Entry(uri, country, p.optBoolean("up"), p.optInt("response_ms", -1).takeIf { it >= 0 })
            }.toList())
        }.toSortedMap()
    }

    /** "united-kingdom.md" -> "United Kingdom". */
    fun countryName(file: String): String =
        file.removeSuffix(".md").split('-', '_', ' ').filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** Online first, then by response time (unknown last), then by address for a stable order. */
    fun sorted(peers: List<Entry>): List<Entry> =
        peers.sortedWith(compareBy<Entry>({ !it.up }, { it.ms ?: Int.MAX_VALUE }, { it.uri }))

    private const val CATALOG_URL = "https://publicpeers.neilalexander.dev/publicnodes.json"
    private const val TIMEOUT_MS = 15_000
    private const val CACHE_MAX_AGE_MS = 7 * 24 * 3600 * 1000L

    /** Catalog JSON; when the download fails, a cache younger than a week. Blocking. */
    fun fetch(context: Context): String {
        val cache = File(context.filesDir, "publicnodes.json")
        return try {
            val conn = URL(CATALOG_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            try {
                conn.inputStream.bufferedReader().use { it.readText() }.also { cache.writeText(it) }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS) {
                TyrLogger.w("PeerCatalog", "Peer list download failed (${e.message}), using the cache")
                cache.readText()
            } else {
                throw e
            }
        }
    }
}
