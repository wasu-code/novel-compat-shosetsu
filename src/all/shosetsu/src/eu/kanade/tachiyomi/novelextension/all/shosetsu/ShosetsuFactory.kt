package eu.kanade.tachiyomi.novelextension.all.shosetsu

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.shosetsu.lib.ShosetsuSharedLib
import app.shosetsu.lib.lua.LuaExtension
import app.shosetsu.lib.lua.ShosetsuLuaLib
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import kuchihige.utils.log
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Suppress("UNUSED")
class ShosetsuFactory : SourceFactory {

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val hostContext by lazy { Injekt.get<Application>() }

    private val context = Injekt.get<Application>()

    init {
        ShosetsuSharedLib.httpClient = OkHttpClient()

        ShosetsuSharedLib.logger = { extensionName, log ->
            Log.d("Shosetsu (ext)", "[$extensionName] $log")
        }

//        ShosetsuSharedLib.shosetsuHeaders = arrayOf(
//            "User-Agent" to "Tsundoku/${AppInfo.getVersionName()} (Shosetsu Extension; ShosetsuLib/1.4.1)"
//        )

        ShosetsuLuaLib.libLoader = libLoader@{ name ->
            Log.i("LuaLibLoader", "Loading ($name)")
            try {
//                val result = runBlocking { extLibRepository.loadExtLibrary(name) }
//                val l =
//                    shosetsuGlobals().load(result, "lib($name)")
//                l.call()
                null
            } catch (e: Throwable) {
                e.log()
                null
            }
        } // TODO
    }

    override fun createSources(): List<Source> {
        PluginManager.init(context.filesDir)

        val extensions = withExtensionClassLoader {
            PluginManager.getInstalledExtensions()
                .map { file ->
                    val lang = file.parentFile?.name ?: "all"
                    LuaExtension(file) to lang
                }
                .plus(LuaExtension(AAAA, "AAAAExt") to "all")
        }

        return extensions
            .mapNotNull(::safeCreateSource)
            .plus(ShosetsuSettings())
    }

    // Use extension class loader so that shosetsu lib sees its resources,
    // not resources of the host app (necessary for .lua resources)
    inline fun <T> withExtensionClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val original = thread.contextClassLoader

