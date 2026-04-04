/*
 * Copyright © 2017-2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.yellastrodev.rnkvpn.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ViewTunnelState {
    object Idle : ViewTunnelState()       // Неактивно
    object Connecting : ViewTunnelState() // Соединение
    object Active : ViewTunnelState()     // Активен
    data class Error(val message: String) : ViewTunnelState() // Опционально: ошибка
}

class TunnelViewModel : ViewModel() {

    // Внутреннее состояние (мутабельное)
    private val _state = MutableStateFlow<ViewTunnelState>(ViewTunnelState.Idle)

    // Публичное состояние (только для чтения)
    val state: StateFlow<ViewTunnelState> = _state.asStateFlow()

    // Метод для обновления состояния извне (например, из сервиса туннеля)
    fun updateState(newState: ViewTunnelState) {
        _state.value = newState
    }
}