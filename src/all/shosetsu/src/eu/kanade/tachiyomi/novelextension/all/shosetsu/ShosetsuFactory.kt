package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.app.Application
import android.util.Log
import android.webkit.WebSettings
import app.shosetsu.lib.ShosetsuSharedLib
import app.shosetsu.lib.lua.LuaExtension
import app.shosetsu.lib.lua.ShosetsuLuaLib
import app.shosetsu.lib.lua.shosetsuGlobals
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Suppress("UNUSED")
class ShosetsuFactory : SourceFactory {
    private val hostContext by lazy { Injekt.get<Application>() }

    init {
        ShosetsuSharedLib.httpClient = Injekt.get<NetworkHelper>().client

        ShosetsuSharedLib.logger = { extensionName, log ->
            Log.d("Shosetsu (ext)", "[$extensionName] $log")
        }

        ShosetsuSharedLib.shosetsuHeaders = arrayOf(
            // runCatching to prevents crash in CI
            "User-Agent" to runCatching {
                WebSettings.getDefaultUserAgent(hostContext)
            }.getOrDefault(""),
        )

        ShosetsuLuaLib.libLoader = libLoader@{ name ->
            Log.i("LuaLibLoader", "Loading ($name)")
            try {
                val file = ExtensionManager.getLibraryFile(name)
                val content = injectLuaPatches(file.readText())
                val l =
                    shosetsuGlobals().load(content, "lib($name)")
                l.call()
            } catch (e: Throwable) {
                Log.e("Shosetsu", "Failed to load library $name", e)
                null
            }
        }
    }

    override fun createSources(): List<Source> {
        ExtensionManager.init(hostContext.filesDir)

        val extensions = withExtensionClassLoader(javaClass.classLoader!!) {
            ExtensionManager.getInstalledExtensionsFiles()
                .map { file ->
                    val ext = ShosetsuExtension.fromFile(file)
                    ext.loadLuaExtension() to ext.lang
                }
//                .plus(LuaExtension(injectLuaPatches(EXT), "DebugExt") to "all")
        }

        return extensions
            .mapNotNull(::safeCreateSource)
            .plus(
                // runCatching to prevents crash in CI
                runCatching {
                    ShosetsuSettings()
                }.getOrNull(),
            )
            .filterNotNull()
    }

    private fun safeCreateSource(pair: Pair<LuaExtension, String>): Source? {
        val (ext, lang) = pair
        return try {
            ShosetsuExtensionAdapter(ext, lang)
        } catch (e: Exception) {
            Log.e("Shosetsu", "Loading extension failed", e)
            null
        }
    }
}

// TODO URLActivity that sends link to search where it is handled and added as novel to listing if id match formattedID of extension processing it
//  share.shosetsu.app https://shosetsuorg.gitlab.io/kotlin-lib/shosetsu-kotlin-lib/app.shosetsu.lib.share/-novel-link/index.html
//
//
//
//
//
//
//
//
//
const val EXT = """
-- {"id":-1,"ver":"1.0.1","libVer":"1.0.0","author":"","repo":"","dep":["foo","bar"]}

math.randomseed(os.time())

--- Helper function for debug purposes
local function tableToString(tbl)
    local result = {}
    for k, v in pairs(tbl) do
        table.insert(result, k .. "=" .. tostring(v))
    end
    return "{" .. table.concat(result, ", ") .. "}"
end

local id = -1
local name = "Example"
local baseURL = "https://example.org/"
local imageURL = "https://example.invalid/asset/logo.png"
local hasCloudFlare = false
local hasSearch = true
local isSearchIncrementing = true

local searchFilters = {
	TextFilter(5, "RANDOM STRING INPUT"),
	SwitchFilter(6, "RANDOM SWITCH INPUT"),
	CheckboxFilter(7, "RANDOM CHECKBOX INPUT"),
	TriStateFilter(8, "RANDOM TRISTATE CHECKBOX INPUT"),
	RadioGroupFilter(9, "RANDOM RGROUP INPUT", { "A", "B", "C" }),
	DropdownFilter(10, "RANDOM DDOWN INPUT", { "A", "B", "C" })
}

local settings = {
	[1] = "settings no yet loaded: defaults are in use",
	[2] = false,
	[3] = false,
	[4] = 2,
	[5] = "A",
	[6] = "B"
}

local settingsModel = {
	TextFilter(1, "RANDOM STRING INPUT"),
	SwitchFilter(2, "RANDOM SWITCH INPUT"),
	CheckboxFilter(3, "RANDOM CHECKBOX INPUT"),
	TriStateFilter(4, "RANDOM TRISTATE CHECKBOX INPUT"),
	RadioGroupFilter(5, "RANDOM RGROUP INPUT", { "A", "B", "C" }),
	DropdownFilter(6, "RANDOM DDOWN INPUT", { "A", "B", "C" })
}

local chapterType = ChapterType.HTML
local startIndex = 1

local listings = {
	Listing("Something", false, function(data)
		local url = baseURL

        local res = Request(GET(url))
        local a = res.headers() -- wrong
        -- local a = res.header --right
        local b = a:get("Content-Type")
        local c = b:sub(1, 16)

		return {
			Novel {
				title = "Filters: " .. tableToString(data),
				link = tostring(math.random(1e6)) -- always different novel (bypass caching)
			}
		}
	end)
}

local function shrinkURL(url, type)
	if type == KEY_NOVEL_URL then
		return url:gsub(".-example%.org/?", "")
	else
		return url:gsub(".-example%.org/?", "")
	end
end

local function expandURL(url, type)
	url = url:gsub("^/*", "") -- remove leading slashes to avoid double slashes when concatenating
	if type == KEY_NOVEL_URL then
		return baseURL .. "/" .. url
	else
		return baseURL .. "/" .. url
	end
end

local function getPassage(chapterURL)
	local url = expandURL(chapterURL, KEY_CHAPTER_URL)

	local document = GETDocument(url)

	return ""
end

local function parseNovel(novelURL)
	local url = shrinkURL(novelURL, KEY_NOVEL_URL)

	return NovelInfo {
		title = "Settings",
		description = tableToString(settings)
	}
end

local function search(data)
	local page = data[PAGE]
	local query = data[QUERY]

	return {
			Novel {
				title = "Filters: " .. tableToString(data),
				link = tostring(math.random(1e6)) -- always different novel (bypass caching)
			}
		}
end

local function updateSetting(id, value)
	settings[id] = value
end

return {
	-- Required
	id = id,
	name = name,
	baseURL = baseURL,
	listings = listings, -- Must have at least one listing
	getPassage = getPassage,
	parseNovel = parseNovel,
	shrinkURL = shrinkURL,
	expandURL = expandURL,

	-- Optional values to change
	imageURL = imageURL,
	hasCloudFlare = hasCloudFlare,
	hasSearch = hasSearch,
	isSearchIncrementing = isSearchIncrementing,
	searchFilters = searchFilters,
	settings = settingsModel,
	chapterType = chapterType,
	startIndex = startIndex,

	-- Required if [hasSearch] is true.
	search = search,

	-- Required if [settings] is not empty
	updateSetting = updateSetting,
}
"""
