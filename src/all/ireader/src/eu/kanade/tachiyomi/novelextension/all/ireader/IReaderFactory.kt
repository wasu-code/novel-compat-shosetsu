package eu.kanade.tachiyomi.novelextension.all.ireader

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import ireader.core.source.CatalogSource as IReaderCatalogueSource
import ireader.core.source.HttpSource as IReaderHttpSource

class IReaderFactory : SourceFactory {
    private val hostContext by lazy { Injekt.get<Application>() }
    private val nh = Injekt.get<NetworkHelper>()

    override fun createSources(): List<Source> {
        val extensions = runBlocking {
            AndroidCatalogLoader(
                hostContext,
                HttpClients(
                    context = hostContext,
                    browseEngine = BrowserEngine(),
                    cookiesStorage = DummyCookiesStorage(),
                    webViewCookieJar = WebViewCookieJar(
                        cookiesStorage = DummyCookiesStorage(),
                    ),
                    preferencesStore = DummyPreferenceStore(),
                    webViewManager = WebViewManger(
                        context = hostContext,
                    ),
                    networkConfig = NetworkConfig(),
                ),
            ).loadAll()
        }

        return buildList {
//            CatalogueSourceAdapter(TestSource()).also(::add)
            IReaderSettings().also(::add)
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

// TODO: actual implementations for dummy classes

class DummyCookiesStorage : CookiesStorage {
    override suspend fun get(requestUrl: Url): List<Cookie> = emptyList()

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
    }

    override fun close() {
    }
}

class DummyPreferenceStore : PreferenceStore {

    private val prefs: SharedPreferences by lazy {
        Injekt.get<Application>()
            .getSharedPreferences("source_ireader", 0x0000)
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun getString(key: String, defaultValue: String) = createPref(
        key,
        defaultValue,
        { prefs.getString(key, defaultValue) ?: defaultValue },
        { prefs.edit().putString(key, it).apply() },
    )

    override fun getLong(key: String, defaultValue: Long) = createPref(
        key,
        defaultValue,
        { prefs.getLong(key, defaultValue) },
        { prefs.edit().putLong(key, it).apply() },
    )

    override fun getInt(key: String, defaultValue: Int) = createPref(
        key,
        defaultValue,
        { prefs.getInt(key, defaultValue) },
        { prefs.edit().putInt(key, it).apply() },
    )

    override fun getFloat(key: String, defaultValue: Float) = createPref(
        key,
        defaultValue,
        { prefs.getFloat(key, defaultValue) },
        { prefs.edit().putFloat(key, it).apply() },
    )

    override fun getBoolean(key: String, defaultValue: Boolean) = createPref(
        key,
        defaultValue,
        { prefs.getBoolean(key, defaultValue) },
        { prefs.edit().putBoolean(key, it).apply() },
    )

    override fun getStringSet(
        key: String,
        defaultValue: Set<String>,
    ) = createPref(
        key,
        defaultValue,
        { prefs.getStringSet(key, defaultValue) ?: defaultValue },
        { prefs.edit().putStringSet(key, it).apply() },
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
        { prefs.edit().putString(key, serializer(it)).apply() },
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
            prefs.edit().putString(
                key,
                json.encodeToString(serializer, it),
            ).apply()
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
