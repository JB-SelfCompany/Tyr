package com.jbselfcompany.tyr.data

import org.json.JSONObject

/**
 * Represents a peer with its configuration and state
 */
data class PeerInfo(
    val uri: String,
    val isEnabled: Boolean = true,
    val tag: PeerTag = PeerTag.CUSTOM
) {
    enum class PeerTag {
        DEFAULT,    // Default peer included with the app
        CUSTOM      // Manually added by user
    }

    /**
     * Convert to JSON for storage
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("uri", uri)
            put("enabled", isEnabled)
            put("tag", tag.name)
        }
    }

    companion object {
        /** Supported Yggdrasil peer protocols */
        val VALID_PROTOCOLS = listOf("tcp://", "tls://", "quic://", "socks://", "unix://")

        /**
         * Validate a peer URL: checks protocol prefix and basic host:port structure,
         * and rejects characters that could cause injection downstream.
         */
        fun isValidPeerUrl(url: String): Boolean {
            val protocol = VALID_PROTOCOLS.find { url.startsWith(it) } ?: return false
            val rest = url.removePrefix(protocol)
            if (rest.isBlank()) return false
            // Reject dangerous/special characters to prevent injection
            val dangerous = setOf(';', '|', '`', '$', '>', '<', '\n', '\r', '\t', '\'', '"')
            if (rest.any { it in dangerous }) return false
            if (rest.contains("..")) return false
            // Unix socket paths need only be non-empty
            if (protocol == "unix://") return true
            // Network protocols require host:port. Only the authority holds it: a query such as
            // ?key=… (pinned peer key) or a socks:// target path after it is not the port.
            val authority = rest.substringBefore('/').substringBefore('?')
            val portSeparator = authority.lastIndexOf(':')
            if (portSeparator <= 0) return false
            val port = authority.substring(portSeparator + 1).toIntOrNull() ?: return false
            return port in 1..65535
        }

        /**
         * Parse from JSON
         */
        fun fromJson(json: JSONObject): PeerInfo {
            return PeerInfo(
                uri = json.getString("uri"),
                isEnabled = json.optBoolean("enabled", true),
                tag = try {
                    // Handle migration from old "type" field to new "tag" field
                    val tagStr = json.optString("tag", "")
                    if (tagStr.isNotEmpty()) {
                        // Migration: Old MULTICAST -> CUSTOM
                        when (tagStr) {
                            "MULTICAST" -> PeerTag.CUSTOM
                            else -> PeerTag.valueOf(tagStr)
                        }
                    } else {
                        // Migration: STATIC -> CUSTOM, DISCOVERED -> CUSTOM
                        PeerTag.CUSTOM
                    }
                } catch (e: IllegalArgumentException) {
                    PeerTag.CUSTOM
                }
            )
        }
    }
}
