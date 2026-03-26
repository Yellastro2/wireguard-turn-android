/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.yellastrodev.rknvpn.fragment

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.rknvpn.Application
import com.yellastrodev.rknvpn.R
import com.yellastrodev.rknvpn.activity.TunnelCreatorActivity
import com.yellastrodev.rknvpn.model.ObservableTunnel
import com.yellastrodev.rknvpn.util.ErrorMessages
import com.yellastrodev.rnkvpn.fragment.RNKFragmentTunnelEditor
import kotlinx.coroutines.launch

class RNKTunnelListFragment : BaseFragment() {

    companion object {
        private const val TAG = "RNK_TUNNEL_LIST"
    }

    private var rootView: View? = null
    private lateinit var rvNodes: androidx.recyclerview.widget.RecyclerView
    private lateinit var btnAddNode: View
    private lateinit var etCitizenKey: android.widget.EditText   // если нужно будет редактировать

    private val tunnelAdapter by lazy {
        TunnelListAdapter(selectedTunnel) { tunnel, action ->
            when (action) {
                TunnelListAction.CLICK -> connectToTunnel(tunnel)
                TunnelListAction.EDIT -> openEditor(tunnel)
                TunnelListAction.DELETE -> deleteTunnel(tunnel)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        Log.d(TAG, "[onCreateView]")
        rootView = inflater.inflate(R.layout.rnk_list_frag, container, false)
        initViews()
        setupRecyclerView()
        return rootView!!
    }

    private fun initViews() {
        rvNodes = rootView!!.findViewById(R.id.rvNodes)
        btnAddNode = rootView!!.findViewById(R.id.btnAddNode)
        etCitizenKey = rootView!!.findViewById(R.id.etCitizenKey)

        btnAddNode.setOnClickListener {
            showAddOptions()   // пока простой вариант, потом можно сделать BottomSheet
        }
    }

    private fun setupRecyclerView() {
        rvNodes.layoutManager = LinearLayoutManager(requireContext())
        rvNodes.adapter = tunnelAdapter
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        loadTunnels()
    }

    private fun loadTunnels() {
        lifecycleScope.launch {
            try {
                val tunnels = Application.getTunnelManager().getTunnels()
                Log.d(TAG, "[loadTunnels] Загружено ${tunnels.size} туннелей")
                tunnelAdapter.submitList(tunnels.toList())   // или .submitList(tunnels.values.toList())
            } catch (e: Throwable) {
                Log.e(TAG, "Ошибка загрузки списка туннелей", e)
            }
        }
    }

    private fun connectToTunnel(tunnel: ObservableTunnel) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Переключение to tunnel ${tunnel.name}")

                // Переключаем выбранный туннель
                val isActive = selectedTunnel?.state == com.wireguard.android.backend.Tunnel.State.UP

                selectedTunnel = tunnel

                if (isActive) {
                    // Включаем его
                    if (tunnel.state != com.wireguard.android.backend.Tunnel.State.UP) {
                        tunnel.setStateAsync(com.wireguard.android.backend.Tunnel.State.UP)
                    }

                    Toast.makeText(requireContext(), "Подключаемся к ${tunnel.name}", Toast.LENGTH_SHORT).show()
                }
                tunnelAdapter.notifyDataSetChanged()   // просто для обновления иконки
            } catch (e: Throwable) {
                val error = ErrorMessages[e]
                Snackbar.make(requireView(), "Ошибка подключения: $error", Snackbar.LENGTH_LONG).show()
                Log.e(TAG, "Failed to connect tunnel", e)
            }
        }
    }

    private fun openEditor(tunnel: ObservableTunnel) {
        selectedTunnel = tunnel   // важно, чтобы редактор знал, какой туннель редактировать

//        val containerId = if (requireActivity().findViewById<View?>(R.id.detail_container) != null) {
//            R.id.detail_container
//        } else {
//            R.id.list_detail_container
//        }

        // Простая замена: всегда открываем редактор поверх списка
        requireActivity().supportFragmentManager.commit {
            replace(R.id.list_detail_container, RNKFragmentTunnelEditor())  // ← главный контейнер
            setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            addToBackStack(null)
        }

//        requireActivity().supportFragmentManager.commit {
//            replace(containerId, RNKFragmentTunnelEditor())
//            setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE)
//            addToBackStack(null)
//        }
    }

    private fun deleteTunnel(tunnel: ObservableTunnel) {
        lifecycleScope.launch {
            try {
                tunnel.deleteAsync()
                Toast.makeText(requireContext(), "Туннель ${tunnel.name} удалён", Toast.LENGTH_SHORT).show()
                loadTunnels() // обновляем список
            } catch (e: Throwable) {
                Snackbar.make(requireView(), "Не удалось удалить: ${ErrorMessages[e]}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddOptions() {
        // Пока простой вариант — можно потом заменить на BottomSheet как в оригинале
        startActivity(Intent(requireActivity(), TunnelCreatorActivity::class.java))

        // Если хочешь QR / импорт — добавь сюда диалог или BottomSheet
    }

    // Обновление выделения при смене туннеля
    @SuppressLint("NotifyDataSetChanged")
    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        Log.d(TAG, "onSelectedTunnelChanged: oldTunnel=$oldTunnel, newTunnel=$newTunnel")
        tunnelAdapter.selectedTunnel = newTunnel
        tunnelAdapter.notifyDataSetChanged()   // простой способ, можно оптимизировать позже
    }

    override fun onDestroyView() {
        rootView = null
        super.onDestroyView()
    }
}