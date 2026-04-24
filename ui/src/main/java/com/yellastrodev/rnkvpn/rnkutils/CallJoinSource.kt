/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yellastrodev.rnkvpn.rnkutils

/**
 * CallJoinSource describes what exactly the user stored in the single input field.
 *
 * The source is either a raw citizen token that must be converted to a fresh join link
 * on every VPN start, or a ready-made join link that can be passed directly to TURN.
 */
data class CallJoinSource(
    val type: Type,
    val value: String,
) {
    /**
     * Type identifies how the stored input must be interpreted on launch.
     */
    enum class Type {
        TOKEN,
        LINK,
    }

    companion object {
        /**
         * fromUserInput converts the current input field value into a structured source.
         */
        fun fromUserInput(input: String): CallJoinSource {
            val normalized = input.trim()
            require(normalized.isNotEmpty()) { "Call join source is empty" }

            val type = if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
                Type.LINK
            } else {
                Type.TOKEN
            }
            return CallJoinSource(type, normalized)
        }

        /**
         * fromStoredValues restores a structured source from persisted type/value fields.
         */
        fun fromStoredValues(type: String?, value: String?): CallJoinSource? {
            val normalizedValue = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when (type?.trim()?.lowercase()) {
                "token" -> CallJoinSource(Type.TOKEN, normalizedValue)
                "link" -> CallJoinSource(Type.LINK, normalizedValue)
                else -> null
            }
        }
    }
}