        return try {
            thread.contextClassLoader = javaClass.classLoader
            block()
        } finally {
            thread.contextClassLoader = original
        }
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

// TODO remotely added extensions by url to easier review PR or local testing
// TODO URLActivity share.shosetsu.app https://shosetsuorg.gitlab.io/kotlin-lib/shosetsu-kotlin-lib/app.shosetsu.lib.share/-novel-link/index.html

// ext.exMetaData Returns the metadata that is at the header of the extension

const val AAAA = """
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

--- Identification number of the extension.
--- Should be unique. Should be consistent in all references.
---
--- Required.
---
--- @type int
local id = -1

--- Name of extension to display to the user.
--- Should match index.
---
--- Required.
---
--- @type string
local name = "Example"

--- Base URL of the extension. Used to open web view in Shosetsu.
---
--- Required.
---
--- @type string
local baseURL = "https://example.org/"

--- URL of the logo.
---
--- Optional, Default is empty.
---
--- @type string
local imageURL = "https://example.invalid/asset/logo.png"

--- Shosetsu tries to handle cloudflare protection if this is set to true.
---
--- Optional, Default is false.
---
--- @type boolean
local hasCloudFlare = false

--- If the website has search.
---
--- Optional, Default is true.
---
--- @type boolean
local hasSearch = true

--- If the websites search increments or not.
---
--- Optional, Default is true.
---
--- @type boolean
local isSearchIncrementing = true

--- Filters to display via the filter fab in Shosetsu.
---
--- Optional, Default is none.
---
--- @type Filter[] | Array
local searchFilters = {
	TextFilter(5, "RANDOM STRING INPUT"),
	SwitchFilter(6, "RANDOM SWITCH INPUT"),
	CheckboxFilter(7, "RANDOM CHECKBOX INPUT"),
	TriStateFilter(8, "RANDOM TRISTATE CHECKBOX INPUT"),
	RadioGroupFilter(9, "RANDOM RGROUP INPUT", { "A", "B", "C" }),
	DropdownFilter(10, "RANDOM DDOWN INPUT", { "A", "B", "C" })
}

--- Internal settings store.
---
--- Completely optional.
---  But required if you want to save results from [updateSetting].
---
--- Notice, each key is surrounded by "[]" and the value is on the right side.
--- @type table
local settings = {
	[1] = "settings no yet loaded: defaults are in use",
	[2] = false,
	[3] = false,
	[4] = 2,
	[5] = "A",
	[6] = "B"
}

--- Settings model for Shosetsu to render.
---
--- Optional, Default is empty.
---
--- @type Filter[] | Array
local settingsModel = {
	TextFilter(1, "RANDOM STRING INPUT"),
	SwitchFilter(2, "RANDOM SWITCH INPUT"),
	CheckboxFilter(3, "RANDOM CHECKBOX INPUT"),
	TriStateFilter(4, "RANDOM TRISTATE CHECKBOX INPUT"),
	RadioGroupFilter(5, "RANDOM RGROUP INPUT", { "A", "B", "C" }),
	DropdownFilter(6, "RANDOM DDOWN INPUT", { "A", "B", "C" })
}

--- ChapterType provided by the extension.
---
--- Optional, Default is STRING. But please do HTML.
---
--- @type ChapterType
local chapterType = ChapterType.HTML

--- Index that pages start with. For example, the first page of search is index 1.
---
--- Optional, Default is 1.
---
--- @type number
local startIndex = 1

--- Listings that users can navigate in Shosetsu.
---
--- Required, 1 value at minimum.
---
--- @type Listing[] | Array
local listings = {
	Listing("Something", false, function(data)
		-- Many sites use the baseURL + some path, you can perform the URL construction here.
		-- You can also extract query data from [data]. But do perform a null check, for safety.
		local url = baseURL

		local document = GETDocument(url)

		return {
			Novel {
				title = "Filters: " .. tableToString(data),
				link = tostring(math.random(1e6)) -- always different novel (bypass caching)
			}
		}
	end),
	Listing("Something (with incrementing pages!)", true, function(data)
		--- @type int
		local page = data[PAGE]
		-- Previous documentation, + appending page
		local url = baseURL .. "?p=" .. page

		local document = GETDocument(url)

		return {
			Novel {
				title = "Filters: " .. tableToString(data),
				link = tostring(math.random(1e6)) -- always different novel (bypass caching)
			}
		}
	end),
	Listing("Something without any input", false, function()
		-- Previous documentation, except no data or appending.
		local url = baseURL

		local document = GETDocument(url)

		return {}
	end)
}

--- Shrink the website url down. This is for space saving purposes.
---
--- Required.
---
--- @param url string Full URL to shrink.
--- @param type int Either KEY_CHAPTER_URL or KEY_NOVEL_URL.
--- @return string Shrunk URL.
local function shrinkURL(url, type)
	-- Currently the two branches are the same.
	-- You can simplify this to just a return with a single substitution.
	-- But some websites separate novels & chapters.
	--  So a novel is URL/novel/12345,
	--  And a chapter is URL/chapter/12345.
	-- Thus you would then program two substitutions, one to remove URL/novel/,
	--  and one to remove URL/chapter/
	if type == KEY_NOVEL_URL then
		return url:gsub(".-example%.org/?", "")
	else
		return url:gsub(".-example%.org/?", "")
	end
end

--- Expand a given URL.
---
--- Required.
---
--- @param url string Shrunk URL to expand.
--- @param type int Either KEY_CHAPTER_URL or KEY_NOVEL_URL.
--- @return string Full URL.
local function expandURL(url, type)
	-- Currently the two branches are the same.
	-- Read [shrinkURL] documentation in regards to what you should do.
	-- Hint, this is the opposite.

	url = url:gsub("^/*", "") -- remove leading slashes to avoid double slashes when concatenating
	if type == KEY_NOVEL_URL then
		return baseURL .. "/" .. url
	else
		return baseURL .. "/" .. url
	end
end

--- Get a chapter passage based on its chapterURL.
---
--- Required.
---
--- @param chapterURL string The chapters shrunken URL.
--- @return string Strings in lua are byte arrays. If you are not outputting strings/html you can return a binary stream.
local function getPassage(chapterURL)
	local url = expandURL(chapterURL, KEY_CHAPTER_URL)

	--- Chapter page, extract info from it.
	local document = GETDocument(url)

	return ""
end

--- Load info on a novel.
---
--- Required.
---
--- @param novelURL string shrunken novel url.
--- @return NovelInfo
local function parseNovel(novelURL)
	local url = shrinkURL(novelURL, KEY_NOVEL_URL)

	--- Novel page, extract info from it.
	-- local document = GETDocument(url)

	return NovelInfo {
		title = "Settings",
		description = tableToString(settings)
	}
end

--- Called to search for novels off a website.
---
--- Optional, But required if [hasSearch] is true.
---
--- @param data table @of applied filter values [QUERY] is the search query, may be empty.
--- @return Novel[] | Array
local function search(data)
	--- Not required if search is not incrementing.
	--- @type int
	local page = data[PAGE]

	--- Get the user text query to pass through.
	--- @type string
	local query = data[QUERY]

	return {
			Novel {
				title = "Filters: " .. tableToString(data),
				link = tostring(math.random(1e6)) -- always different novel (bypass caching)
			}
		}
end

--- Called when a user changes a setting and when the extension is being initialized.
---
--- Optional, But required if [settingsModel] is not empty.
---
--- @param id int Setting key as stated in [settingsModel].
--- @param value any Value pertaining to the type of setting. Int/Boolean/String.
--- @return void
local function updateSetting(id, value)
	settings[id] = value
end

-- Return all properties in a lua table.
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

const val BBBB = """
-- {"id":808001,"ver":"1.0.0","libVer":"1.0.0","author":"wsu808","repo":"","dep":["unhtml", "FilterOptions", "url"]}

local FilterOptions = Require("FilterOptions")
local HTMLToString = Require("unhtml").HTMLToString
local encode = Require("url").encode

local EMPTY_SPACE = string.rep(" ", 100)

local baseURL = "https://www.fimfiction.net"

--- Maps Fimfiction statuses to Shosetsu statuses
local statusMap = {
  complete = NovelStatus.COMPLETED,
  incomplete = NovelStatus.PUBLISHING,
  hitaus = NovelStatus.PAUSED,
  cancelled = NovelStatus.PAUSED
}

-- Filter IDs
local FID_ORDER = 2
local FID_TAGS = 3
local FID_RATING = 4
local FID_VIEWS = 5
local FID_WORDS = 6
local FID_STATUS = 7

local orderFilter = FilterOptions {
  "latest",
  "relevance",
  "heat",
  "updated",
  { top = "rating" },
  "views",
  "words",
  "comments",
  "likes",
  "dislikes",
  "random"
}

local statusFilter = FilterOptions({
  nil,
  "complete",
  "incomplete",
  "hitaus",
  "cancelled",
}, "All")

--- simplified querystring without enforced url encoding
local function querystring(tbl, url)
  local fields = {}

