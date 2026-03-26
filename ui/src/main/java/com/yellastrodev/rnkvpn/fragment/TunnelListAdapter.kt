/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.yellastrodev.rknvpn.fragment

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.yellastrodev.rknvpn.R
import com.yellastrodev.rknvpn.model.ObservableTunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Адаптер для списка узлов связи в RNKTunnelListFragment
 */
class TunnelListAdapter(
    var selectedTunnel: ObservableTunnel?,
    private val onAction: (tunnel: ObservableTunnel, action: TunnelListAction) -> Unit
) : ListAdapter<ObservableTunnel, TunnelListAdapter.TunnelViewHolder>(TunnelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TunnelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_node, parent, false)
        return TunnelViewHolder(view, onAction)
    }

    override fun onBindViewHolder(holder: TunnelViewHolder, position: Int) {
        Log.d("TunnelListAdapter", "onBindViewHolder: position=$position")
        holder.bind(getItem(position), selectedTunnel)
    }

    /**
     * ViewHolder
     */
    class TunnelViewHolder(
        itemView: View,
        private val onAction: (tunnel: ObservableTunnel, action: TunnelListAction) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivCheck: ImageView = itemView.findViewById(R.id.ivCheck)
        private val tvNodeName: TextView = itemView.findViewById(R.id.tvNodeName)
        private val tvNodeIp: TextView = itemView.findViewById(R.id.tvNodeIp)
        private val tvNodeRegion: TextView = itemView.findViewById(R.id.tvNodeRegion)
        private val ivEdit: ImageView = itemView.findViewById(R.id.ivEdit)
        private val ivDelete: ImageView = itemView.findViewById(R.id.ivDelete)

        private var currentTunnel: ObservableTunnel? = null

        init {
            // Клик по всей карточке — переключить и подключить туннель
            itemView.setOnClickListener {
                currentTunnel?.let { tunnel ->
                    onAction(tunnel, TunnelListAction.CLICK)
                }
            }

            // Клик по кнопке "Редактировать"
            ivEdit.setOnClickListener {
                currentTunnel?.let { tunnel ->
                    onAction(tunnel, TunnelListAction.EDIT)
                }
            }

            // Клик по кнопке "Удалить"
            ivDelete.setOnClickListener {
                currentTunnel?.let { tunnel ->
                    onAction(tunnel, TunnelListAction.DELETE)
                }
            }
        }

        fun bind(tunnel: ObservableTunnel, selectedTunnel: ObservableTunnel?) {
            currentTunnel = tunnel

            tvNodeName.text = tunnel.name

            // IP адрес (берём первый из адресов интерфейса)
            GlobalScope.launch {
                val address = tunnel.getConfigAsync().`interface`.addresses.firstOrNull().toString()
                withContext(Dispatchers.Main) {
                    tvNodeIp.text = address
                }
            }

            // Регион — пока заглушка (можно потом вытащить из имени или добавить поле)
            tvNodeRegion.text = "ЦФО"   // TODO: сделать динамическим при необходимости

            // Визуальное выделение активного туннеля
//            val isActive = tunnel.state == com.wireguard.android.backend.Tunnel.State.UP
            val isActive = tunnel == selectedTunnel
            if (isActive) {
                itemView.background = itemView.context.getDrawable(R.drawable.bg_node_active)
                ivCheck.setImageResource(R.drawable.ic_check_circle)
                ivCheck.visibility = View.VISIBLE
            } else {
                itemView.background = itemView.context.getDrawable(R.drawable.bg_node_inactive)
                ivCheck.visibility = View.INVISIBLE
            }
        }
    }

    /**
     * DiffUtil для эффективного обновления списка
     */
    class TunnelDiffCallback : DiffUtil.ItemCallback<ObservableTunnel>() {
        override fun areItemsTheSame(oldItem: ObservableTunnel, newItem: ObservableTunnel): Boolean {
            return oldItem.name == newItem.name
        }

//        override fun areContentsTheSame(oldItem: ObservableTunnel, newItem: ObservableTunnel): Boolean {
//            return oldItem.name == newItem.name &&
//                    oldItem.state == newItem.state &&
//                    oldItem.getConfig()?.`interface`?.addresses == newItem.getConfig()?.`interface`?.addresses
//        }
            override fun areContentsTheSame(oldItem: ObservableTunnel, newItem: ObservableTunnel): Boolean {
                // Здесь сравниваем только то, что можно быстро проверить без асинхронных вызовов
                return oldItem.name == newItem.name &&
                        oldItem.state == newItem.state
                // НЕ сравниваем адреса здесь! Это слишком дорого и асинхронно.
                // Адреса будем обновлять при bind()
        }
    }
}

/**
 * Типы действий с туннелем
 */
enum class TunnelListAction {
    CLICK,      // клик по всей строке → подключить
    EDIT,       // нажатие на иконку редактирования
    DELETE      // нажатие на иконку удаления
}