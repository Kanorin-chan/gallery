package dev.kanorin.gallery.logic

import dev.kanorin.gallery.data.FavouritesSummary
import dev.kanorin.gallery.data.FolderPrefsEntity
import dev.kanorin.gallery.data.FolderSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderOrderingTest {
    private val root = "/storage/emulated/0"

    private fun folder(rel: String, count: Int = 3, latest: Long = 1, hidden: Boolean = false) = FolderSummary(
        path = "$root/$rel", name = rel.substringAfterLast('/'), count = count, totalSize = count * 10L,
        coverPath = "$root/$rel/cover.jpg", latestDate = latest, hidden = hidden,
    )

    private fun prefs(rel: String, pinned: Boolean? = null, hidden: Boolean? = null) =
        FolderPrefsEntity("$root/$rel", pinned, hidden, null, null, null)

    private fun arrange(
        folders: List<FolderSummary>,
        prefs: List<FolderPrefsEntity> = emptyList(),
        created: List<String> = emptyList(),
        showHidden: Boolean = false,
        favourites: FavouritesSummary = FavouritesSummary(0, null),
    ) = FolderOrdering.arrange(folders, prefs, created, root, showHidden, favourites)

    @Test fun favouritesIsFirstEvenWithNoFavouritesAndNoFolders() {
        val a = arrange(emptyList())
        assertEquals(listOf(FAVORITES_FOLDER), a.pinned.map { it.path })
        assertEquals(0, a.pinned[0].count)
        assertEquals("Favourites", a.pinned[0].name)
        assertTrue(a.others.isEmpty())
    }

    @Test fun favouritesCarriesItsCountAndCover() {
        val a = arrange(emptyList(), favourites = FavouritesSummary(27, "$root/x.jpg"))
        assertEquals(27, a.pinned[0].count)
        assertEquals("$root/x.jpg", a.pinned[0].coverPath)
    }

    @Test fun pinnedOrderIsFavouritesCameraScreenshotsThenUserPins() {
        val a = arrange(
            listOf(
                folder("Pictures/Trip", latest = 50),
                folder("Pictures/Screenshots", latest = 40),
                folder("DCIM/Camera", latest = 10),
                folder("DCIM/Screenshots", latest = 5),
                folder("Pictures/Memes", latest = 99),
            ),
            prefs = listOf(prefs("Pictures/Trip", pinned = true)),
        )
        assertEquals(
            listOf(FAVORITES_FOLDER, "$root/DCIM/Camera", "$root/DCIM/Screenshots", "$root/Pictures/Trip"),
            a.pinned.map { it.path },
        )
        // The second Screenshots folder is an ordinary one.
        assertEquals(listOf("$root/Pictures/Memes", "$root/Pictures/Screenshots"), a.others.map { it.path })
    }

    @Test fun screenshotsFallsBackToPicturesScreenshots() {
        val a = arrange(listOf(folder("Pictures/Screenshots"), folder("DCIM/Camera")))
        assertEquals(listOf(FAVORITES_FOLDER, "$root/DCIM/Camera", "$root/Pictures/Screenshots"), a.pinned.map { it.path })
    }

    @Test fun cameraOnAnotherVolumeIsNotTheCameraCard() {
        val sd = FolderSummary("/storage/ABCD-1234/DCIM/Camera", "Camera", 2, 20, "/storage/ABCD-1234/DCIM/Camera/a.jpg", 5, false)
        val a = arrange(listOf(sd))
        assertEquals(listOf(FAVORITES_FOLDER), a.pinned.map { it.path })
        assertEquals(listOf(sd.path), a.others.map { it.path })
    }

    @Test fun anAutoPinnedFolderCanBeUnpinned() {
        val a = arrange(listOf(folder("DCIM/Camera")), prefs = listOf(prefs("DCIM/Camera", pinned = false)))
        assertEquals(listOf(FAVORITES_FOLDER), a.pinned.map { it.path })
        assertEquals(listOf("$root/DCIM/Camera"), a.others.map { it.path })
    }

    @Test fun othersAreNewestFirst() {
        val a = arrange(listOf(folder("A", latest = 1), folder("B", latest = 3), folder("C", latest = 2)))
        assertEquals(listOf("B", "C", "A"), a.others.map { it.name })
    }

    @Test fun twoScreenshotsFoldersBothGetTheirParentAsSubtitle() {
        val a = arrange(listOf(folder("DCIM/Screenshots"), folder("Pictures/Screenshots"), folder("Pictures/Trip")))
        val shots = (a.pinned + a.others).filter { it.name == "Screenshots" }
        assertEquals(2, shots.size)
        assertEquals(setOf("DCIM", "Pictures"), shots.map { it.subtitle }.toSet())
        assertNull((a.pinned + a.others).first { it.name == "Trip" }.subtitle)
    }

    @Test fun subtitleIsTheRelativeParentPath() {
        val a = arrange(listOf(folder("Pictures/Trips/Day1"), folder("Download/Day1")))
        val bySubtitle = a.others.associate { it.path to it.subtitle }
        assertEquals("Pictures/Trips", bySubtitle["$root/Pictures/Trips/Day1"])
        assertEquals("Download", bySubtitle["$root/Download/Day1"])
    }

    @Test fun aHiddenTwinDoesNotMakeASubtitle() {
        val a = arrange(listOf(folder("Pictures/Trip"), folder(".cache/Trip", hidden = true)))
        assertNull(a.others.single().subtitle)
    }

    @Test fun nomediaFolderIsExcludedAndShownWhenAsked() {
        val folders = listOf(folder("Pictures/Trip"), folder("WhatsApp/Stickers", hidden = true))
        val off = arrange(folders)
        assertEquals(listOf("$root/Pictures/Trip"), off.others.map { it.path })
        val on = arrange(folders, showHidden = true)
        assertEquals(setOf("$root/Pictures/Trip", "$root/WhatsApp/Stickers"), on.others.map { it.path }.toSet())
        assertTrue(on.others.first { it.name == "Stickers" }.hidden)
    }

    @Test fun overrideHidesAndOverrideFalseBeatsTheRule() {
        val folders = listOf(folder("Pictures/Trip"), folder("WhatsApp/Stickers", hidden = true))
        val a = arrange(folders, prefs = listOf(prefs("Pictures/Trip", hidden = true), prefs("WhatsApp/Stickers", hidden = false)))
        assertEquals(listOf("$root/WhatsApp/Stickers"), a.others.map { it.path })
    }

    @Test fun hiddenListHoldsRuleAndOverrideHiddenAlways() {
        val folders = listOf(folder("Pictures/Trip"), folder("Pictures/Memes"), folder("WhatsApp/Stickers", hidden = true))
        val a = arrange(folders, prefs = listOf(prefs("Pictures/Trip", hidden = true)))
        assertEquals(setOf("$root/Pictures/Trip", "$root/WhatsApp/Stickers"), a.hidden.map { it.path }.toSet())
        assertFalse(a.hidden.any { it.path == "$root/Pictures/Memes" })
    }

    @Test fun hiddenPinnedFolderLeavesThePinnedSection() {
        val a = arrange(listOf(folder("DCIM/Camera")), prefs = listOf(prefs("DCIM/Camera", hidden = true)))
        assertEquals(listOf(FAVORITES_FOLDER), a.pinned.map { it.path })
    }

    @Test fun createdFolderWithoutMediaIsAnEmptyCardAtTheEnd() {
        val a = arrange(listOf(folder("A", latest = 1)), created = listOf("$root/Pictures/New one"))
        assertEquals(listOf("A", "New one"), a.others.map { it.name })
        val empty = a.others.last()
        assertEquals(0, empty.count)
        assertNull(empty.coverPath)
    }

    @Test fun createdFolderThatHasMediaIsNotDuplicated() {
        val a = arrange(listOf(folder("Pictures/Trip")), created = listOf("$root/Pictures/Trip"))
        assertEquals(1, a.others.size)
        assertEquals(3, a.others.single().count)
    }

    @Test fun createdFolderCanBePinnedAndNamedLikeAnother() {
        val a = arrange(
            listOf(folder("Pictures/Trip")),
            prefs = listOf(prefs("Pictures/Mine", pinned = true)),
            created = listOf("$root/Pictures/Mine", "$root/Download/Trip"),
        )
        assertEquals("$root/Pictures/Mine", a.pinned.last().path)
        val trips = a.others.filter { it.name == "Trip" }
        assertEquals(setOf("Pictures", "Download"), trips.map { it.subtitle }.toSet())
    }

    @Test fun aClashAcrossVolumesNamesTheVolume() {
        val sd = FolderSummary("/storage/ABCD-1234/Pictures/Trip", "Trip", 1, 1, "/storage/ABCD-1234/Pictures/Trip/a.jpg", 1, false)
        val sdRoot = FolderSummary("/storage/ABCD-1234/Trip", "Trip", 1, 1, "/storage/ABCD-1234/Trip/a.jpg", 1, false)
        val a = arrange(listOf(folder("Pictures/Trip"), folder("Trip"), sd, sdRoot))
        assertEquals(
            setOf("Pictures", "Storage", "SD card · Pictures", "SD card"),
            a.others.map { it.subtitle }.toSet(),
        )
    }
}
