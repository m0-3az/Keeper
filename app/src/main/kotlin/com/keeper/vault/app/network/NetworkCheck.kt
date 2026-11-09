package com.keeper.vault.app.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings

/** Read-only snapshot. Keeper never attempts to change any of these. */
data class NetworkStatus(
    val wifiConnected: Boolean,
    val mobileConnected: Boolean,
    val vpnActive: Boolean,
    val airplaneMode: Boolean,
)

/** Settings targets Keeper can open. These are public intents, not toggles. */
enum class NetworkSettingTarget(val label: String, val action: String) {
    WIFI("Wi-Fi settings", Settings.ACTION_WIFI_SETTINGS),
    BLUETOOTH("Bluetooth settings", Settings.ACTION_BLUETOOTH_SETTINGS),
    NFC("NFC settings", Settings.ACTION_NFC_SETTINGS),
    MOBILE("Mobile data / wireless settings", Settings.ACTION_WIRELESS_SETTINGS),
    HOTSPOT("Hotspot / tethering settings", Settings.ACTION_WIRELESS_SETTINGS),
    VPN("VPN settings", Settings.ACTION_VPN_SETTINGS),
    AIRPLANE("Airplane mode settings", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
    ;

    fun intent(): Intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

fun interface NetworkStatusSource {
    fun read(): NetworkStatus
}

/**
 * Best-effort read using the already-present ACCESS_NETWORK_STATE permission. No API here writes
 * a radio state; there are deliberately no calls to WifiManager, BluetoothAdapter, NfcAdapter, or
 * telephony toggles.
 */
class AndroidNetworkStatusSource(context: Context) : NetworkStatusSource {
    private val appContext = context.applicationContext

    override fun read(): NetworkStatus {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        val capabilities = active?.let { manager.getNetworkCapabilities(it) }
        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val mobile = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true ||
            manager?.allNetworks?.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            } == true
        val airplane = Settings.Global.getInt(
            appContext.contentResolver,
            Settings.Global.AIRPLANE_MODE_ON,
            0,
        ) == 1
        return NetworkStatus(
            wifiConnected = wifi,
            mobileConnected = mobile,
            vpnActive = vpn,
            airplaneMode = airplane,
        )
    }
}

const val NETWORK_CHECK_DISCLAIMER: String =
    "Keeper cannot toggle or restore these settings. You must change them yourself. " +
        "Keeper has no INTERNET permission and does not upload anything."

fun onOff(value: Boolean, on: String = "Connected", off: String = "Not connected"): String =
    if (value) on else off
