/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.yellastrodev.rknvpn.activity

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CaptchaActivity : AppCompatActivity() {
    private var previousNetwork: Network? = null
    private var didBindNetwork = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val redirectUri = intent.getStringExtra(EXTRA_REDIRECT_URI)
        if (redirectUri.isNullOrEmpty()) {
            Log.e(TAG, "No redirect URI provided")
            deliverResult("")
            finish()
            return
        }

        bindToPhysicalNetwork()
        Log.d(TAG, "Loading captcha page...")

        val webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

            addJavascriptInterface(CaptchaBridge(), "AndroidCaptcha")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(INTERCEPT_SCRIPT, null)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false
            }
        }

        setContentView(webView)
        webView.loadUrl(redirectUri)
    }

    private fun deliverResult(token: String) {
        pendingResult?.complete(token)
    }

    private inner class CaptchaBridge {
        @JavascriptInterface
        fun onResult(successToken: String) {
            Log.d(TAG, "Captcha solved, got success_token (length=${successToken.length})")
            runOnUiThread {
                deliverResult(successToken)
                finish()
            }
        }
    }

    private fun bindToPhysicalNetwork() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            previousNetwork = cm.boundNetworkForProcess

            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

                cm.bindProcessToNetwork(network)
                didBindNetwork = true
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                    else -> "Other"
                }
                Log.d(TAG, "Bound process to physical network: $network ($type)")
                return
            }
            Log.w(TAG, "No physical network found to bind to!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind to physical network", e)
        }
    }

    private fun restoreNetworkBinding() {
        if (!didBindNetwork) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(previousNetwork)
            Log.d(TAG, "Restored previous network binding")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore network binding", e)
        }
    }

    override fun onDestroy() {
        restoreNetworkBinding()
        super.onDestroy()
        deliverResult("")
    }

    companion object {
        private const val TAG = "WireGuard/CaptchaActivity"
        private const val EXTRA_REDIRECT_URI = "redirect_uri"
        private const val CAPTCHA_TIMEOUT_SECONDS = 120L

        @Volatile
        private var pendingResult: CompletableFuture<String>? = null

        private val INTERCEPT_SCRIPT = """
            (function() {
                var origOpen = XMLHttpRequest.prototype.open;
                var origSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.open = function() {
                    this._captchaUrl = arguments[1];
                    return origOpen.apply(this, arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                    var xhr = this;
                    if (xhr._captchaUrl && xhr._captchaUrl.indexOf('captchaNotRobot.check') !== -1) {
                        xhr.addEventListener('load', function() {
                            try {
                                var data = JSON.parse(xhr.responseText);
                                if (data.response && data.response.success_token) {
                                    AndroidCaptcha.onResult(data.response.success_token);
                                }
                            } catch(e) {}
                        });
                    }
                    return origSend.apply(this, arguments);
                };

                var origFetch = window.fetch;
                if (origFetch) {
                    window.fetch = function() {
                        var url = arguments[0];
                        if (typeof url === 'object' && url.url) url = url.url;
                        var p = origFetch.apply(this, arguments);
                        if (typeof url === 'string' && url.indexOf('captchaNotRobot.check') !== -1) {
                            p.then(function(response) {
                                return response.clone().json();
                            }).then(function(data) {
                                if (data.response && data.response.success_token) {
                                    AndroidCaptcha.onResult(data.response.success_token);
                                }
                            }).catch(function(e) {});
                        }
                        return p;
                    };
                }

                window.addEventListener('message', function(e) {
                    try {
                        var data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                        if (data && data.success_token) {
                            AndroidCaptcha.onResult(data.success_token);
                        } else if (data && data.response && data.response.success_token) {
                            AndroidCaptcha.onResult(data.response.success_token);
                        }
                    } catch(ex) {}
                });
            })();
        """.trimIndent()

        fun solveCaptcha(context: Context, redirectUri: String): String {
            Log.d(TAG, "solveCaptcha called, launching activity...")

            val future = CompletableFuture<String>()
            pendingResult = future

            val intent = Intent(context, CaptchaActivity::class.java).apply {
                putExtra(EXTRA_REDIRECT_URI, redirectUri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            return try {
                val result = future.get(CAPTCHA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                Log.d(TAG, "solveCaptcha result: ${if (result.isNotEmpty()) "token" else "empty"}")
                result
            } catch (e: Exception) {
                Log.e(TAG, "solveCaptcha failed", e)
                ""
            } finally {
                pendingResult = null
            }
        }
    }
}
