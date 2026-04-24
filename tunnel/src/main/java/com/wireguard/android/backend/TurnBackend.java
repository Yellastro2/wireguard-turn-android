/*
 * Copyright © 2026.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import android.net.VpnService;
import androidx.annotation.Nullable;
import android.util.Log;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Native interface for TURN proxy management.
 */
public final class TurnBackend {
    public static final int WG_TURN_PROXY_SUCCESS = 0;
    public static final int WG_TURN_PROXY_ERROR_GENERIC = -1;
    public static final int WG_TURN_PROXY_ERROR_VK_LINK_EXPIRED = -9000;

    private static final AtomicReference<CompletableFuture<VpnService>> vpnServiceFutureRef = new AtomicReference<>(new CompletableFuture<>());

    // Latch for synchronization: signals that JNI is registered and ready to protect sockets
    private static final AtomicReference<CountDownLatch> vpnServiceLatchRef = new AtomicReference<>(new CountDownLatch(1));

    // Captcha handler: called when automatic captcha solving fails and WebView is needed
    private static volatile Function<String, String> captchaHandler;

    private TurnBackend() {
    }

    /**
     * Registers the VpnService instance and notifies the native layer.
     * @param service The VpnService instance.
     */
    public static void onVpnServiceCreated(@Nullable VpnService service) {
        Log.d(TAG, "onVpnServiceCreated called with service=" + (service != null ? "non-null" : "null"));

        if (service != null) {
            // 1. First set in JNI so sockets can be protected
            Log.d(TAG, "Calling wgSetVpnService()...");
            wgSetVpnService(service);
            Log.d(TAG, "wgSetVpnService() complete");

            // 2. Count down latch — JNI is ready to protect sockets
            vpnServiceLatchRef.get().countDown();
            Log.d(TAG, "vpnServiceLatchRef.countDown()");

            // 3. Then complete Future for Java code
            CompletableFuture<VpnService> currentFuture = vpnServiceFutureRef.getAndSet(new CompletableFuture<>());
            if (!currentFuture.isDone()) {
                currentFuture.complete(service);
                Log.d(TAG, "VpnService future completed");
            } else {
                // Old future already completed — complete the new one
                CompletableFuture<VpnService> newFuture = vpnServiceFutureRef.get();
                if (!newFuture.isDone()) {
                    newFuture.complete(service);
                    Log.d(TAG, "VpnService future completed (replacement)");
                }
            }
        } else {
            // Service destroyed - reset everything for next cycle
            Log.d(TAG, "VpnService destroyed, resetting future and latch");

            wgTurnProxyStop();

            wgSetVpnService(null);
            vpnServiceFutureRef.set(new CompletableFuture<>());
            vpnServiceLatchRef.set(new CountDownLatch(1));  // Recreate latch for next launch
        }
    }

    /**
     * Returns a future that completes when the VpnService is created.
     */
    public static CompletableFuture<VpnService> getVpnServiceFuture() {
        return vpnServiceFutureRef.get();
    }
    
    /**
     * Waits until the VpnService is registered in JNI and ready to protect sockets.
     * @param timeout Maximum time to wait in milliseconds
     * @return true if successfully registered, false on timeout or interrupt
     */
    public static boolean waitForVpnServiceRegistered(long timeout) {
        try {
            CountDownLatch latch = vpnServiceLatchRef.get();
            boolean success = latch.await(timeout, TimeUnit.MILLISECONDS);
            Log.d(TAG, "waitForVpnServiceRegistered: " + (success ? "SUCCESS" : "TIMEOUT (" + timeout + "ms)"));
            return success;
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for VpnService registration", e);
            Thread.currentThread().interrupt();  // Restore interrupt flag
            return false;
        }
    }

    public static void setCaptchaHandler(@Nullable Function<String, String> handler) {
        captchaHandler = handler;
        Log.d(TAG, "Captcha handler " + (handler != null ? "registered" : "cleared"));
    }

    @SuppressWarnings("unused") // Called from native code
    public static String onCaptchaRequired(String redirectUri) {
        Log.d(TAG, "onCaptchaRequired called with URI length=" + (redirectUri != null ? redirectUri.length() : 0));
        Function<String, String> handler = captchaHandler;
        if (handler == null) {
            Log.e(TAG, "No captcha handler registered!");
            return "";
        }
        try {
            String result = handler.apply(redirectUri);
            Log.d(TAG, "Captcha handler returned: " + (result != null && !result.isEmpty() ? "token" : "empty"));
            return result != null ? result : "";
        } catch (Exception e) {
            Log.e(TAG, "Captcha handler threw exception", e);
            return "";
        }
    }

    public static native void wgSetVpnService(@Nullable VpnService service);

    /**
     * Starts the TURN proxy.
     *
     * @param peerAddr    WireGuard server address (ip:port)
     * @param vklink      VK call join link (ignored in WB mode, pass empty string)
     * @param mode        Credential mode: "vk" or "wb"
     * @param n           Number of parallel streams
     * @param useUdp      1 = UDP transport to TURN, 0 = TCP
     * @param listenAddr  Local UDP listen address (e.g. "127.0.0.1:51820")
     * @param turnIp      Override TURN server IP (empty string = use from credentials)
     * @param turnPort    Override TURN server port (0 = use from credentials)
     * @param peerType    "proxy_v2", "proxy_v1", or "wireguard"
     * @param streamsPerCred Number of streams sharing one TURN credential cache
     * @param watchdogTimeout DTLS watchdog timeout in seconds, 0 to disable
     * @param networkHandle Android network handle for socket binding
     * @return 0 on success, -1 on generic failure, -9000 when VK reports that the call link expired
     */
    public static native int wgTurnProxyStart(
            String peerAddr,
            String vklink,
            String mode,
            int n,
            int useUdp,
            String listenAddr,
            String turnIp,
            int turnPort,
            String peerType,
            int streamsPerCred,
            int watchdogTimeout,
            long networkHandle
    );
    public static native void wgTurnProxyStop();
    public static native void wgNotifyNetworkChange();
    public static native String wgGetNetworkDnsServers(long networkHandle);
    
    private static final String TAG = "WireGuard/TurnBackend";
}