  for key, value in pairs(tbl) do
    if value ~= nil then
      table.insert(fields, key .. "=" .. tostring(value))
    end
  end

  return (url and url .. "?" or "") .. table.concat(fields, "&")
end

local function shrinkURL(url, type)
  return url:gsub(".-fimfiction%.net/?", "")
end

local function expandURL(url, type)
  return baseURL .. "/" .. url:gsub("^/*", "")
end

local function getListing(data)
  local query = data[QUERY]
  local page = data[PAGE]
  local tags = data[FID_TAGS] or ""

  local filters = {
    status = data[FID_STATUS] and statusFilter:valueOf(data[FID_STATUS]),
    words = data[FID_WORDS],
    views = data[FID_VIEWS],
    wilson = data[FID_RATING]
  }

  local parts = {}
  for k, v in pairs(filters) do
    if v and v ~= "" then
      table.insert(parts, k .. "%3A" .. v)
    end
  end
  for tag in tags:gmatch("%S+") do
    table.insert(parts, encode(tag))
  end

  local advancedQuery = encode(query) .. "+" .. table.concat(parts, "+")

  local params = {
    view_mode = 0,
    order = data[FID_ORDER] and orderFilter:valueOf(data[FID_ORDER]),
    q = advancedQuery,
    page = (page ~= 1) and page or nil
  }

