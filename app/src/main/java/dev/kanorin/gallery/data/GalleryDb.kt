package dev.kanorin.gallery.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        MediaEntity::class,
        FolderPrefsEntity::class,
        FavoriteEntity::class,
        CreatedFolderEntity::class,
        BinEntity::class,
        VideoProgressEntity::class,
        MetaEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class GalleryDb : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun metaDao(): MetaDao
    abstract fun pathDao(): PathDao
    abstract fun binDao(): BinDao
    abstract fun videoProgressDao(): VideoProgressDao

    companion object {
        const val NAME = "gallery.db"

        /** v1 is frozen (shipped). Each bump adds its Migration here and a test in GalleryDbMigrationTest. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        // No destructive fallback: favourites and bin paths are user data; bump with a Migration.
        fun build(context: Context, name: String = NAME): GalleryDb =
            Room.databaseBuilder(context, GalleryDb::class.java, name).addMigrations(*MIGRATIONS).build()
    }
}
