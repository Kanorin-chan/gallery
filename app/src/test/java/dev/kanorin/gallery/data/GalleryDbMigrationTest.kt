package dev.kanorin.gallery.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The home of every schema migration test. v1 (app/schemas/.../1.json) is frozen: see CLAUDE.md. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GalleryDbMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // The driver form: the by-name form compares paths by '/', which a Windows host's Robolectric paths fail.
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), context.getDatabasePath(DB), AndroidSQLiteDriver(), GalleryDb::class,
    )

    // A v2 copies this: create at 1, write rows, runMigrationsAndValidate(2, MIGRATIONS.toList()), check the rows.
    @Test fun theShippedVersionOneOpensFromItsExportedSchema() {
        helper.createDatabase(1).use { it.execSQL("INSERT INTO favorites(path, addedAt) VALUES ('/storage/emulated/0/DCIM/Camera/a.jpg', 5)") }
        helper.runMigrationsAndValidate(1, GalleryDb.MIGRATIONS.toList()).close()
        // Room checks the file against today's entities: an entity edited without a bump fails here.
        val db = GalleryDb.build(context, DB)
        try {
            db.openHelper.readableDatabase.query("SELECT path FROM favorites").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals("/storage/emulated/0/DCIM/Camera/a.jpg", c.getString(0))
            }
        } finally {
            db.close()
        }
    }

    // KSP rewrites 1.json whenever an entity changes at version 1; the pinned hash makes that edit fail instead.
    @Test fun versionOneIsFrozen() {
        val json = context.assets.open("dev.kanorin.gallery.data.GalleryDb/1.json").bufferedReader().use { it.readText() }
        val hash = Regex("\"identityHash\":\\s*\"(\\w+)\"").find(json)!!.groupValues[1]
        assertEquals("v1 shipped 2026-10-31: bump the version and add a Migration", V1_HASH, hash)
    }

    private companion object {
        const val DB = "migration-test.db"
        const val V1_HASH = "018a41b2ef701cace932ca699a66abf9"
    }
}
