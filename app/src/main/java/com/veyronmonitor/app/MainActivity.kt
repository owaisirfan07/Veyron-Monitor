package com.veyronmonitor.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.veyronmonitor.app.api.TumcApi
import com.veyronmonitor.app.api.UpdateChecker
import com.veyronmonitor.app.api.UpdateInfo
import com.veyronmonitor.app.data.CredentialStore
import com.veyronmonitor.app.data.EnergyStore
import com.veyronmonitor.app.data.ChargeSchedule
import com.veyronmonitor.app.data.ScheduleStore
import com.veyronmonitor.app.data.WarningLogStore
import com.veyronmonitor.app.schedule.AlarmScheduler
import com.veyronmonitor.app.model.AuthState
import com.veyronmonitor.app.model.Device
import com.veyronmonitor.app.ui.DashboardScreen
import com.veyronmonitor.app.ui.EnergyHistoryScreen
import com.veyronmonitor.app.ui.EnergyScreen
import com.veyronmonitor.app.ui.LoginScreen
import com.veyronmonitor.app.ui.ScheduleScreen
import com.veyronmonitor.app.ui.SettingsScreen
import com.veyronmonitor.app.ui.WarningsScreen
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

private enum class Screen { DASHBOARD, SETTINGS, ENERGY, HISTORY, SCHEDULE, WARNINGS }

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
    var isSavingParam by remember { mutableStateOf(false) }
    var saveParamError by remember { mutableStateOf<String?>(null) }

    var energyState by remember { mutableStateOf(EnergyStore.load(context)) }
    var schedules by remember { mutableStateOf(ScheduleStore.getAll(context)) }
    var hasUnreadWarnings by remember { mutableStateOf(WarningLogStore.hasUnread(context)) }

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

            // Refresh online/offline status for display purposes only --
            // it no longer gates energy accounting (see below), since the
            // flag can flap on and off while the house is still genuinely
            // running on solar/battery the whole time.
            try {
                val devices = withContext(Dispatchers.IO) { TumcApi.getDevices(currentAuth) }
                devices.firstOrNull { it.serialNumber == currentDevice.serialNumber }?.let {
                    device = it
                }
            } catch (_: Exception) {
                // non-critical -- keep showing the last known status
            }

            // Accumulate energy using the DEVICE'S OWN reported reading time
            // ("currentTime"), not our poll interval or the online flag.
            // This way: (1) a genuinely fresh reading always counts, even if
            // the app briefly shows "offline"; (2) a repeated/stale reading
            // (same timestamp as last time) is never double-counted.
            val deviceTimeMillis = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .parse(fresh.optString("currentTime", ""))?.time ?: 0L
            } catch (_: Exception) { 0L }

            if (deviceTimeMillis > 0L) {
                when {
                    energyState.lastDeviceTimestampMillis == 0L -> {
                        // First sample ever -- just record the baseline.
                        energyState = energyState.copy(lastDeviceTimestampMillis = deviceTimeMillis)
                        EnergyStore.save(context, energyState)
                    }
                    deviceTimeMillis > energyState.lastDeviceTimestampMillis -> {
                        // Genuinely new reading -- integrate over the actual
                        // device-reported gap (capped at 30 min as a sanity limit).
                        val elapsedHours = (deviceTimeMillis - energyState.lastDeviceTimestampMillis)
                            .coerceAtMost(30 * 60_000L) / 3_600_000.0
                        val solarWatts = fresh.optDouble("pvInputPower1", 0.0).takeIf { !it.isNaN() } ?: 0.0
                        val gridWatts = fresh.optDouble("gridPowerInputActiveTotal", 0.0).takeIf { !it.isNaN() } ?: 0.0
                        energyState = EnergyStore.addSample(context, energyState, solarWatts, gridWatts, elapsedHours)
                            .copy(lastDeviceTimestampMillis = deviceTimeMillis)
                        EnergyStore.save(context, energyState)
                    }
                    else -> {
                        // Same (or older) timestamp as last time -- stale/repeated
                        // reading, e.g. while reporting is briefly interrupted.
                        // Skip it entirely so it isn't counted twice.
                    }
                }
            }

            // Log any newly-appeared warning/fault codes for the Warnings tab.
            val warningArr = fresh.optJSONArray("warning")
            val warningCodes = if (warningArr != null) {
                (0 until warningArr.length()).map { warningArr.optString(it) }
            } else emptyList()
            val faultCode = fresh.opt("fault1")?.toString()
            WarningLogStore.recordActiveCodes(context, warningCodes, faultCode)
            hasUnreadWarnings = WarningLogStore.hasUnread(context)
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

    suspend fun setChargingPriority(value: String) {
        val currentAuth = auth ?: return
        val currentDevice = device ?: return
        isSavingParam = true
        saveParamError = null
        try {
            withContext(Dispatchers.IO) { TumcApi.setParam(currentAuth, currentDevice, "PC", value) }
            // Re-fetch so the UI reflects what the inverter actually confirmed,
            // not just what we optimistically assume was applied.
            params = withContext(Dispatchers.IO) { TumcApi.getParams(currentAuth, currentDevice) }
        } catch (e: Exception) {
            saveParamError = "Setting apply nahi ho saki: ${e.message ?: "network error"}"
        } finally {
            isSavingParam = false
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
                screen == Screen.HISTORY -> {
                    EnergyHistoryScreen(
                        records = EnergyStore.getHistory(context, energyState),
                        onBack = { screen = Screen.ENERGY }
                    )
                }
                screen == Screen.SCHEDULE -> {
                    ScheduleScreen(
                        schedules = schedules,
                        needsExactAlarmPermission = !AlarmScheduler.canScheduleExact(context),
                        onRequestExactAlarmPermission = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                context.startActivity(
                                    Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                        .setData(Uri.parse("package:${context.packageName}"))
                                )
                            }
                        },
                        onAdd = { newSchedule ->
                            schedules = ScheduleStore.add(context, newSchedule)
                            AlarmScheduler.schedule(context, newSchedule)
                        },
                        onToggle = { id, enabled ->
                            schedules = ScheduleStore.setEnabled(context, id, enabled)
                            val s = schedules.first { it.id == id }
                            if (enabled) AlarmScheduler.schedule(context, s) else AlarmScheduler.cancel(context, s)
                        },
                        onRemove = { id ->
                            val toCancel = schedules.firstOrNull { it.id == id }
                            schedules = ScheduleStore.remove(context, id)
                            toCancel?.let { AlarmScheduler.cancel(context, it) }
                        },
                        onBack = { screen = Screen.SETTINGS }
                    )
                }
                else -> {
                    // Main tabs, with a persistent bottom navigation bar.
                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                NavigationBarItem(
                                    selected = screen == Screen.DASHBOARD,
                                    onClick = { screen = Screen.DASHBOARD },
                                    icon = { Icon(Icons.Filled.Dashboard, contentDescription = "Dashboard") },
                                    label = { Text("Dashboard") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.ENERGY,
                                    onClick = { screen = Screen.ENERGY },
                                    icon = { Icon(Icons.Filled.QueryStats, contentDescription = "Energy") },
                                    label = { Text("Energy") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.WARNINGS,
                                    onClick = {
                                        screen = Screen.WARNINGS
                                        WarningLogStore.markAllViewed(context)
                                        hasUnreadWarnings = false
                                    },
                                    icon = {
                                        BadgedBox(badge = { if (hasUnreadWarnings) Badge() }) {
                                            Icon(Icons.Filled.Warning, contentDescription = "Warnings")
                                        }
                                    },
                                    label = { Text("Warnings") }
                                )
                                NavigationBarItem(
                                    selected = screen == Screen.SETTINGS,
                                    onClick = {
                                        screen = Screen.SETTINGS
                                        scope.launch { loadParams() }
                                    },
                                    icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                                    label = { Text("Settings") }
                                )
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                            when (screen) {
                                Screen.ENERGY -> EnergyScreen(
                                    solarWh = energyState.solarWh,
                                    gridWh = energyState.gridWh,
                                    solarSince = energyState.solarSince,
                                    gridSince = energyState.gridSince,
                                    onBack = { screen = Screen.DASHBOARD },
                                    onResetSolar = { energyState = EnergyStore.resetSolar(context, energyState) },
                                    onResetGrid = { energyState = EnergyStore.resetGrid(context, energyState) },
                                    onViewHistory = { screen = Screen.HISTORY }
                                )
                                Screen.WARNINGS -> WarningsScreen(
                                    entries = WarningLogStore.getLog(context)
                                )
                                Screen.SETTINGS -> SettingsScreen(
                                    deviceName = device?.displayName ?: "Inverter",
                                    params = params,
                                    isLoading = isLoadingParams,
                                    errorMessage = paramsError,
                                    isSaving = isSavingParam,
                                    saveError = saveParamError,
                                    onBack = { screen = Screen.DASHBOARD },
                                    onSetChargingPriority = { value ->
                                        scope.launch { setChargingPriority(value) }
                                    },
                                    onOpenSchedule = { screen = Screen.SCHEDULE }
                                )
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
                                        onOpenEnergy = { screen = Screen.ENERGY },
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
