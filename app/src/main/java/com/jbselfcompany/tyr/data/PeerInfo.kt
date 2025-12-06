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
