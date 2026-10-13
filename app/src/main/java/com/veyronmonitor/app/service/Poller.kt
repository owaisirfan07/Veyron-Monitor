package com.veyronmonitor.app.service

import android.content.Context
import com.veyronmonitor.app.api.TumcApi
import com.veyronmonitor.app.data.CredentialStore
import com.veyronmonitor.app.data.EnergyStore
import com.veyronmonitor.app.data.WarningLogStore
import com.veyronmonitor.app.model.AuthState
import com.veyronmonitor.app.model.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * One shared "fetch a reading and count it" routine, used by the open app,
 * the background service and the WorkManager backup job. Keeps one login
 * session in memory and logs in again by itself if the token expires.
 */
object Poller {
    @Volatile var auth: AuthState? = null
    @Volatile var device: Device? = null

    private val _latest = MutableStateFlow<JSONObject?>(null)
    /** The most recent reading from any source (app, service or worker). */
    val latest: StateFlow<JSONObject?> = _latest.asStateFlow()

    private val mutex = Mutex()

    fun setSession(a: AuthState?, d: Device?) { auth = a; device = d }

    fun clear() { auth = null; device = null; _latest.value = null }

    private fun login(context: Context): Boolean {
        val (u, p) = CredentialStore.load(context) ?: return false
        val a = TumcApi.login(u, p)
        val devices = TumcApi.getDevices(a)
        if (devices.isEmpty()) return false
        val keep = device?.serialNumber
        auth = a
        device = devices.firstOrNull { it.serialNumber == keep } ?: devices.first()
        return true
    }

    /**
     * Blocking (call from IO). Fetches the live reading, feeds it to the
     * energy meter and the warnings log. Returns the reading, or throws.
     */
    fun fetchOnce(context: Context): JSONObject {
        if (auth == null || device == null) {
            if (!login(context)) throw IllegalStateException("Not logged in")
        }
        val json = try {
            TumcApi.getRealData(auth!!, device!!)
        } catch (e: TumcApi.ApiException) {
            // Token expired or similar - log in again once and retry.
            if (!login(context)) throw e
            TumcApi.getRealData(auth!!, device!!)
        }
        handle(context, json)
        return json
    }

    suspend fun fetchOnceLocked(context: Context): JSONObject = mutex.withLock { fetchOnce(context) }

    // ---------------- inverter sync ----------------
    // Same as the Sync button in the i.Solar app: send the dongle the command
    // FORCE_TRAN=0 ("upload your data now"), wait 5 s, then read the fresh data.

    sealed class SyncState {
        object Idle : SyncState()
        object Running : SyncState()
        data class Done(val at: Long, val auto: Boolean) : SyncState()
        data class Failed(val message: String) : SyncState()
    }

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _sync.asStateFlow()
    @Volatile var lastSyncAt = 0L
        private set

    private fun syncError(e: Exception): String = when ((e as? TumcApi.ApiException)?.code) {
        899004 -> "Inverter ne waqt par jawab nahi diya"
        899003 -> "Sync command fail hui"
        799011 -> "Inverter abhi command nahi le raha (shayad offline)"
        799001 -> "Device serial number nahi mila"
        else -> e.message ?: "network error"
    }

    /** Blocking (call from IO). Returns the fresh reading, or null if sync failed. */
    suspend fun sync(context: Context, auto: Boolean): JSONObject? {
        if (_sync.value == SyncState.Running) return null
        _sync.value = SyncState.Running
        lastSyncAt = System.currentTimeMillis()
        return try {
            mutex.withLock {
                if (auth == null || device == null) {
                    if (!login(context)) throw IllegalStateException("Not logged in")
                }
                try {
                    TumcApi.setParam(auth!!, device!!, "FORCE_TRAN", "0")
                } catch (e: TumcApi.ApiException) {
                    // codes 7xxxxx / 8xxxxx are inverter answers; anything else may be an expired login
                    if (e.code >= 700000) throw e
                    if (!login(context)) throw e
                    TumcApi.setParam(auth!!, device!!, "FORCE_TRAN", "0")
                }
            }
            kotlinx.coroutines.delay(5_000) // the i.Solar app waits 5 s too
            val json = fetchOnceLocked(context)
            _sync.value = SyncState.Done(System.currentTimeMillis(), auto)
            json
        } catch (e: kotlinx.coroutines.CancellationException) {
            _sync.value = SyncState.Idle; throw e
        } catch (e: Exception) {
            _sync.value = SyncState.Failed(syncError(e))
            null
        }
    }

    /** Minutes since the newest reading was taken by the inverter (null if unknown). */
    fun dataAgeMinutes(): Long? {
        val t = _latest.value?.let { EnergyStore.readingTime(it) } ?: return null
        if (t <= 0) return null
        return ((System.currentTimeMillis() - t) / 60_000L).coerceAtLeast(0)
    }

    /** Count a reading that some other code fetched. */
    @Synchronized
    fun handle(context: Context, json: JSONObject) {
        _latest.value = json
        EnergyStore.process(context, json)
        val warningArr = json.optJSONArray("warning")
        val warningCodes = if (warningArr != null) (0 until warningArr.length()).map { warningArr.optString(it) } else emptyList()
        WarningLogStore.recordActiveCodes(context, warningCodes, json.opt("fault1")?.toString())
    }
}
