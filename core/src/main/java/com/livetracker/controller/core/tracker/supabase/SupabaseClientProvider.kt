package com.livetracker.controller.core.tracker.supabase

import com.livetracker.controller.core.BuildConfig
import com.livetracker.controller.core.security.SecureStorage
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.SessionManager
import io.github.jan.supabase.gotrue.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

/**
 * Persists Supabase [UserSession] across process lifecycles and cold starts
 * using hardware-backed [SecureStorage] (EncryptedSharedPreferences).
 */
class SecureStorageSessionManager(private val storage: SecureStorage) : SessionManager {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saveSession(session: UserSession) {
        runCatching {
            val encoded = json.encodeToString(UserSession.serializer(), session)
            storage.putString(KEY_SESSION, encoded)
        }
    }

    override suspend fun loadSession(): UserSession? {
        return runCatching {
            storage.getString(KEY_SESSION)?.takeIf { it.isNotBlank() }?.let {
                json.decodeFromString(UserSession.serializer(), it)
            }
        }.getOrNull()
    }

    override suspend fun deleteSession() {
        runCatching {
            storage.putString(KEY_SESSION, null)
        }
    }

    companion object {
        const val KEY_SESSION = "trace_supabase_user_session"
    }
}

/**
 * Provides a shared, thread-safe [SupabaseClient] configured to communicate
 * with the tracker fleet backend with required security headers and Realtime intervals.
 */
object SupabaseClientProvider {
    val supabaseUrl = BuildConfig.SUPABASE_URL
    val supabaseKey = BuildConfig.SUPABASE_KEY

    @Volatile
    private var secureStorage: SecureStorage? = null

    /**
     * Initializes the underlying [SecureStorage] instance used by GoTrue [SessionManager].
     * If omitted (e.g. in unit tests), GoTrue defaults to an ephemeral in-memory session manager.
     */
    fun init(storage: SecureStorage) {
        secureStorage = storage
    }

    val client: SupabaseClient by lazy {
        createSupabaseClient(supabaseUrl, supabaseKey) {
            requestTimeout = 60.seconds
            httpEngine = OkHttp.create {
                config {
                    connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            install(Auth) {
                secureStorage?.let { storage ->
                    sessionManager = SecureStorageSessionManager(storage)
                }
            }
            install(Postgrest)
            install(Realtime) {
                reconnectDelay = 2.seconds
                heartbeatInterval = 15.seconds
            }
        }
    }
}
