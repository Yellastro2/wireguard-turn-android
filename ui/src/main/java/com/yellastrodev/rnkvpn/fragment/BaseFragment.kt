/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yellastrodev.rknvpn.fragment

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.yellastrodev.rknvpn.Application
import com.yellastrodev.rknvpn.R
import com.yellastrodev.rknvpn.activity.BaseActivity
import com.yellastrodev.rknvpn.activity.BaseActivity.OnSelectedTunnelChangedListener
import com.yellastrodev.rnkvpn.rnkutils.CallJoinSource
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.yellastrodev.rknvpn.databinding.TunnelDetailFragmentBinding
import com.yellastrodev.rknvpn.databinding.TunnelListItemBinding
import com.yellastrodev.rknvpn.model.ObservableTunnel
import com.yellastrodev.rknvpn.util.ErrorMessages
import kotlinx.coroutines.launch

/**
 * Base class for fragments that need to know the currently-selected tunnel. Only does anything when
 * attached to a `BaseActivity`.
 */
abstract class BaseFragment : Fragment(), OnSelectedTunnelChangedListener {
    private var pendingTunnel: ObservableTunnel? = null
    private var pendingTunnelUp: Boolean? = null
    private val permissionActivityResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val tunnel = pendingTunnel
        val checked = pendingTunnelUp
        if (tunnel != null && checked != null)
            setTunnelStateWithPermissionsResult(tunnel, checked)
        pendingTunnel = null
        pendingTunnelUp = null
    }

    protected var selectedTunnel: ObservableTunnel?
        get() = (activity as? BaseActivity)?.selectedTunnel
        protected set(tunnel) {
            (activity as? BaseActivity)?.selectedTunnel = tunnel
        }

    protected var citizennKey: String?
        get() = (activity as? BaseActivity)?.citizennKey
        protected set(key) {
            (activity as? BaseActivity)?.citizennKey = key
        }

    /**
     * currentCallJoinSource exposes the structured call source stored by the parent activity.
     */
    protected val currentCallJoinSource: CallJoinSource?
        get() = (activity as? BaseActivity)?.currentCallJoinSource


    override fun onAttach(context: Context) {
        super.onAttach(context)
        (activity as? BaseActivity)?.addOnSelectedTunnelChangedListener(this)
    }

    override fun onDetach() {
        (activity as? BaseActivity)?.removeOnSelectedTunnelChangedListener(this)
        super.onDetach()
    }

    suspend fun checkTunnelPermission(view: View): Boolean {
        val activity = activity ?: return false

        if (Application.getBackend() is GoBackend) {
            try {
                val intent = GoBackend.VpnService.prepare(activity)
                if (intent != null) {
                    permissionActivityResultLauncher.launch(intent)
                    return false
                }
            } catch (e: Throwable) {
                val message = activity.getString(R.string.error_prepare, ErrorMessages[e])
                Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                    .setAnchorView(view.findViewById(R.id.create_fab))
                    .show()
                Log.e(TAG, message, e)
                return false
            }
        }
        return true
    }

    fun setTunnelState(view: View, checked: Boolean) {
        val tunnel = when (val binding = DataBindingUtil.findBinding<ViewDataBinding>(view)) {
            is TunnelDetailFragmentBinding -> binding.tunnel
            is TunnelListItemBinding -> binding.item
            else -> return
        } ?: return
        val activity = activity ?: return
        activity.lifecycleScope.launch {
            if (!checkTunnelPermission(view)) return@launch
            setTunnelStateWithPermissionsResult(tunnel, checked)
        }
    }

    private fun setTunnelStateWithPermissionsResult(tunnel: ObservableTunnel, checked: Boolean) {
        val activity = activity ?: return
        activity.lifecycleScope.launch {
            try {
                tunnel.setStateAsync(Tunnel.State.of(checked))
            } catch (e: Throwable) {
                val error = ErrorMessages[e]
                val messageResId = if (checked) R.string.error_up else R.string.error_down
                val message = activity.getString(messageResId, error)
                val view = view
                if (view != null)
                    Snackbar.make(view, message, Snackbar.LENGTH_LONG)
                        .setAnchorView(view.findViewById(R.id.create_fab))
                        .show()
                else
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                Log.e(TAG, message, e)
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/BaseFragment"
    }
}
