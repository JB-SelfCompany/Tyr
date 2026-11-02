package com.jbselfcompany.tyr.utils

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The public peer list the picker ranks; downloaded here so the native library needs no HTTP client. */
object PeerCatalog {
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
