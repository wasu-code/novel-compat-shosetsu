package eu.kanade.tachiyomi.novelextension.all.ireader

import android.app.Application
import android.content.SharedPreferences
import androidx.core.content.edit
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import ireader.core.http.BrowserEngine
import ireader.core.http.HttpClients
import ireader.core.http.NetworkConfig
import ireader.core.http.WebViewCookieJar
import ireader.core.http.WebViewManger
import ireader.core.prefs.Preference
import ireader.core.prefs.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import io.ktor.http.Cookie as KtorCookie
import ireader.core.source.CatalogSource as IReaderCatalogueSource
import ireader.core.source.HttpSource as IReaderHttpSource
import okhttp3.Cookie as OkHttpCookie

class IReaderFactory : SourceFactory {
    private val hostContext by lazy { Injekt.get<Application>() }
    private val nh = Injekt.get<NetworkHelper>()
    private val cookiesStorage: CookiesStorage by lazy {
        OkHttpCookiesStorage(nh.client.cookieJar)
    }

    private val extensions by lazy {
        runBlocking {
            AndroidCatalogLoader(
                hostContext,
                HttpClients(
                    context = hostContext,
                    browseEngine = BrowserEngine(),
                    cookiesStorage = cookiesStorage,
                    webViewCookieJar = WebViewCookieJar(
                        cookiesStorage = cookiesStorage,
                    ),
                    preferencesStore = PreferenceStore(),
                    webViewManager = WebViewManger(
                        context = hostContext,
                    ),
                    networkConfig = NetworkConfig(),
                ),
            ).loadAll()
        }
            .also { ExtensionRegistry.installed.addAll(it.filterIsInstance<CatalogInstalled>()) }
    }

    override fun createSources(): List<Source> = buildList {
//            CatalogueSourceAdapter(TestSource()).also(::add)
        IReaderSettings().also(::add)
        runCatching {
            extensions.forEach { item ->
                when (val source = item.source) {
                    is IReaderHttpSource ->
                        add(HttpSourceAdapter(source))

                    is IReaderCatalogueSource ->
                        add(CatalogueSourceAdapter(source))

                    else ->
                        error("Unknown source type")
                }
            }
        }
    }
}

// ============================================================================

/**
 * Bridges the host app's OkHttp CookieJar into IReader's ktor-based CookiesStorage.
 *
 * IReader extensions call HttpClients for network requests; those go through ktor,
 * which uses CookiesStorage. By backing it with the host's NetworkHelper CookieJar
 * we get shared cookie state across both HTTP stacks (e.g. login sessions work).
 */
class OkHttpCookiesStorage(
    private val cookieJar: CookieJar,
) : CookiesStorage {

    override suspend fun get(requestUrl: Url): List<KtorCookie> {
        val okHttpUrl = requestUrl.toString().toHttpUrlOrNull() ?: return emptyList()
        return cookieJar.loadForRequest(okHttpUrl).map { it.toKtorCookie() }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: KtorCookie) {
        val okHttpUrl = requestUrl.toString().toHttpUrlOrNull() ?: return
        val okHttpCookie = cookie.toOkHttpCookie(okHttpUrl) ?: return
        cookieJar.saveFromResponse(okHttpUrl, listOf(okHttpCookie))
    }

    override fun close() {
        // CookieJar is owned by NetworkHelper; not ours to close
    }
}

