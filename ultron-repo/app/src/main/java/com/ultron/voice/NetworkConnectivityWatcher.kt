package com.ultron.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Backs VoiceAssistantController's online/offline switch. Checks for an
 * actual validated internet path (not just "some network interface exists") —
 * a phone connected to Wi-Fi with no internet still counts as offline here.
 */
class NetworkConnectivityWatcher(context: Context) : ConnectivityWatcher {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override val isOnline: Boolean
        get() {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
}
