package dev.kanorin.gallery.data

/** Test-only reads (were MediaDao.allPaths / countVisible: the app itself never needs them). */
fun GalleryDb.allMediaPaths(): List<String> =
    openHelper.readableDatabase.query("SELECT path FROM media").use { c -> List(c.count) { c.moveToNext(); c.getString(0) } }

/** Rows the All view can show (hidden = 0 holds the effective flag). */
fun GalleryDb.visibleCount(): Int =
    openHelper.readableDatabase.query("SELECT COUNT(*) FROM media WHERE hidden = 0").use { c -> c.moveToFirst(); c.getInt(0) }
