/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yellastrodev.rknvpn.activity

import android.os.Bundle
import android.util.Log
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.CallbackRegistry
import androidx.databinding.CallbackRegistry.NotifierCallback
import androidx.lifecycle.lifecycleScope
import com.yellastrodev.rknvpn.Application
import com.yellastrodev.rknvpn.model.ObservableTunnel
import com.yellastrodev.rknvpn.viewmodel.ConfigProxy
import com.yellastrodev.rnkvpn.rnkutils.CallJoinSource
import com.yellastrodev.rnkvpn.rnkutils.CallJoinSourceStore
import com.yellastrodev.rnkvpn.rnkutils.CallResult
import com.yellastrodev.rnkvpn.rnkutils.VkSessionManager
import com.yellastrodev.rnkvpn.viewmodel.TunnelViewModel
import kotlinx.coroutines.launch
import java.io.File

/**
 * BaseActivity stores the currently selected tunnel and the user's single call source input.
 *
 * The call source is persisted separately from the runtime join link:
 * token sources generate a fresh link on every VPN start, while direct links are reused as-is.
 */
abstract class BaseActivity : AppCompatActivity() {
    private val selectionChangeRegistry = SelectionChangeRegistry()
    private val callJoinSourceStore by lazy {
        CallJoinSourceStore(getSharedPreferences(PREFS_VPN, MODE_PRIVATE))
    }
    private var created = false

    /**
     * currentCallJoinSource keeps the original user intent from the single input field.
     */
    var currentCallJoinSource: CallJoinSource? = null
        private set

    /**
     * selectedTunnel tracks the tunnel currently controlled by the activity.
     */
    var selectedTunnel: ObservableTunnel? = null
        set(value) {
            val oldTunnel = field
            if (oldTunnel == value) return
            field = value

            currentCallJoinSource?.let { source ->
                val tunnel = field ?: return
                if (source.type == CallJoinSource.Type.LINK) {
                    Log.d(TAG, "[selectedTunnel] Применяем сохранённую ссылку к туннелю ${tunnel.name}")
                    setVkLink(source.value, tunnel)
                }
            }

            if (created) {
                if (!onSelectedTunnelChanged(oldTunnel, value)) {
                    field = oldTunnel
                } else {
                    selectionChangeRegistry.notifyCallbacks(oldTunnel, 0, value)
                }
            }
        }

    /**
     * citizennKey preserves compatibility with the existing UI input code.
     *
     * Internally the raw string is immediately converted to a structured call source.
     */
    var citizennKey: String?
        get() = currentCallJoinSource?.value
        set(value) {
            val normalizedValue = value?.trim()?.takeIf { it.isNotEmpty() }
            val source = normalizedValue?.let { CallJoinSource.fromUserInput(it) }
            currentCallJoinSource = source
            callJoinSourceStore.save(source)

            if (source == null) {
                Log.d(TAG, "[setCitizennKey] Источник звонка очищен")
                return
            }

            Log.d(TAG, "[setCitizennKey] Сохранён источник звонка типа ${source.type}")
            selectedTunnel?.let { tunnel ->
                if (source.type == CallJoinSource.Type.LINK) {
                    Log.d(TAG, "[setCitizennKey] Применяем прямую ссылку к туннелю ${tunnel.name}")
                    setVkLink(source.value, tunnel)
                }
            }
        }

    /**
     * vkSessionManager generates fresh join links from stored citizen tokens.
     */
    val vkSessionManager by lazy {
        VkSessionManager(File(filesDir, "ok_sessions.json"))
    }

    private val viewModel: TunnelViewModel by viewModels()

    /**
     * applyVkLinkToTunnel synchronously writes the resolved join link into the tunnel config.
     */
    suspend fun applyVkLinkToTunnel(link: String, tunnel: ObservableTunnel) {
        Log.d(TAG, "[applyVkLinkToTunnel] Применяем ссылку к туннелю ${tunnel.name}")
        if (tunnel.turnSettings == null || tunnel.config == null) return

        val configProxy = ConfigProxy(tunnel.config!!, tunnel.turnSettings)
        configProxy.turn.mode = "vk"

        if (link != configProxy.turn.vkLink || configProxy.turn.mode != tunnel.turnSettings?.mode) {
            configProxy.turn.vkLink = link
            val config = configProxy.resolve()
            val turnSettings = configProxy.resolveTurnSettings()
            Application.getTunnelManager().setTunnelConfig(tunnel, config, turnSettings)
        }
    }

