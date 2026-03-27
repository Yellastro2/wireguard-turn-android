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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.yellastrodev.rknvpn.Application
import com.yellastrodev.rknvpn.R
import com.yellastrodev.rknvpn.activity.TunnelCreatorActivity
import com.yellastrodev.rknvpn.databinding.ObservableSortedKeyedArrayList
import com.yellastrodev.rknvpn.model.ObservableTunnel
import com.yellastrodev.rknvpn.util.ErrorMessages
import com.yellastrodev.rknvpn.util.QrCodeFromFileScanner
import com.yellastrodev.rknvpn.util.TunnelImporter
import com.yellastrodev.rnkvpn.fragment.RNKFragmentTunnelEditor
import kotlinx.coroutines.launch

class RNKTunnelListFragment : BaseFragment() {

    companion object {
        private const val TAG = "RNK_TUNNEL_LIST"
    }

    private var rootView: View? = null
    private lateinit var rvNodes: androidx.recyclerview.widget.RecyclerView
    private lateinit var btnAddNode: View
    private lateinit var etCitizenKey: android.widget.EditText

    // Лаунчер для импорта из файла
    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    val result = qrCodeFromFileScanner.scan(data)
                    TunnelImporter.importTunnel(parentFragmentManager, result.text) { showSnackbar(it) }
                } catch (e: Exception) {
                    val error = ErrorMessages[e]
                    val message = Application.get().resources.getString(R.string.import_error, error)
                    Log.e(TAG, message, e)
                    showSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data) { showSnackbar(it) }
            }
        }
    }

    // Лаунчер для сканирования QR-кода
    private val qrImportResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch {
                TunnelImporter.importTunnel(parentFragmentManager, qrCode) { showSnackbar(it) }
            }
        }
    }

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
            showAddOptions()
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
                tunnelAdapter.submitList(tunnels.toList())

                tunnels.addOnListChangedCallback(
                    object : androidx.databinding.ObservableList.OnListChangedCallback<ObservableSortedKeyedArrayList<String, ObservableTunnel>>() {
                    override fun onChanged(sender: ObservableSortedKeyedArrayList<String, ObservableTunnel>?) {
                        updateList(sender)
                    }

                    override fun onItemRangeChanged(sender: ObservableSortedKeyedArrayList<String, ObservableTunnel>?, positionStart: Int, itemCount: Int) {
                        updateList(sender)
                    }

                    override fun onItemRangeInserted(sender: ObservableSortedKeyedArrayList<String, ObservableTunnel>?, positionStart: Int, itemCount: Int) {
                        updateList(sender)
                    }

                    override fun onItemRangeMoved(sender: ObservableSortedKeyedArrayList<String, ObservableTunnel>?, fromPosition: Int, toPosition: Int, itemCount: Int) {
                        updateList(sender)
                    }

                    override fun onItemRangeRemoved(sender: ObservableSortedKeyedArrayList<String, ObservableTunnel>?, positionStart: Int, itemCount: Int) {
                        updateList(sender)
                    }

                    private fun updateList(sender: ObservableSortedKeyedArrayList<String, ObservableTunnel>?) {
                        sender?.let { tunnelAdapter.submitList(it.toList()) }
                    }
                })
            } catch (e: Throwable) {
                Log.e(TAG, "Ошибка загрузки списка туннелей", e)
            }
        }
    }

    private fun connectToTunnel(tunnel: ObservableTunnel) {
        lifecycleScope.launch {
            try {
                Log.d(TAG, "Переключение to tunnel ${tunnel.name}")

                val isActive = selectedTunnel?.state == com.wireguard.android.backend.Tunnel.State.UP
                selectedTunnel = tunnel

                val prefs = requireContext().getSharedPreferences("vpn_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putString("last_used_tunnel", tunnel.name).apply()

                if (isActive) {
                    if (tunnel.state != com.wireguard.android.backend.Tunnel.State.UP) {
                        tunnel.setStateAsync(com.wireguard.android.backend.Tunnel.State.UP)
                    }
                    Toast.makeText(requireContext(), "Подключаемся к ${tunnel.name}", Toast.LENGTH_SHORT).show()
                }
                tunnelAdapter.notifyDataSetChanged()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to connect tunnel", e)
                showSnackbar("Ошибка подключения: ${ErrorMessages[e]}")
            }
        }
    }

    private fun openEditor(tunnel: ObservableTunnel) {
        selectedTunnel = tunnel
        requireActivity().supportFragmentManager.commit {
            val fragment = RNKFragmentTunnelEditor()
            fragment.arguments = bundleOf(RNKFragmentTunnelEditor.KEY_TUNNEL_NAME to tunnel.name)
            replace(R.id.list_detail_container, fragment)
            setTransition(androidx.fragment.app.FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            addToBackStack(null)
        }
    }

    private fun deleteTunnel(tunnel: ObservableTunnel) {
        lifecycleScope.launch {
            try {
                tunnel.deleteAsync()
                Toast.makeText(requireContext(), "Туннель ${tunnel.name} удалён", Toast.LENGTH_SHORT).show()
                loadTunnels()
            } catch (e: Throwable) {
                showSnackbar("Не удалось удалить: ${ErrorMessages[e]}")
            }
        }
    }

    private fun showAddOptions() {
        val options = arrayOf(
            getString(R.string.create_empty),
            getString(R.string.create_from_file),
            getString(R.string.create_from_qr_code)
        )

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.create_tunnel)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // Создать с нуля
                        startActivity(Intent(requireActivity(), TunnelCreatorActivity::class.java))
                    }
                    1 -> { // Импорт из файла
                        tunnelFileImportResultLauncher.launch("*/*")
                    }
                    2 -> { // QR-код
                        qrImportResultLauncher.launch(
                            ScanOptions()
                                .setOrientationLocked(false)
                                .setBeepEnabled(false)
                                .setPrompt(getString(R.string.qr_code_hint))
                        )
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSnackbar(message: CharSequence) {
        rootView?.let {
            Snackbar.make(it, message, Snackbar.LENGTH_LONG).show()
        } ?: Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        Log.d(TAG, "onSelectedTunnelChanged: oldTunnel=$oldTunnel, newTunnel=$newTunnel")
        tunnelAdapter.selectedTunnel = newTunnel
        tunnelAdapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        rootView = null
        super.onDestroyView()
    }
}
