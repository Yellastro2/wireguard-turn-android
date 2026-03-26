/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.yellastrodev.rnkvpn.fragment

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.os.BundleCompat
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.rknvpn.Application
import com.yellastrodev.rknvpn.R
import com.wireguard.android.backend.Tunnel
import com.yellastrodev.rknvpn.databinding.TunnelEditorFragmentBinding
import com.yellastrodev.rknvpn.model.ObservableTunnel
import com.yellastrodev.rknvpn.util.AdminKnobs
import com.yellastrodev.rknvpn.util.BiometricAuthenticator
import com.yellastrodev.rknvpn.util.ErrorMessages
import com.yellastrodev.rknvpn.viewmodel.ConfigProxy
import com.wireguard.config.Config
import com.yellastrodev.rknvpn.activity.BaseActivity
import com.yellastrodev.rknvpn.fragment.BaseFragment
import com.yellastrodev.rknvpn.viewmodel.PeerProxy
import com.yellastrodev.rknvpn.widget.KeyInputFilter
import com.yellastrodev.rknvpn.widget.NameInputFilter
import kotlinx.coroutines.launch


class RNKFragmentTunnelEditor : BaseFragment() {
    // Секция 1
    private lateinit var etNodeName: EditText
    private lateinit var etPrivateKey: EditText
    private lateinit var etPublicKey: EditText
    private lateinit var etNodeIp: EditText
    private lateinit var etListenPort: EditText
    private lateinit var etDns: EditText
    private lateinit var etMtu: EditText

    // Секция 2 (TURN)
    private lateinit var switchTurn: SwitchCompat
    private lateinit var layoutTurnContent: View
    private lateinit var etTurnPeer: EditText
    private lateinit var etTurnVk: EditText
    private lateinit var etTurnStreams: EditText
    private lateinit var etTurnLocalPort: EditText
    private lateinit var cbTurnUdp: CheckBox
    private lateinit var layoutTurnAdvanced: View
    private lateinit var etTurnIp: EditText
    private lateinit var etTurnPortAdv: EditText
    private lateinit var cbTurnNoDtls: CheckBox

    // Модель (оставляем для удобства сохранения)
    private var configProxy = ConfigProxy()
    private var tunnel: ObservableTunnel? = null

