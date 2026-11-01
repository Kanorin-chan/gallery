package dev.kanorin.gallery.ui.viewer

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.kanorin.gallery.cancelAndDrain
import dev.kanorin.gallery.data.GalleryDb
import dev.kanorin.gallery.data.MediaEntity
import dev.kanorin.gallery.fileops.FileOps
import dev.kanorin.gallery.fileops.RecycleBin
import dev.kanorin.gallery.logic.FAVORITES_FOLDER
import dev.kanorin.gallery.logic.GroupBy
import dev.kanorin.gallery.logic.SearchQuery
import dev.kanorin.gallery.logic.MediaFilter
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.logic.Zoom
import dev.kanorin.gallery.logic.ZoomMath
import dev.kanorin.gallery.settings.MemoryStore
import dev.kanorin.gallery.settings.Settings
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.common.ToastHostState
import dev.kanorin.gallery.ui.player.playerRoute
import dev.kanorin.gallery.ui.theme.GalleryTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** Spec 5.7 on the real screen: Room in memory, real files, real paging, real gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class ViewerScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()

    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private fun at(d: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, kl).toInstant().toEpochMilli()
    private val now = at(30, 18, 0)

    private lateinit var root: File
    private lateinit var trip: File
    private lateinit var other: File
    private lateinit var db: GalleryDb
    private lateinit var ops: FileOps
    private lateinit var bin: RecycleBin
    private val settings = Settings(MemoryStore())
    private val toasts = ToastHostState()
    private lateinit var vm: ViewerViewModel
    private var closed = 0
    private val navigated = mutableListOf<String>()
    private var picks = 0
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Trip, newest first: a (today 14:32), b (yesterday), c, v (a video). Other: one photo.
    private val a get() = File(trip, "a.jpg")
    private val b get() = File(trip, "b.jpg")
    private val c get() = File(trip, "c.jpg")
    private val v get() = File(trip, "v.mp4")
    private val lone get() = File(other, "lone.jpg")

    private fun seed() {
        root = tmp.newFolder("storage", "emulated", "0")
        trip = File(root, "Pictures/Trip KL").apply { mkdirs() }
        other = File(root, "Pictures/Other").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(context, GalleryDb::class.java).allowMainThreadQueries().build()
        ops = FileOps(db, {}, roots = { listOf(root) }, storageRoot = root.path)
        bin = RecycleBin(db, ops)
        runBlocking {
            db.mediaDao().upsert(
                listOf(
                    row(a, at(30, 14, 32), 1), row(b, at(29, 9, 0), 2), row(c, at(28, 9, 0), 3),
                    row(v, at(27, 9, 0), 4, video = true), row(lone, at(26, 9, 0), 5),
                ),
            )
        }
    }

    private fun row(f: File, taken: Long, id: Long, video: Boolean = false): MediaEntity {
        if (video) f.writeBytes(ByteArray(64)) else f.outputStream().use {
            Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it)
        }
        return MediaEntity(
            path = f.path, folderPath = f.parent!!, name = f.name, mimeType = if (video) "video/mp4" else "image/jpeg",
            isVideo = video, dateTaken = taken, dateModified = taken, size = f.length(), width = 4000, height = 3000,
            durationMs = if (video) 5_000 else 0, orientation = 0, mediaStoreId = id, hidden = false, volume = "external_primary",
        )
    }

    private fun open(
        index: Int,
        folder: String? = trip.path,
        saved: SavedStateHandle = SavedStateHandle(),
        filter: MediaFilter = MediaFilter(groupBy = GroupBy.NONE),
        path: String? = null,
        registry: ActivityResultRegistry? = null,
    ) {
        vm = newVm(index, folder, saved, filter, path)
        rule.setContent {
            GalleryTheme {
                ToastHost(toasts) {
                    val screen = @Composable {
                        ViewerScreen(vm, onBack = { closed++ }, onNavigate = { navigated += it }, onPickFolder = { picks++ })
                    }
                    if (registry == null) {
                        screen()
                    } else {
                        val owner = object : ActivityResultRegistryOwner {
                            override val activityResultRegistry: ActivityResultRegistry = registry
                        }
                        CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) { screen() }
                    }
                }
            }
        }
    }

    private fun newVm(
        index: Int,
        folder: String?,
        saved: SavedStateHandle,
        filter: MediaFilter = MediaFilter(groupBy = GroupBy.NONE),
        path: String? = null,
    ) = ViewerViewModel(
        ViewerArgs(filter, folder, index, null, path = path), db.mediaDao(), db.pathDao(), settings, saved,
        ops, bin, roots = { listOf(root) }, resolver = null, toasts = toasts, storageRoot = root.path,
        clock = { now }, zoneId = { kl },
    )

    private fun favour(vararg files: File) = files.forEach {
        db.openHelper.writableDatabase.execSQL("INSERT INTO favorites(path, addedAt) VALUES (?, 1)", arrayOf(it.path))
    }

    /** Counts permission prompts; never answers them. */
    private class CountingRegistry : ActivityResultRegistry() {
        val launched = mutableListOf<Any?>()

        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            launched += input
        }
    }

    @After fun tearDown() {
        if (::vm.isInitialized) cancelAndDrain(vm.viewModelScope)
        if (::db.isInitialized) db.close()
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()
    private fun exists(tag: String) = exists(hasTestTag(tag))
    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { exists(tag) }
    private fun waitForText(text: String) = rule.waitUntil(10_000) { exists(hasText(text)) }
    private fun showing(f: File) = waitForTag(viewerPageTag(f.path))
    private fun until(condition: () -> Boolean) = rule.waitUntil(10_000) { rule.waitForIdle(); condition() }
    private fun zoom(f: File): Zoom = rule.onNodeWithTag(viewerImageTag(f.path)).fetchSemanticsNode().config[ZoomKey]
    private fun pager() = rule.onNodeWithTag(VIEWER_PAGER_TAG)

    // One tap: the viewer waits out the double-tap window before it toggles anything.
    private fun tapImage(f: File) {
        rule.onNodeWithTag(viewerImageTag(f.path)).performTouchInput { click(center) }
        rule.mainClock.advanceTimeBy(400)
        rule.waitForIdle()
    }

    private fun favourite(f: File) = runBlocking { db.mediaDao().isFavorite(f.path).first() }

    // ---- paging over the grid's query

    @Test fun opensAtTheTappedItem() {
        seed()
        open(index = 2)
        showing(c)
    }

    @Test fun swipingGoesToTheNextItemInGridOrderAndBack() {
        seed()
        open(index = 0)
        showing(a)
        pager().performTouchInput { swipeLeft() }
        showing(b)
        pager().performTouchInput { swipeLeft() }
        showing(c)
        pager().performTouchInput { swipeRight() }
        showing(b)
    }

    // Fix round 1: the grid's index is a snapshot; rows can come and go before the viewer's first load.
    @Test fun rowsInsertedAboveBeforeTheFirstLoadStillOpenTheTappedPhoto() {
        seed()
        // The grid showed a, b, c, v and b was tapped at 1; then two newer photos landed (a camera shot, the import).
        val newer = listOf(File(trip, "n1.jpg"), File(trip, "n2.jpg"))
        runBlocking { db.mediaDao().upsert(newer.mapIndexed { i, f -> row(f, at(30, 16, i), 10L + i) }) }
        open(index = 1, path = b.path)
        showing(b)
        pager().performTouchInput { swipeRight() }
        showing(a)
    }

    @Test fun rowsRemovedAboveBeforeTheFirstLoadStillOpenTheTappedPhoto() {
        seed()
        // c was tapped at 2; then a left the index (deleted elsewhere, the resume reconcile).
        runBlocking { db.mediaDao().deleteByPaths(listOf(a.path)) }
        open(index = 2, path = c.path)
        showing(c)
    }

    @Test fun aTappedPathThatIsGoneFallsBackToTheIndex() {
        seed()
        open(index = 2, path = File(trip, "gone.jpg").path)
        showing(c)
    }

    @Test fun underASearchItOpensOnTheTappedPhotoAndSwipesInTheSearchsOrder() {
        seed()
        // "Trip" matches the folder's name: a, b, c, v; lone (in Other) is left out.
        val search = MediaFilter(search = SearchQuery("Trip", null, null))
        open(index = 2, folder = null, filter = search, path = c.path)
        showing(c)
        pager().performTouchInput { swipeLeft() }
        showing(v)
        pager().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertFalse(exists(viewerPageTag(lone.path)))
        showing(v)
    }

    @Test fun inFavouritesItOpensOnTheTappedPhotoAndSwipesInFavouritesOrder() {
        seed()
        favour(a, c, lone)
        open(index = 1, folder = FAVORITES_FOLDER, path = c.path)
        showing(c)
        pager().performTouchInput { swipeLeft() }
        showing(lone)
        pager().performTouchInput { swipeRight() }
        showing(c)
        pager().performTouchInput { swipeRight() }
        showing(a)
    }

    @Test fun theSettledPageSurvivesANewViewModel() {
        seed()
        val saved = SavedStateHandle()
        open(index = 0, saved = saved)
        showing(a)
        pager().performTouchInput { swipeLeft() }
        showing(b)
        until { saved.get<Int>("viewerPage") == 1 }
        val again = newVm(index = 0, folder = trip.path, saved = saved)
        assertEquals(Browse.Grid(MediaFilter(groupBy = GroupBy.NONE), trip.path, 1), again.browse.value)
        again.viewModelScope.cancel()
    }

    @Test fun theTopBarShowsTheDayAndTime() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(VIEWER_TITLE_TAG).assertTextEquals("Today")
        rule.onNodeWithTag(VIEWER_TIME_TAG).assertTextEquals("14:32")
        pager().performTouchInput { swipeLeft() }
        showing(b)
        rule.waitUntil(5_000) { exists(hasTestTag(VIEWER_TITLE_TAG) and hasText("Yesterday")) }
    }

    // ---- favourite

    // Final review M7: a favourite the database refuses is a toast, never a crash.
    @Test fun aFavouriteThatCantBeSavedSaysSo() {
        seed()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER no_favs BEFORE INSERT ON favorites BEGIN SELECT RAISE(ABORT, 'disk full'); END")
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(ACTION_FAVOURITE_TAG).performClick()
        until { toasts.toast.value?.message == "Couldn't add to Favourites" }
        assertFalse(favourite(a))
    }

    @Test fun theFavouriteToggleAddsAndRemovesAFavoriteEntity() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(ACTION_FAVOURITE_TAG).assertIsOff()
        rule.onNodeWithTag(ACTION_FAVOURITE_TAG).performClick()
        until { favourite(a) }
        rule.onNodeWithTag(ACTION_FAVOURITE_TAG).assertIsOn()
        rule.onNodeWithTag(ACTION_FAVOURITE_TAG).performClick()
        until { !favourite(a) }
        rule.onNodeWithTag(ACTION_FAVOURITE_TAG).assertIsOff()
        assertFalse(favourite(b))
    }

    // ---- Review Focus 3

    @Test fun anIndexRowWhoseFileWasDeletedShowsFileNoLongerExists() {
        seed()
        assertTrue(b.delete()) // another app deleted it; the index still has the row
        open(index = 1)
        showing(b)
        waitForTag(MISSING_TAG)
        rule.onNodeWithTag(MISSING_TAG).assertTextEquals("File no longer exists")
        assertFalse(exists(ACTION_DELETE_TAG))
        assertTrue(exists(ACTION_SHARE_TAG))
        // The next one is fine.
        pager().performTouchInput { swipeLeft() }
        showing(c)
        waitForTag(ACTION_DELETE_TAG)
        assertFalse(exists(MISSING_TAG))
    }

    // ---- bars

    @Test fun aTapTogglesTheBars() {
        seed()
        open(index = 0)
        showing(a)
        waitForTag(VIEWER_TOP_BAR_TAG)
        tapImage(a)
        until { !exists(VIEWER_TOP_BAR_TAG) && !exists(VIEWER_ACTIONS_TAG) }
        tapImage(a)
        until { exists(VIEWER_TOP_BAR_TAG) && exists(VIEWER_ACTIONS_TAG) }
    }

    // ---- zoom

    @Test fun doubleTapZoomsTo2_5xAndBack() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(viewerImageTag(a.path)).performTouchInput { doubleClick(center) }
        until { zoom(a).scale == ZoomMath.DOUBLE_TAP_SCALE }
        // Around the tap, which was the centre.
        assertEquals(0f, zoom(a).x, 0.5f)
        rule.onNodeWithTag(viewerImageTag(a.path)).performTouchInput { doubleClick(center) }
        until { zoom(a) == Zoom() }
        // The bars weren't toggled by either.
        assertTrue(exists(VIEWER_TOP_BAR_TAG))
    }

    @Test fun pinchZoomsAndStopsAt8x() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(viewerImageTag(a.path)).performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(340f, 0f), center + Offset(20f, 0f), center + Offset(340f, 0f))
        }
        until { zoom(a).scale == ZoomMath.MAX_SCALE }
        // A pinch is never a page swipe.
        showing(a)
    }

    @Test fun whileZoomedASideSwipePansTheImageAndOnlyItsEdgeTurnsThePage() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(viewerImageTag(a.path)).performTouchInput { doubleClick(center) }
        until { zoom(a).scale == ZoomMath.DOUBLE_TAP_SCALE }
        pager().performTouchInput { swipeLeft() }
        rule.waitForIdle()
        // Panned to its right edge (x = -max), still on the same photo.
        val panned = zoom(a)
        assertTrue("x ${panned.x}", panned.x < 0f)
        assertTrue(exists(viewerPageTag(a.path)))
        assertFalse(exists(viewerPageTag(b.path)))
        // At the edge, the next swipe the same way turns the page.
        pager().performTouchInput { swipeLeft() }
        showing(b)
    }

    @Test fun anUpSwipeWhileZoomedPansAndNeverOpensTheDetails() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(viewerImageTag(a.path)).performTouchInput { doubleClick(center) }
        until { zoom(a).scale == ZoomMath.DOUBLE_TAP_SCALE }
        rule.onNodeWithTag(viewerImageTag(a.path)).performTouchInput { swipeUp(startY = centerY + 200f, endY = centerY - 300f) }
        rule.waitForIdle()
        assertFalse(exists(DETAILS_SHEET_TAG))
        assertEquals(ZoomMath.DOUBLE_TAP_SCALE, zoom(a).scale)
    }

    @Test fun aZoomedPhotoPansBackTheOtherWayBeforeTurningBack() {
        seed()
        open(index = 1)
        showing(b)
        rule.onNodeWithTag(viewerImageTag(b.path)).performTouchInput { doubleClick(center) }
        until { zoom(b).scale == ZoomMath.DOUBLE_TAP_SCALE }
        pager().performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertTrue(zoom(b).x > 0f)
        assertFalse(exists(viewerPageTag(a.path)))
        pager().performTouchInput { swipeRight() }
        showing(a)
    }

    // ---- details

    @Test fun swipingUpOpensTheDetailsAndBackClosesThem() {
        seed()
        open(index = 0)
        showing(a)
        // Mid-photo, clear of the action bar over its bottom.
        rule.onNodeWithTag(viewerImageTag(a.path)).performTouchInput { swipeUp(startY = centerY + 200f, endY = centerY - 300f) }
        waitForTag(DETAILS_SHEET_TAG)
        waitForText("a.jpg")
        waitForText("Fri, 30 Oct 2026 · 14:32:00")
        waitForText("Pictures/Trip KL")
        waitForText(trip.path)
        val size = Formatter.formatShortFileSize(context, a.length())
        waitForText("$size · 4000 × 3000 · 12 MP")
        // Not granted under Robolectric: asked once, and the location row says why there are no coordinates.
        assertTrue(Manifest.permission.ACCESS_MEDIA_LOCATION in shadowOf(rule.activity).lastRequestedPermission!!.requestedPermissions)
        waitForText("Location hidden by Android")
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        until { !exists(DETAILS_SHEET_TAG) }
        assertEquals(0, closed)
    }

    @Test fun theLocationPermissionIsAskedOncePerViewer() {
        seed()
        val registry = CountingRegistry()
        open(index = 0, registry = registry)
        showing(a)
        rule.onNodeWithTag(viewerImageTag(a.path)).performTouchInput { swipeUp(startY = centerY + 200f, endY = centerY - 300f) }
        waitForTag(DETAILS_SHEET_TAG)
        until { registry.launched.size == 1 }
        assertEquals(Manifest.permission.ACCESS_MEDIA_LOCATION, registry.launched.single())
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        until { !exists(DETAILS_SHEET_TAG) }
        rule.onNodeWithTag(ACTION_MORE_TAG).performClick()
        rule.onNodeWithTag(MENU_INFO_TAG).performClick()
        waitForTag(DETAILS_SHEET_TAG)
        rule.waitForIdle()
        assertEquals(1, registry.launched.size)
    }

    @Test fun infoInMoreOpensTheDetailsAndAGrantedPermissionDropsTheHiddenRow() {
        seed()
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.ACCESS_MEDIA_LOCATION)
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(ACTION_MORE_TAG).performClick()
        rule.onNodeWithTag(MENU_INFO_TAG).performClick()
        waitForTag(DETAILS_SHEET_TAG)
        waitForText("a.jpg")
        rule.waitForIdle()
        // Granted, and the photo has no GPS: no location row at all.
        assertFalse(exists(hasText("Location hidden by Android")))
    }

    @Test fun theTopMenuOffersTheSameChoices() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(VIEWER_MENU_TAG).performClick()
        listOf(MENU_INFO_TAG, MENU_OPEN_WITH_TAG, MENU_COPY_TAG, MENU_MOVE_TAG).forEach { waitForTag(it) }
        rule.onNodeWithTag(MENU_INFO_TAG).performClick()
        waitForTag(DETAILS_SHEET_TAG)
    }

    // ---- delete

    @Test fun deletingMovesToTheNextItem() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(ACTION_DELETE_TAG).performClick()
        waitForText("Delete 1 item?") // the dialog waits on the remembered choice and the video count
        rule.onNodeWithText("Move to Recycle bin").performClick()
        until { !a.exists() }
        showing(b)
        waitForText("1 moved to Recycle bin")
        assertEquals(0, closed)
    }

    @Test fun deletingTheLastItemShowsThePreviousOne() {
        seed()
        open(index = 3)
        showing(v)
        rule.onNodeWithTag(ACTION_DELETE_TAG).performClick()
        waitForText("Delete 1 item?") // the dialog waits on the remembered choice and the video count
        rule.onNodeWithText("Move to Recycle bin").performClick()
        until { !v.exists() }
        showing(c)
    }

    @Test fun deletingTheOnlyItemCloses() {
        seed()
        open(index = 0, folder = other.path)
        showing(lone)
        rule.onNodeWithTag(ACTION_DELETE_TAG).performClick()
        waitForText("Delete 1 item?") // the dialog waits on the remembered choice and the video count
        rule.onNodeWithText("Move to Recycle bin").performClick()
        until { !lone.exists() }
        until { closed == 1 }
    }

    // ---- video, share, edit, open with, copy / move

    @Test fun aVideoShowsItsPosterWithAPlayButtonThatOpensThePlayer() {
        seed()
        open(index = 3)
        showing(v)
        rule.onNodeWithTag(PLAY_TAG).performClick()
        assertEquals(listOf(playerRoute(v.path)), navigated)
    }

    @Test fun shareEditAndOpenWithHandTheFileToChoosers() {
        seed()
        open(index = 0)
        showing(a)
        val activity = shadowOf(rule.activity)
        rule.onNodeWithTag(ACTION_SHARE_TAG).performClick()
        until { activity.peekNextStartedActivity() != null }
        val share = activity.nextStartedActivity.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, share.action)

        rule.onNodeWithTag(ACTION_EDIT_TAG).performClick()
        val edit = activity.nextStartedActivity.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_EDIT, edit.action)
        assertEquals("content://media/external_primary/images/media/1", edit.data.toString())
        assertEquals("image/jpeg", edit.type)
        assertTrue(edit.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertTrue(edit.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        rule.onNodeWithTag(ACTION_MORE_TAG).performClick()
        rule.onNodeWithTag(MENU_OPEN_WITH_TAG).performClick()
        val view = activity.nextStartedActivity.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_VIEW, view.action)
        assertEquals(MediaStore.Images.Media.getContentUri("external_primary").buildUpon().appendPath("1").build(), view.data)
    }

    @Test fun moveToFolderHandsThisPhotoToThePickerAndTheViewerMovesOn() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(ACTION_MORE_TAG).performClick()
        rule.onNodeWithTag(MENU_MOVE_TAG).performClick()
        assertEquals(1, picks)
        assertEquals(Transfer.MOVE, vm.actions.pendingTransfer.value)
        assertEquals(setOf(a.path), vm.actions.selection.selected.toSet())
        // The picker's answer.
        rule.runOnUiThread { vm.actions.transferTo(other.path) }
        until { File(other, "a.jpg").isFile && !a.exists() }
        showing(b)
        waitForText("Moved 1 item")
    }

    @Test fun copyToFolderLeavesThePhotoWhereItIs() {
        seed()
        open(index = 0)
        showing(a)
        rule.onNodeWithTag(ACTION_MORE_TAG).performClick()
        rule.onNodeWithTag(MENU_COPY_TAG).performClick()
        assertEquals(Transfer.COPY, vm.actions.pendingTransfer.value)
        rule.runOnUiThread { vm.actions.transferTo(other.path) }
        until { File(other, "a.jpg").isFile }
        assertTrue(a.isFile)
        showing(a)
    }
}