    /**
     * setVkLink asynchronously applies the resolved link in non-blocking UI flows.
     */
    fun setVkLink(link: String, tunnel: ObservableTunnel) {
        lifecycleScope.launch {
            applyVkLinkToTunnel(link, tunnel)
        }
    }

    /**
     * resolveVkLinkForLaunch returns the actual join link to be used for the next VPN launch.
     */
    suspend fun resolveVkLinkForLaunch(tunnel: ObservableTunnel): CallResult? {
        val source = currentCallJoinSource ?: return null

        return when (source.type) {
            CallJoinSource.Type.LINK -> {
                Log.d(TAG, "[resolveVkLinkForLaunch] Используем пользовательскую ссылку напрямую")
                applyVkLinkToTunnel(source.value, tunnel)
                CallResult.Success(source.value)
            }

            CallJoinSource.Type.TOKEN -> {
                Log.d(TAG, "[resolveVkLinkForLaunch] Источник = токен, генерируем новую ссылку перед запуском VPN")
                when (val result = vkSessionManager.getLinkSmarter(source.value)) {
                    is CallResult.Success -> {
                        applyVkLinkToTunnel(result.url, tunnel)
                        result
                    }

                    is CallResult.AuthExpired -> result
                    is CallResult.Error -> result
                }
            }
        }
    }

    /**
     * addOnSelectedTunnelChangedListener registers a listener for tunnel selection changes.
     */
    fun addOnSelectedTunnelChangedListener(listener: OnSelectedTunnelChangedListener) {
        selectionChangeRegistry.add(listener)
    }

    /**
     * onCreate restores the stored call source and the last selected tunnel.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        currentCallJoinSource = callJoinSourceStore.load()

        val savedTunnelName = when {
            savedInstanceState != null -> savedInstanceState.getString(KEY_SELECTED_TUNNEL)
            intent != null -> intent.getStringExtra(KEY_SELECTED_TUNNEL)
            else -> null
        }
        Application.getTunnelManager().activityViewModel = viewModel
        if (savedTunnelName != null) {
            lifecycleScope.launch {
                val tunnel = Application.getTunnelManager().getTunnels()[savedTunnelName]
                if (tunnel == null)
                    created = true
                selectedTunnel = tunnel
                created = true
            }
        } else {
            created = true
        }
    }

    /**
     * onSaveInstanceState persists the selected tunnel name across activity recreation.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        if (selectedTunnel != null) outState.putString(KEY_SELECTED_TUNNEL, selectedTunnel!!.name)
        super.onSaveInstanceState(outState)
    }

    /**
     * onSelectedTunnelChanged is implemented by subclasses to react to selection changes.
     */
    protected abstract fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?): Boolean

    /**
     * removeOnSelectedTunnelChangedListener unregisters a tunnel selection listener.
     */
    fun removeOnSelectedTunnelChangedListener(listener: OnSelectedTunnelChangedListener) {
        selectionChangeRegistry.remove(listener)
    }

    /**
     * OnSelectedTunnelChangedListener receives selection change events from the activity.
     */
    interface OnSelectedTunnelChangedListener {
        fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?)
    }

    /**
     * SelectionChangeNotifier forwards tunnel selection events to listeners.
     */
    private class SelectionChangeNotifier : NotifierCallback<OnSelectedTunnelChangedListener, ObservableTunnel, ObservableTunnel>() {
        /**
         * onNotifyCallback forwards the selection change to a single listener.
         */
        override fun onNotifyCallback(
            listener: OnSelectedTunnelChangedListener,
            oldTunnel: ObservableTunnel?,
            ignored: Int,
            newTunnel: ObservableTunnel?
        ) {
            Log.d(TAG, "[onNotifyCallback] Tunnel changed from $oldTunnel to $newTunnel")
            listener.onSelectedTunnelChanged(oldTunnel, newTunnel)
        }
    }

    /**
     * SelectionChangeRegistry stores and notifies tunnel selection listeners.
     */
    private class SelectionChangeRegistry :
        CallbackRegistry<OnSelectedTunnelChangedListener, ObservableTunnel, ObservableTunnel>(SelectionChangeNotifier())

    companion object {
        private const val TAG = "BaseActivity"
        private const val KEY_SELECTED_TUNNEL = "selected_tunnel"
        private const val PREFS_VPN = "vpn_prefs"
    }
}