    private val peerHolders = mutableListOf<PeerViewHolder>()
    private lateinit var peersContainer: LinearLayout // Тот самый peers_layout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        val view = inflater.inflate(R.layout.rnk_editor_frag, container, false)
        initViews(view)
        setupListeners(view)

        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        Log.d("RNK_EDITOR_LIFECYCLE", "onAttach вызван. Activity = ${activity?.javaClass?.simpleName}")
        (activity as? BaseActivity)?.addOnSelectedTunnelChangedListener(this)
        Log.d("RNK_EDITOR_LIFECYCLE", "Listener добавлен в BaseActivity")
    }

    override fun onDetach() {
        Log.d("RNK_EDITOR_LIFECYCLE", "onDetach вызван")
        (activity as? BaseActivity)?.removeOnSelectedTunnelChangedListener(this)
        super.onDetach()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("RNK_EDITOR_LIFECYCLE", "onViewCreated. selectedTunnel сейчас = ${selectedTunnel?.name ?: "null"}")
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        if (savedInstanceState == null) {
            // ←←← Вот это и вызывает колбэк, когда туннель уже выбран
            onSelectedTunnelChanged(null, selectedTunnel)
        } else {
            // Восстановление после поворота экрана и т.п.
            tunnel = selectedTunnel

            // Если потом будешь сохранять состояние (как в оригинале), добавишь сюда:
            // val config = BundleCompat.getParcelable(savedInstanceState, KEY_LOCAL_CONFIG, ConfigProxy::class.java)
            // ...
        }
    }

    private fun initViews(view: View) {
        Log.d("RNK_EDITOR", "initViews")
        etNodeName = view.findViewById(R.id.etNodeName)
        etPrivateKey = view.findViewById(R.id.etPrivateKey)
        etPublicKey = view.findViewById(R.id.etPublicKey)
        etNodeIp = view.findViewById(R.id.etNodeIp)
        etListenPort = view.findViewById(R.id.etListenPort)
        etDns = view.findViewById(R.id.etDns)
        etMtu = view.findViewById(R.id.etMtu)

        switchTurn = view.findViewById(R.id.switchTurn)
        layoutTurnContent = view.findViewById(R.id.layoutTurnContent)
        etTurnPeer = view.findViewById(R.id.etTurnPeer)
        etTurnVk = view.findViewById(R.id.etTurnVk)
        etTurnStreams = view.findViewById(R.id.etTurnStreams)
        etTurnLocalPort = view.findViewById(R.id.etTurnLocalPort)
        cbTurnUdp = view.findViewById(R.id.cbTurnUdp)

        layoutTurnAdvanced = view.findViewById(R.id.layoutTurnAdvanced)
        etTurnIp = view.findViewById(R.id.etTurnIp)
        etTurnPortAdv = view.findViewById(R.id.etTurnPortAdv)
        cbTurnNoDtls = view.findViewById(R.id.cbTurnNoDtls)

        // Применяем фильтры вручную (вместо BindingAdapter)
        etNodeName.filters = arrayOf(NameInputFilter.newInstance())
        etPrivateKey.filters = arrayOf(KeyInputFilter.newInstance())
        peersContainer = view.findViewById(R.id.fr_edit_peers_layout)
    }

    private fun setupListeners(view: View) {
        // Логика переключателя TURN
        switchTurn.setOnCheckedChangeListener { _, isChecked ->
            layoutTurnContent.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Логика Advanced в TURN
        view.findViewById<View>(R.id.btnToggleAdvanced).setOnClickListener {
            val isVisible = layoutTurnAdvanced.visibility == View.VISIBLE
            layoutTurnAdvanced.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        // Кнопка УТВЕРДИТЬ (Save)
        view.findViewById<View>(R.id.btnSave).setOnClickListener {
            syncUiToModel() // Сначала собираем данные
            onSaveClicked()
        }

        view.findViewById<View>(R.id.btnCancel).setOnClickListener { onFinished() }

        // Генерация ключа (иконка в FrameLayout)
        view.findViewById<View>(R.id.etPrivateKey).setOnClickListener { onKeyClick(it) }

        // Кнопка добавления нового пира
        view.findViewById<View>(R.id.fr_edit_addpeer_layout).setOnClickListener {
            val newPeerProxy = PeerProxy()
            // ВАЖНО: добавляем его в модель, чтобы configProxy.resolve() его увидел
            configProxy.addPeer()

            // Отрисовываем карточку
            addPeerToUi(newPeerProxy)

            // Скроллим вниз, чтобы пользователь видел новую форму
            view.findViewById<ScrollView>(R.id.fr_edit_scrollView).post {
                view.findViewById<ScrollView>(R.id.fr_edit_scrollView).fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun onFinished() {
        // Прячем клавиатуру
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view?.windowToken, 0)

        // Возвращаемся назад (в оригинале это обычно backStack)
        parentFragmentManager.popBackStack()
    }

    fun onKeyClick(view: View) {
        if (view is EditText) {
            val isPassword = view.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
            if (isPassword) {
                // Если поле скрыто - показываем через биометрию или просто так
                view.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                // Если открыто - генерируем новый ключ
                val key = com.wireguard.crypto.KeyPair().privateKey.toBase64()
                view.setText(key)
            }
        }
    }

    // Твоя простая "функция взаимозависимости" для ввода данных
    private fun syncUiToModel() {
        val i = configProxy.`interface`
        val t = configProxy.turn

        // Интерфейс
        i.privateKey = etPrivateKey.text.toString()
        i.addresses = etNodeIp.text.toString()
        i.dnsServers = etDns.text.toString()
        i.listenPort = etListenPort.text.toString()
        i.mtu = etMtu.text.toString()

        // TURN
        t.enabled = switchTurn.isChecked
        t.peer = etTurnPeer.text.toString()
        t.vkLink = etTurnVk.text.toString()
        t.streams = etTurnStreams.text.toString()
        t.localPort = etTurnLocalPort.text.toString()
        t.useUdp = cbTurnUdp.isChecked
        t.turnIp = etTurnIp.text.toString()
        t.turnPort = etTurnPortAdv.text.toString()
        t.noDtls = cbTurnNoDtls.isChecked
    }

    // Обратная функция: когда конфиг загружен, подставляем данные в UI
    private fun syncModelToUi(config: Config) {
        Log.d("RNK_EDITOR", "syncModelToUi: config = ${config.toString()}")
        configProxy = ConfigProxy(config, tunnel?.turnSettings)
        val i = configProxy.`interface`
        val t = configProxy.turn

        etNodeName.setText(tunnel?.name)

        etPrivateKey.setText(i.privateKey)
        etPublicKey.setText(i.publicKey)
        etNodeIp.setText(i.addresses)
        etDns.setText(i.dnsServers)
        etListenPort.setText(i.listenPort)
        etMtu.setText(i.mtu)

        switchTurn.isChecked = t.enabled
        etTurnPeer.setText(t.peer)
        etTurnVk.setText(t.vkLink)
        etTurnStreams.setText(t.streams)
        etTurnLocalPort.setText(t.localPort)
        cbTurnUdp.isChecked = t.useUdp

        // Скрываем/показываем секцию TURN
        layoutTurnContent.visibility = if (t.enabled) View.VISIBLE else View.GONE
    }

    fun onKeyFocusChange(view: View, hasFocus: Boolean) {
        if (hasFocus && view is EditText) {
            // Можно добавить логику авто-открытия клавиатуры или проверки биометрии
        }
    }

    private fun addPeerToUi(peerProxy: PeerProxy) {
        val inflater = LayoutInflater.from(requireContext())
        val peerView = inflater.inflate(R.layout.rnk_editor_peer, peersContainer, false)

        val holder = PeerViewHolder(peerView, peerProxy)

        // Удаление узла
        holder.btnDelete.setOnClickListener {
            // 1. Убираем из UI
            peersContainer.removeView(peerView)
            // 2. Убираем из списка холдеров для синхронизации
            peerHolders.remove(holder)
            // 3. Убираем из самой модели ConfigProxy
            configProxy.peers.remove(peerProxy)

            // Проверка на пустоту для логов или заглушки
            if (peerHolders.isEmpty()) {
                // tvEmptyPeersPlaceholder.visibility = View.VISIBLE
            }
        }

        // Обработка фокуса и клика по ключам (для биометрии, как в оригинале)
        holder.etPresharedKey.setOnFocusChangeListener { v, hasFocus ->
            onKeyFocusChange(v, hasFocus)
        }

        peerHolders.add(holder)
        peersContainer.addView(peerView)
    }

    private fun syncAllDataToModel() {
        // 1. Синхронизируем основной интерфейс (как обсуждали раньше)
        syncUiToModel()

        // 2. Очищаем старые пиры в модели и закидываем обновленные
        // В ConfigProxy пиры обычно живут в ObservableList,
        // но так как мы всё делаем руками, просто обновляем их через холдеры
        peerHolders.forEach { it.syncToModel() }
    }

    private fun onSaveClicked() {
        try {
            syncAllDataToModel() // Собираем всё в ConfigProxy

            val tunnelName = etNodeName.text.toString()
            if (tunnelName.isEmpty()) {
                throw Exception("Наименование узла не может быть пустым (ФОРМА №42-В требует заполнения)")
            }

            val config = configProxy.resolve() // WireGuard превращает Proxy в реальный Config
            val turnSettings = configProxy.resolveTurnSettings()

            lifecycleScope.launch {
                // Дальше твоя стандартная логика WireGuard:
                if (tunnel == null) {
                    // Создание нового
                    Application.getTunnelManager().create(tunnelName, config, turnSettings)
                } else {
                    // Обновление существующего
                    if (tunnel!!.name != tunnelName) {
                        tunnel!!.setNameAsync(tunnelName)
                    }
                    Application.getTunnelManager().setTunnelConfig(tunnel!!, config, turnSettings)
                }
                onFinished()
            }
        } catch (e: Throwable) {
            val error = ErrorMessages[e]
            Snackbar.make(requireView(), error, Snackbar.LENGTH_LONG).show()
        }
    }


    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?
    ) {
        // 1. Обновляем локальную ссылку
        Log.d("RNK_EDITOR", "onSelectedTunnelChanged: old: ${oldTunnel?.name}, new: ${newTunnel?.name}")
        tunnel = newTunnel

        if (newTunnel != null) {
            // 2. Загружаем конфигурацию из ядра WireGuard
            // Используем lifecycleScope, так как получение конфига — операция асинхронная
            lifecycleScope.launch {
                try {
                    val config = newTunnel.getConfigAsync()
                    // 3. Вызываем наш метод отрисовки (который мы написали ранее)
                    onConfigLoaded(config)
                } catch (e: Throwable) {
                    Log.e("RNK_EDITOR", "Ошибка загрузки конфигурации узла", e)
                    Toast.makeText(requireContext(), "Ошибка доступа к параметрам узла", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            // 4. Если туннель пустой (создание нового), инициализируем пустой прокси
            configProxy = ConfigProxy()
            peersContainer.removeAllViews()
            peerHolders.clear()
            // Можно выставить дефолтные значения (например, пустые EditText)
        }
    }


    private fun onConfigLoaded(config: Config) {
        Log.d("RNK_EDITOR", "onConfigLoaded")
        // Инициализируем прокси-объект (он помогает парсить сырые данные WireGuard)
        configProxy = ConfigProxy(config, tunnel?.turnSettings)

        // 1. Заполняем основные поля Интерфейса и TURN
        syncModelToUi(config)

        // 2. Работа со списком пиров (Секция 3)
        peersContainer.removeAllViews()
        peerHolders.clear()

        if (configProxy.peers.isEmpty()) {
            // Можно показать заглушку "Список пиров пуст", если она есть в лайауте
            // tvEmptyPeersPlaceholder.visibility = View.VISIBLE
        } else {
            // tvEmptyPeersPlaceholder.visibility = View.GONE
            configProxy.peers.forEach { peerProxy ->
                addPeerToUi(peerProxy)
            }
        }
    }

    private inner class PeerViewHolder(
        val view: View,
        val peerProxy: PeerProxy
    )
    {
        val etPublicKey: EditText = view.findViewById(R.id.etPeerPublicKey)
        val etPresharedKey: EditText = view.findViewById(R.id.etPeerPresharedKey)
        val etEndpoint: EditText = view.findViewById(R.id.etPeerEndpoint)
        val etAllowedIps: EditText = view.findViewById(R.id.etPeerAllowedIps)
        val etKeepalive: EditText = view.findViewById(R.id.etPeerKeepalive)
        val cbExcludePrivate: CheckBox = view.findViewById(R.id.cbExcludePrivateIps)
        val btnDelete: View = view.findViewById(R.id.btnDeletePeer)
        val btnGeneratePsk: View = view.findViewById(R.id.btnGeneratePsk)

        init {
            // Наполняем данными при создании
            etPublicKey.setText(peerProxy.publicKey)
            etPresharedKey.setText(peerProxy.preSharedKey)
            etEndpoint.setText(peerProxy.endpoint)
            etAllowedIps.setText(peerProxy.allowedIps)
            etKeepalive.setText(peerProxy.persistentKeepalive)
            cbExcludePrivate.isChecked = peerProxy.isExcludingPrivateIps

//            etAllowedIps.addTextChangedListener {
//                val canExclude = peerProxy.isAbleToExcludePrivateIps // Проверь это имя в своем PeerProxy
//                cbExcludePrivate.visibility = if (canExclude) View.VISIBLE else View.GONE
//            }

            // Применяем фильтр ключей
            etPublicKey.filters = arrayOf(KeyInputFilter.newInstance())
            etPresharedKey.filters = arrayOf(KeyInputFilter.newInstance())
        }

        // Собираем данные из полей обратно в объект Proxy
        fun syncToModel() {
            peerProxy.publicKey = etPublicKey.text.toString()
            peerProxy.preSharedKey = etPresharedKey.text.toString()
            peerProxy.endpoint = etEndpoint.text.toString()
            peerProxy.allowedIps = etAllowedIps.text.toString()
            peerProxy.persistentKeepalive = etKeepalive.text.toString()
//            peerProxy.isExcludingPrivateIps = cbExcludePrivate.isChecked
        }
    }
}