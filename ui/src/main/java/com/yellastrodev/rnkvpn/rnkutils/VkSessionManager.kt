/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.yellastrodev.rnkvpn.rnkutils


import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/**
 * Результат выполнения операций со звонками
 */
sealed interface CallResult {
    data class Success(val url: String) : CallResult
    data class Error(val message: String, val throwable: Throwable? = null) : CallResult
    object AuthExpired : CallResult // Когда auth_token окончательно протух
}

/**
 * Данные сессии OK.ru
 */
data class OkSession(
    val key: String,
    var lastUsed: Long = System.currentTimeMillis()
)

/**
 * Менеджер сессий без внешних зависимостей.
 * @param authToken Твой секретный ключ
 * @param sessionFile Файл для хранения JSON-базы (создается через File(context.filesDir, "name"))
 */
class VkSessionManager(
    private val sessionFile: File
) {
    private val tag = "VKSessionManager"

    /**
     * Пытается получить ссылку на звонок "умным" способом:
     * 1. Сначала проверяет сохраненные сессии из файла (сортирует по времени использования).
     * 2. Если сессия протухла, удаляет её и пробует следующую.
     * 3. Если живых сессий нет, выпускает новую через [authToken].
     */
    fun getLinkSmarter(authToken: String): CallResult {
        val sessions = loadKeys()

        // Сортируем: те, что не использовались дольше всего — в начало
        sessions.sortBy { it.lastUsed }

        val iterator = sessions.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            Log.d(tag, "[getLinkSmarter] Пробуем ключ: ${session.key.takeLast(5)}")

            val result = createInstantCall(session.key)
            if (result is CallResult.Success) {
                // Обновляем время использования и сохраняем
                session.lastUsed = System.currentTimeMillis()
                saveKeys(sessions)
                Log.d(tag, "[getLinkSmarter] Успешно получен линк")
                return result
            } else if (result is CallResult.AuthExpired) {
                Log.w(tag, "[getLinkSmarter] Сессия ${session.key.takeLast(5)} протухла, удаляем")
                iterator.remove()
                saveKeys(sessions)
            }
        }

        Log.i(tag, "[getLinkSmarter] Живых ключей нет, создаем новую сессию")
        val newKey = getOkSessionKey(authToken) ?: return CallResult.AuthExpired

        val newSession = OkSession(newKey, System.currentTimeMillis())
        sessions.add(newSession)
        saveKeys(sessions)

        return createInstantCall(newKey)
    }

    /**
     * Обменивает основной [authToken] на сессионный ключ calls.okcdn.ru.
     */
    private fun getOkSessionKey(authToken: String): String? {
        val sessionData = JSONObject().apply {
            put("version", 3)
            put("device_id", "55fb682c-bcff-4695-872a-0dfd75aeee57")
            put("client_version", 1.1)
            put("client_type", "SDK_JS")
            put("auth_token", authToken)
        }

        val params = mapOf(
            "session_data" to sessionData.toString(),
            "method" to "auth.anonymLogin",
            "format" to "JSON",
            "application_key" to "CGMMEJLGDIHBABABA"
        )
        Log.d(tag, "[getOkSessionKey] Запрос: $params")
        val response = makePostRequest("https://calls.okcdn.ru/fb.do", params) ?: return null
        return try {
            val json = JSONObject(response)
            if (json.has("session_key")) {
                json.getString("session_key")
            } else {
                Log.e(tag, "[getOkSessionKey] Ошибка в ответе: $response")
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "[getOkSessionKey] Ошибка парсинга JSON: ${e.message}")
            null
        }
    }

    /**
     * Создает звонок и возвращает готовую ссылку vk.com/call/join/...
     */
    private fun createInstantCall(sessionKey: String): CallResult {
        val callPayload = JSONObject().apply {
            put("is_video", false)
            put("with_join_link", true)
            put("join_by_link", true)
            put("community_user_id", 0)
            put("caller_app_id", 51542479)
        }

        val params = mapOf(
            "method" to "vchat.startConversation",
            "format" to "JSON",
            "application_key" to "CGMMEJLGDIHBABABA",
            "session_key" to sessionKey,
            "conversationId" to UUID.randomUUID().toString(),
            "isVideo" to "false",
            "protocolVersion" to "5",
            "createJoinLink" to "true",
            "payload" to callPayload.toString()
        )

        val response = makePostRequest("https://calls.okcdn.ru/fb.do", params)
            ?: return CallResult.Error("Ошибка сети")

        return try {
            val json = JSONObject(response)
            when {
                json.has("join_link") ->
                    CallResult.Success("https://vk.com/call/join/${json.getString("join_link")}")
                json.optInt("error_code") in listOf(101, 102, 401) ->
                    CallResult.AuthExpired
                else ->
                    CallResult.Error(json.optString("error_msg", "Неизвестная ошибка"))
            }
        } catch (e: Exception) {
            Log.e(tag, "[createInstantCall] Ошибка парсинга: ${e.message}")
            CallResult.Error("Ошибка парсинга ответа API")
        }
    }

    /**
     * Универсальный POST запрос через HttpURLConnection.
     */
    private fun makePostRequest(urlStr: String, params: Map<String, String>): String? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 7000

            val postData = params.map {
                "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
            }.joinToString("&")

            conn.outputStream.use { os ->
                OutputStreamWriter(os).use { it.write(postData) }
            }

            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.w(tag, "[makePostRequest] HTTP Error: ${conn.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "[makePostRequest] Сетевое исключение: ${e.message}", e)
            null
        }
    }

    /**
     * Загружает список сессий из локального JSON-файла.
     */
    private fun loadKeys(): MutableList<OkSession> {
        if (!sessionFile.exists()) {
            Log.i(tag, "[loadKeys] Файл не найден, создаем новый список")
            return mutableListOf()
        }
        return try {
            val jsonArray = JSONArray(sessionFile.readText())
            val list = mutableListOf<OkSession>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(OkSession(obj.getString("key"), obj.getLong("last_used")))
            }
            Log.d(tag, "[loadKeys] Загружено ключей: ${list.size}")
            list
        } catch (e: Exception) {
            Log.e(tag, "[loadKeys] Ошибка чтения/парсинга: ${e.message}")
            mutableListOf()
        }
    }

    /**
     * Сохраняет текущий список сессий в файл.
     */
    private fun saveKeys(sessions: List<OkSession>) {
        try {
            val jsonArray = JSONArray()
            sessions.forEach {
                val obj = JSONObject()
                obj.put("key", it.key)
                obj.put("last_used", it.lastUsed)
                jsonArray.put(obj)
            }
            sessionFile.writeText(jsonArray.toString())
            Log.d(tag, "[saveKeys] База обновлена. Ключей: ${sessions.size}")
        } catch (e: Exception) {
            Log.e(tag, "[saveKeys] Ошибка записи в файл: ${e.message}")
        }
    }
}
