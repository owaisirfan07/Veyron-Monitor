package com.veyronmonitor.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.api.TumcApi
import com.veyronmonitor.app.api.UpdateChecker
import com.veyronmonitor.app.api.UpdateInfo
import com.veyronmonitor.app.data.CredentialStore
import com.veyronmonitor.app.model.AuthState
import com.veyronmonitor.app.model.Device
import com.veyronmonitor.app.ui.DashboardScreen
import com.veyronmonitor.app.ui.LoginScreen
import com.veyronmonitor.app.ui.SettingsScreen
import com.veyronmonitor.app.ui.VeyronMonitorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// How often we re-poll the cloud while the dashboard is open. The dongle
// itself only pushes new data to the cloud every so often, but polling
// this often means the moment new data lands, you see it almost instantly
// instead of waiting on a slow app refresh cycle.
private const val REFRESH_INTERVAL_MS = 15_000L

// Online/offline status changes less often than live readings, so we only
// re-check the device list (which carries onlineStatus) every few polls
// instead of on every single tick, to keep things light.
private const val DEVICE_STATUS_EVERY_N_POLLS = 4

private enum class Screen { DASHBOARD, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VeyronMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var auth by remember { mutableStateOf<AuthState?>(null) }
    var device by remember { mutableStateOf<Device?>(null) }
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastUpdatedAt by remember { mutableStateOf<Long?>(null) }
    var pollCount by remember { mutableStateOf(0) }

    var screen by remember { mutableStateOf(Screen.DASHBOARD) }
    var params by remember { mutableStateOf<JSONObject?>(null) }
    var isLoadingParams by remember { mutableStateOf(false) }
    var paramsError by remember { mutableStateOf<String?>(null) }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }

    // Check GitHub for a newer build once, in the background. Never blocks
    // or interrupts anything -- if it fails or finds nothing newer, it's silent.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            updateInfo = UpdateChecker.checkForUpdate(BuildConfig.VERSION_CODE)
        }
    }

    fun attemptLogin(username: String, password: String) {
        isLoading = true
        errorMessage = null
        scope.launch {
            try {
                val authResult = withContext(Dispatchers.IO) {
                    TumcApi.login(username, password)
                }
                val devices = withContext(Dispatchers.IO) {
                    TumcApi.getDevices(authResult)
                }
                if (devices.isEmpty()) {
                    errorMessage = "Koi device is account se register nahi mila."
                    isLoading = false
                    return@launch
                }
                CredentialStore.save(context, username, password)
                auth = authResult
                device = devices.first()
            } catch (e: Exception) {
                errorMessage = "Login fail hua: ${e.message ?: "network error"}"
            } finally {
                isLoading = false
            }
        }
    }

    suspend fun refreshData() {
        val currentAuth = auth ?: return
        val currentDevice = device ?: return
        isRefreshing = true
        try {
            val fresh = withContext(Dispatchers.IO) {
                TumcApi.getRealData(currentAuth, currentDevice)
            }
            data = fresh
            lastUpdatedAt = System.currentTimeMillis()
            errorMessage = null

            // Periodically re-check the device list to refresh online/offline
            // status (it doesn't come back with the live-data endpoint).
            if (pollCount % DEVICE_STATUS_EVERY_N_POLLS == 0) {
                try {
                    val devices = withContext(Dispatchers.IO) { TumcApi.getDevices(currentAuth) }
                    devices.firstOrNull { it.serialNumber == currentDevice.serialNumber }?.let {
                        device = it
                    }
                } catch (_: Exception) {
                    // non-critical -- keep showing the last known status
                }
            }
            pollCount++
        } catch (e: Exception) {
            errorMessage = "Update fail: ${e.message ?: "network error"}"
        } finally {
            isRefreshing = false
        }
    }

    suspend fun loadParams() {
        val currentAuth = auth ?: return
        val currentDevice = device ?: return
        isLoadingParams = true
        paramsError = null
        try {
            params = withContext(Dispatchers.IO) { TumcApi.getParams(currentAuth, currentDevice) }
        } catch (e: Exception) {
            paramsError = "Parameters load nahi ho sake: ${e.message ?: "network error"}"
        } finally {
            isLoadingParams = false
        }
    }

    // Try auto-login from saved credentials once, on first composition.
    LaunchedEffect(Unit) {
        val saved = CredentialStore.load(context)
        if (saved != null && auth == null) {
            val (u, p) = saved
            isLoading = true
            try {
                val authResult = withContext(Dispatchers.IO) { TumcApi.login(u, p) }
                val devices = withContext(Dispatchers.IO) { TumcApi.getDevices(authResult) }
                if (devices.isNotEmpty()) {
                    auth = authResult
                    device = devices.first()
                }
            } catch (_: Exception) {
                // silent fail -> fall back to manual login screen
            } finally {
                isLoading = false
            }
        }
    }

    // Poll loop while logged in.
    LaunchedEffect(auth, device?.serialNumber) {
        if (auth != null && device != null) {
            while (true) {
                refreshData()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        updateInfo?.let { info ->
            UpdateBanner(
                info = info,
                onDismiss = { updateInfo = null },
                onUpdate = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.apkDownloadUrl)))
                }
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                auth == null || device == null -> {
                    LoginScreen(
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onLogin = ::attemptLogin
                    )
                }
                screen == Screen.SETTINGS -> {
                    SettingsScreen(
                        deviceName = device?.displayName ?: "Inverter",
                        params = params,
                        isLoading = isLoadingParams,
                        errorMessage = paramsError,
                        onBack = { screen = Screen.DASHBOARD }
                    )
                }
                else -> {
                    val lastUpdatedText = lastUpdatedAt?.let {
                        SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(it))
                    } ?: "--"

                    DashboardScreen(
                        deviceName = device?.displayName ?: "Inverter",
                        isOnline = device?.isOnline ?: false,
                        data = data,
                        lastUpdatedText = lastUpdatedText,
                        isRefreshing = isRefreshing,
                        errorMessage = errorMessage,
                        onRefresh = { scope.launch { refreshData() } },
                        onOpenSettings = {
                            screen = Screen.SETTINGS
                            scope.launch { loadParams() }
                        },
                        onLogout = {
                            CredentialStore.clear(context)
                            auth = null
                            device = null
                            data = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(info: UpdateInfo, onDismiss: () -> Unit, onUpdate: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Nayi version available: ${info.versionLabel}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row {
                TextButton(onClick = onDismiss) { Text("Baad mein") }
                Button(onClick = onUpdate) { Text("Update") }
            }
        }
    }
}