  local url = expandURL(querystring(params, "/stories"))
  local doc = GETDocument(url)

  return map(doc:select(".story_content_box"), function(n)
    local a = n:selectFirst(".story_name")
    local img = n:selectFirst(".story_container__story_image img")

    return Novel {
      title = a:text(),
      link = a:attr("href"),
      imageURL = img and img:attr("data-src")
    }
  end)
end

local function parseNovel(url, loadChapters)
  local doc = GETDocument(expandURL(url))

  local statusClass = doc:selectFirst("[class^='completed-status-']"):attr("class")
  local status = statusClass:match(".*%-(.+)")
  local img = doc:selectFirst("meta[property='og:image']")

  local info = NovelInfo {
    title = doc:selectFirst("meta[property='og:title']"):attr("content"),
    description = doc:selectFirst("meta[property='og:description']"):attr("content")
      .. "\n\n"
      .. HTMLToString(doc:selectFirst(".description-text")),
    imageURL = img and img:attr("content"),
    status = statusMap[status],
    authors = { doc:selectFirst("meta[property='book:author']"):attr("content"):match(".*%/(.+)") },
    genres = map(doc:select(".story_container .story-tags li"), function(t) return t:text() end)
  }

  if loadChapters then
    local chapters = map(doc:select(".chapters li .title-box"), function (ch)
      return NovelChapter {
        title = ch:selectFirst(".chapter-title"):text(),
        link = ch:selectFirst(".chapter-title"):attr("href"),
        release = ch:selectFirst(".date"):ownText()
      }
    end)

    info:setChapters(chapters)
  end

  return info
end

local function getPassage(url)
  local doc = GETDocument(expandURL(url))
  -- remove chapter list dropdown
  doc:selectFirst("h1.chapter-title > div"):remove()
  return pageOfElem(doc:selectFirst("#chapter"))
end

return {
  id = 808001,
  name = "Fimfiction",
  baseURL = baseURL,
  imageURL = "https://static.fimfiction.net/images/favicon.png?9",
  chapterType = ChapterType.HTML,
  hasCloudFlare = true,

  shrinkURL = shrinkURL,
  expandURL = expandURL,

  listings = {
    Listing("Default", true, getListing)
  },

  searchFilters = {
    DropdownFilter(FID_ORDER, "Order by", orderFilter:labels()),
    DropdownFilter(FID_STATUS, "Status", statusFilter:labels()),
    FilterList("Tags", {
      TextFilter(FID_TAGS, "Tags"),
      FilterList([[
Include tags by prefixing them with #
Exclude tags by prefixing with -#
      ]] .. EMPTY_SPACE, {}),
      FilterList("✳️ #slice-of-life -#sex" .. EMPTY_SPACE, {}),
    }),
    FilterList("Rating", {
      TextFilter(FID_RATING, "Rating"),
      FilterList("✳️ >80" .. EMPTY_SPACE, {})
    }),
    FilterList("View count", {
      TextFilter(FID_VIEWS, "Custom view count"),
      FilterList("✳️ >10000" .. EMPTY_SPACE, {}),
      FilterList("✳️ <10000" .. EMPTY_SPACE, {}),
      FilterList("✳️ 5000-9000" .. EMPTY_SPACE, {})
    }),
    FilterList("Word count", {
      TextFilter(FID_WORDS, "Custom word count"),
      FilterList("✳️ >10000" .. EMPTY_SPACE, {}),
      FilterList("✳️ <10000" .. EMPTY_SPACE, {}),
      FilterList("✳️ 5000-9000" .. EMPTY_SPACE, {})
    }),
  },

  parseNovel = parseNovel,
  getPassage = getPassage,

  hasSearch = true,
  isSearchIncrementing = true,
  startIndex = 1,
  search = getListing,
}
"""
