/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yellastrodev.rnkvpn.rnkutils

import android.content.SharedPreferences
import android.util.Log

/**
 * CallJoinSourceStore persists the user's single call input as structured source metadata.
 *
 * It keeps the original intent intact: token sources remain tokens, and direct links remain links.
 * The old `vk_link` preference is supported only as a one-time migration source.
 */
class CallJoinSourceStore(
    private val preferences: SharedPreferences,
) {
    /**
     * load restores the current source from preferences, including one-time migration from `vk_link`.
     */
    fun load(): CallJoinSource? {
        val stored = CallJoinSource.fromStoredValues(
            preferences.getString(KEY_SOURCE_TYPE, null),
            preferences.getString(KEY_SOURCE_VALUE, null),
        )
        if (stored != null) {
            Log.d(TAG, "[load] Загружен источник звонка типа ${stored.type}")
            return stored
        }

        val legacyValue = preferences.getString(KEY_LEGACY_VK_LINK, null)?.trim().orEmpty()
        if (legacyValue.isEmpty()) {
            Log.d(TAG, "[load] Источник звонка в памяти не найден")
            return null
        }

        val migrated = CallJoinSource.fromUserInput(legacyValue)
        save(migrated)
        preferences.edit().remove(KEY_LEGACY_VK_LINK).apply()
        Log.d(TAG, "[load] Старое значение vk_link мигрировано в источник типа ${migrated.type}")
        return migrated
    }

    /**
     * save writes the structured source to preferences.
     */
    fun save(source: CallJoinSource?) {
        val editor = preferences.edit()
        if (source == null) {
            editor.remove(KEY_SOURCE_TYPE)
            editor.remove(KEY_SOURCE_VALUE)
            editor.apply()
            Log.d(TAG, "[save] Источник звонка очищен")
            return
        }

        editor.putString(KEY_SOURCE_TYPE, source.type.name.lowercase())
        editor.putString(KEY_SOURCE_VALUE, source.value)
        editor.apply()
        Log.d(TAG, "[save] Сохранён источник звонка типа ${source.type}")
    }

    companion object {
        private const val TAG = "WireGuard/CallJoinSourceStore"
        private const val KEY_SOURCE_TYPE = "call_join_source_type"
        private const val KEY_SOURCE_VALUE = "call_join_source_value"
        private const val KEY_LEGACY_VK_LINK = "vk_link"
    }
}