private fun OkHttpCookie.toKtorCookie(): KtorCookie = KtorCookie(
    name = name,
    value = value,
    domain = domain,
    path = path,
    secure = secure,
    httpOnly = httpOnly,
    // OkHttp stores absolute expiry as epoch millis; ktor uses max-age in seconds
    maxAge = if (persistent) ((expiresAt - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0) else -1,
    encoding = CookieEncoding.RAW,
)

private fun KtorCookie.toOkHttpCookie(requestUrl: okhttp3.HttpUrl): OkHttpCookie? {
    val builder = OkHttpCookie.Builder()
        .name(name)
        .value(value)
        .path(path ?: "/")

    // Domain: ktor may omit it (means request-host only)
    val cookieDomain = domain ?: requestUrl.host
    builder.domain(cookieDomain)

    if (secure) builder.secure()
    if (httpOnly) builder.httpOnly()

    when {
        maxAge != null && maxAge!! > 0 ->
            builder.expiresAt(System.currentTimeMillis() + maxAge!! * 1000L)
        maxAge != null && maxAge!! == 0 ->
            builder.expiresAt(0L) // expired / delete signal
        // maxAge == -1 means session cookie → no expiresAt → OkHttp treats as session cookie
    }

    return runCatching { builder.build() }.getOrNull()
}

// ===

class PreferenceStore(name: String = "source_ireader") : PreferenceStore {

    private val prefs: SharedPreferences by lazy {
        Injekt.get<Application>()
            .getSharedPreferences(name, 0x0000)
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun getString(key: String, defaultValue: String) = createPref(
        key,
        defaultValue,
        { prefs.getString(key, defaultValue) ?: defaultValue },
        { prefs.edit { putString(key, it) } },
    )

    override fun getLong(key: String, defaultValue: Long) = createPref(
        key,
        defaultValue,
        { prefs.getLong(key, defaultValue) },
        { prefs.edit { putLong(key, it) } },
    )

    override fun getInt(key: String, defaultValue: Int) = createPref(
        key,
        defaultValue,
        { prefs.getInt(key, defaultValue) },
        { prefs.edit { putInt(key, it) } },
    )

    override fun getFloat(key: String, defaultValue: Float) = createPref(
        key,
        defaultValue,
        { prefs.getFloat(key, defaultValue) },
        { prefs.edit { putFloat(key, it) } },
    )

    override fun getBoolean(key: String, defaultValue: Boolean) = createPref(
        key,
        defaultValue,
        { prefs.getBoolean(key, defaultValue) },
        { prefs.edit { putBoolean(key, it) } },
    )

    override fun getStringSet(
        key: String,
        defaultValue: Set<String>,
    ) = createPref(
        key,
        defaultValue,
        { prefs.getStringSet(key, defaultValue) ?: defaultValue },
        { prefs.edit { putStringSet(key, it) } },
    )

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ) = createPref(
        key,
        defaultValue,
        {
            prefs.getString(key, null)?.let(deserializer) ?: defaultValue
        },
        { prefs.edit { putString(key, serializer(it)) } },
    )

    override fun <T> getJsonObject(
        key: String,
        defaultValue: T,
        serializer: KSerializer<T>,
        serializersModule: SerializersModule,
    ) = createPref(
        key,
        defaultValue,
        {
            prefs.getString(key, null)?.let {
                json.decodeFromString(serializer, it)
            } ?: defaultValue
        },
        {
            prefs.edit {
                putString(
                    key,
                    json.encodeToString(serializer, it),
                )
            }
        },
    )

    private fun <T> createPref(
        key: String,
        default: T,
        getter: () -> T,
        setter: (T) -> Unit,
    ): Preference<T> = SimplePreference(
        keyName = key,
        default = default,
        getter = getter,
        setter = setter,
    )
}
class SimplePreference<T>(
    private val keyName: String,
    private val default: T,
    private val getter: () -> T,
    private val setter: (T) -> Unit,
) : Preference<T> {

    private val state = MutableStateFlow(getter())

    override fun key(): String = keyName

    override fun isSet(): Boolean = true // SharedPreferences-backed → always "set"

    override fun defaultValue(): T = default

    override fun get(): T = getter()

    override fun set(value: T) {
        setter(value)
        state.value = value
    }

    override fun delete() {
        setter(default)
        state.value = default
    }

    override fun changes() = state.asStateFlow()

    override fun stateIn(scope: CoroutineScope): StateFlow<T> = state.asStateFlow()
}
