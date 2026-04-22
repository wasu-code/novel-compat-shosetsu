package eu.kanade.tachiyomi.novelextension.all.shosetsu

import app.shosetsu.lib.Novel
import eu.kanade.tachiyomi.source.model.SChapter
import novelsurcery.utils.setAltTitles
import eu.kanade.tachiyomi.source.model.SManga as SNovel

// private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
//    timeZone = TimeZone.getTimeZone("UTC")
// }

fun Novel.Status.toSNovelStatus(): Int = when (this) {
    Novel.Status.COMPLETED -> SNovel.COMPLETED
    Novel.Status.PUBLISHING -> SNovel.ONGOING
    Novel.Status.PAUSED -> SNovel.ON_HIATUS
    Novel.Status.UNKNOWN -> SNovel.UNKNOWN
}

fun Novel.Info.toSNovel(): SNovel = SNovel.create().apply {
    url = this@toSNovel.link
    title = this@toSNovel.title
    author = this@toSNovel.authors.joinToString()
    artist = this@toSNovel.artists.joinToString()
    genre = this@toSNovel.genres.joinToString().takeIf { it.isNotEmpty() } ?: tags.joinToString()
    description = this@toSNovel.description
    status = this@toSNovel.status.toSNovelStatus()
    thumbnail_url = imageURL
    setAltTitles(this@toSNovel.alternativeTitles.asList())
}

fun Novel.Chapter.toSChapter(): SChapter = SChapter.create().apply {
    url = link
    name = title
//    date_upload = SimpleDateFormat().tryParse(release) // first need to quess the format
    chapter_number = order.toFloat()
}
