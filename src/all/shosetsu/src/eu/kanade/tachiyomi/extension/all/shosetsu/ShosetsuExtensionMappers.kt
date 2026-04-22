package eu.kanade.tachiyomi.extension.all.shosetsu

import app.shosetsu.lib.Novel
import eu.kanade.tachiyomi.source.model.SManga as SNovel

fun Novel.Status.toSNovelStatus(): Int = when (this) {
    Novel.Status.COMPLETED -> SNovel.COMPLETED
    Novel.Status.PUBLISHING -> SNovel.ONGOING
    Novel.Status.PAUSED -> SNovel.ON_HIATUS
    Novel.Status.UNKNOWN -> SNovel.UNKNOWN
}

fun Novel.Info.toSNovel(): SNovel = SNovel.create().apply {
    url = this@toSNovel.link
    title = this@toSNovel.title
//        alternativeTitles
    author = this@toSNovel.authors.toString()
    artist = this@toSNovel.artists.toString()
    genre = this@toSNovel.genres.toString().takeIf { it.isNotEmpty() } ?: tags.toString()
    description = this@toSNovel.description
    status = this@toSNovel.status.toSNovelStatus()
    thumbnail_url = imageURL
}
