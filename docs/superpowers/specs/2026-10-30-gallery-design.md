# Gallery: design

*App name: **Gallery** (Kano: "just gallery."). Project folder: `F:\gallery`.*

*2026-10-30 · status: draft, waiting for Kano's approval*
*The approved look is mockup v11 (`gallery-v11.html`, kept next to this file). Where this text and the mockup disagree, this text wins.*

Lines marked **(my call)** are Claude's decisions. Kano didn't make them, and he can overrule any of them.

---

## 1. Why

Kano's words: his phones *"dint come with a proper photo apps gallery"*. The gallery he uses now gets *"stuck for awhile for 'loading'"* right after he takes a photo. Google Photos *"is not showing all the image"*.

He wants a standard gallery that is fast, sees every file on the phone, and gets out of the way.

## 2. Goals

- **Sees everything.** Kano: *"request full permission"*. Every image and video on the phone shows up, in every folder (WhatsApp, Telegram, Download, all of it).
  - **One accepted gap (Kano chose this):** Android blocks every app from `Android/data/`, even with full access. Telegram keeps downloaded media there until "Save to gallery" is tapped, so those files are out of reach in v1.
  - Shizuku support could close the gap later.
- **Fast.** Kano: *"Must be fast. real fast."* A photo just taken is in the grid when he opens the app, with no "loading" wait.
- **Viewing first.** Kano: *"Lesser AI ish, view and things first."*
- **A standard gallery** with the specific behaviour in §5.

## 3. Non-goals (v1)

- No AI features, cloud backup, sync or accounts.
- **Tagging and tag search come later.** Kano: *"support tagging search and things later if we have."* v1 has no tag UI, but the data model keeps room for tags (§7). **(my call: no greyed-out placeholder row in v1)**
- No in-app photo editor. "Edit" hands the photo to the phone's editor app. **(my call. The mockup guessed basic rotate/crop; it's cut to keep v1 lean)**

## 4. Anti-goals

The build fails if any of these happen, even when everything else works:

- A spinner or "loading" state blocks the grid after a new photo is taken.
- A photo or folder that exists on the phone is missing from the app.
- Controls jump around: buttons change position when a mode or label changes.
- Gestures that are easy to trigger by accident, like the fast-scroll handle being clickable while invisible.
- Android's own confirmation popup appearing on every delete or move. **(my call. It defeats the app's own delete choice in §5.5)**

## 5. Screens and behaviour

### 5.1 Main screen top bar (both All and Folders)

One rounded search pill: `[ 🔍 Search … | mode button | ⋮ ]`

- **Mode button.** A single button that switches All ↔ Folders. It shows the current mode's icon and label ("All" / "Folders"). It sits just left of ⋮ with 11 px side padding, 6 px further right than a plain gap would put it (Kano tuned this). The ⋮ never moves.
- The placeholder text follows the mode: "Search photos" / "Search folders".
- **What search matches in v1:** file name, folder name, and dates typed as text ("oct 2026", "2026-10-28", "yesterday"). **(my call)**
- **⋮** opens a bottom sheet (§5.4).

### 5.2 All view (timeline)

- Grouped **Today → Yesterday → then dates** ("Tue, 28 Oct"). Older years show the year. Each group header shows the item count and a circle that selects the whole group.
- 4 per row by default. Videos show their duration on the thumbnail.
- **Fast-scroll handle** on the right edge:
  - Hidden by default. It appears only while scrolling and fades out 1.5 s after scrolling stops.
  - It can't be pressed while hidden (Kano: *"easy miss click"*).
  - Press and drag it to scrub through the library. A month bubble ("Oct 2026") shows while dragging. At the very bottom it shows the last group's month.
- **Pinch** changes the grid size: 2 to 6 per row. The photo under the fingers stays in place. A small "Grid 2 3 4 5 6" pill flashes to show the current size.
- **Selecting:**
  - **Hold** a photo: enters selection and selects it.
  - **Keep holding and drag**: selects every photo between the first one and the finger, in order. Moving back un-selects again.
  - Dragging near the top or bottom edge auto-scrolls.
  - Once selecting, a **tap** adds or removes one photo.
  - Holding an *already selected* photo and dragging **deselects** that range. **(my call)**
  - The selection bar replaces the search pill at exactly the same height: `✕ · "N selected" + total size · select all · share · delete · ⋮`. It leaves selection when the count hits 0.
  - The bar's **⋮** offers **Move to folder** and **Copy to folder**. These are real file moves on disk. **(my call)**

### 5.3 Folders view

- A grid of folder cards: cover photo, name, item count. **All covers are exactly the same size.** Long names are cut short with "…".
- 3 per row by default. Pinch changes it from 2 to 4.
- Two sections:
  - **Pinned**: **Favourites** (always there, a built-in folder of ♡'d items, heart badge on the cover), then Camera, then Screenshots.
  - **All folders · N**: every other folder, with **"+ New folder" always as the last card**.
- Long-pressing a folder offers pin / hide / rename. **(my call)**
  - Rename changes the real folder on disk.
  - It isn't offered for folders other apps write into (DCIM/Camera, Screenshots, WhatsApp, Telegram and similar). Renaming those breaks the app that writes there, because it just recreates the old folder. **(my call)**
- **New folder:**
  1. A dialog asks for the name.
  2. The name is checked: not empty, none of `\ / : * ? " < > |`, and not the same as an existing folder (ignoring case). The error shows inline.
  3. On Create, a **real directory is made on the phone**. **(my call: it goes under `Pictures/`)**
  4. The folder shows immediately, **even while empty**, just before the "+ New folder" card.
  5. A toast says *Folder "X" created* with **ADD PHOTOS**. That opens the All view in selection mode, then asks **Move** or **Copy**. **(my call)**

### 5.4 ⋮ sheet (main screen)

- **Sort by**: Date taken (default, newest first), Date modified, Name, Size, Length (videos by duration; photos after them). Order can be flipped. Picking a sort or a Show chip moves nothing on the sheet (2026-10-31, Kano: *"the UI keep shifting when i tap another, make sure they dont move around"*).
- **Show**: Photos, Videos, GIFs, Screenshots, Favourites (toggle chips).
- **Group by**: Day (default) / Month / Year / None.
- **Recycle bin** (shows its item count), **Hidden folders**, **Settings**.

### 5.5 Delete

- **Asks every time** (Kano: *"when delete ask if recycle bin / delete directly"*).
- The dialog says "Delete N items?" with a subline like "2 photos, 1 video". It has two big choices:
  - **Move to Recycle bin**: "Restore any time within 30 days".
  - **Delete permanently**: "Gone from the phone. Can't be undone".
- A **"Remember my choice"** tick-box stops the question. Settings can undo it.
- After moving to the bin, a toast offers **UNDO**.
- Items are kept in the bin for 30 days, then it empties itself. **(my call)**
- **How the bin works (my call):**
  - A binned file is really moved into a hidden bin folder the app owns on the phone's storage (`.nomedia` inside), so other apps stop showing it.
  - Its original path is recorded so Restore puts it back exactly where it was.
  - Uninstalling the app doesn't destroy the bin.
  - Android's own trash isn't used: it can't hold files the media index doesn't know about.
- The Recycle bin screen can restore items or delete them for good.

### 5.6 Inside a folder

- App bar: `←`, folder name, "N items · total size", search, ⋮.
- Chips under the title for quick sort / type / favourites.
- 3 per row by default. **Pinch changes it** from 2 to 6, or tap a number on the grid pill.
- Each folder remembers its own grid size and sort. **(my call)**
- Selection works the same as in §5.2.

### 5.7 Photo viewer

- Full screen. Pinch to zoom, double-tap to zoom, swipe sideways for the next or previous item.
- Top: back, date + time, ⋮.
- Bottom: Share · Favourite · Edit · Delete · More.
- **Swipe up for the details** (Kano: *"other proper info"*):
  - date taken and date modified
  - file name, size, resolution and megapixels
  - full path
  - camera: aperture, shutter, ISO, focal length, flash
  - location: coordinates always; the place name when the phone can look it up **(my call)**

### 5.8 Video player

- **Upright and sideways.** It follows the phone's rotation, and a **rotate button** (bottom right) switches by hand.
- **Rotation lock** (bottom-left button) keeps the current orientation when the phone turns. It flashes "Rotation locked" / "unlocked". Kano: *"lock the rotation, some people will need. not for seek."*
- **Control row:**
  - left: rotation lock
  - centre: skip back · play/pause · skip forward, **exactly centred** in both orientations
  - right: speed · rotate
- **Hold anywhere = 2×** while the finger is down, with a "2× speed" pill. While still holding:
  - **slide up** (~40 px) = **0.5×**
  - **slide down** = "Release to lock 2×". Letting go keeps 2× locked ("2× locked · hold to unlock"). A tap only shows or hides the controls; a hold ends the lock (2026-10-31, Kano: *"if on tap: just show the progress bar and thing. dont cancle. only cancel is hold once more"*).
  - No on-screen hint text explains the slides (Kano removed it).
- **Double-tap left / right** = skip back / forward. The skip length is **5 s or 10 s** (setting, default 10 s).
- **Progress bar:** current and total time, buffered part, drag to seek, preview frame while scrubbing.
- **Long videos.** Kano: *"probably running video that longer than 1 hrs."*
  - Times show hours (`1:04:13`).
  - Scrubbing and preview frames stay usable on multi-hour files.
  - Long videos open as fast as short ones.
  - Videos over 10 min resume where they were left. **(my call)**
- **Speed button:** a tap opens a **list that pops up above it**: 0.25, 0.5, 0.75, 1.0 (Normal), 1.25, 1.5, 1.75, 2×. The current speed has a tick, picking one closes the list, and tapping outside closes it too. Sideways, the list uses two columns so it fits.
- **Zoom into part of the video while it plays:**
  - Pinch zooms from 1× to 5×.
  - Drag moves around while zoomed.
  - A finger that moves straight away pans; a finger held still gives 2×.
  - A "2.3× · Reset" pill shows while the zoom changes and fades 0.7 s after, and with the controls; tapping it resets (2026-10-31, Kano: *"it should fade away quick"*).
- The hold speed defaults to 2× and can be changed in Settings. **(my call)**

### 5.9 Settings (v1)

Skip length · hold speed · reset "remember my delete choice" · default grid sizes · show hidden folders · grant permissions again.

Dark theme only in v1. **(my call. The mockup is dark)**

### 5.10 First run

The app explains in one screen why it needs full access, then opens Android's **"All files access"** page. It also requests **media management** so deletes and moves don't trigger Android's popup (§4).

Without the permissions the app shows that one screen, not an empty grid.

## 6. Platform and how it's made

- **Android, latest.** Kano: *"android, latest."* Target API 36. **(my call: minimum Android 11 / API 30, the first version with "All files access")**
- **Devices:** a mix of brands. The main phone is a **RedMagic 8 Pro** (Nubia's RedMagic OS).
  - Its Android version gets read off the phone over USB at test time, not assumed.
  - Manufacturer quirks around "All files access" and delete popups get checked on it specifically.
- **Package id:** `dev.kanorin.gallery`. **(my call)**
- **Installed by APK, not the Play Store.** **(my call. Google Play restricts "All files access" for galleries)**
- **Native Kotlin + Jetpack Compose**, with Media3 (ExoPlayer) for video. **(my call, chosen for speed)**
- It registers as a handler for opening images and videos, so other apps and the camera can "open with" it. **(my call)**
- **Language:** English UI.

## 7. Speed and correctness: what "fast" means

My targets **(my call)**. The builder measures these on a real phone:

- Cold start to the first thumbnails on screen in **≤ 1 s**.
- Taking a photo, then switching to the app: **the new photo is already there**, with no loading state.
- Normal scrolling shows no blank tiles. A fast-scroll drag may show placeholders briefly.
- **A full rescan never blocks the UI.**

Constraints the builder must respect:

- Show what's already known first, then update. Keep a persistent thumbnail cache on disk, watch for media changes so new files appear on their own, and rescan only what changed.
- **"Shows everything"** means files Android's media index doesn't know about must still be found and shown.
- Folders containing a `.nomedia` file, and folders whose name starts with a dot (`.thumbnails`, `.Statuses`), are found but **listed under Hidden folders by default**. One tap unhides them. These are mostly app junk like stickers and caches, and Android hides them the same way. **(my call)**
- **A folder is its full path**, not its name. **(my call)**
  - `DCIM/Screenshots` and `Pictures/Screenshots` are two cards. When names clash, the card shows its parent path.
  - Pinned "Camera" means `DCIM/Camera` on internal storage.
  - The Folders view is a flat list of every folder that directly holds media.
  - The New-folder duplicate check applies within the same parent folder.
- **Storage covered:** internal storage and SD cards. A USB drive Android has mounted gets indexed too, because the two can't be told apart reliably. That is harmless. **(my call)**
- **Scale:** Kano: *"assume it is more than [50k]. video also."* It's designed for 100k+ items, many of them videos, and it's tested at that size too.
- Favourites, hidden or pinned folders, per-folder grid and sort, the bin's original paths and (later) tags live in the app's own small database. No file is renamed or moved to store that data. The only exceptions are the user's own Move, Copy, Rename and Delete.

## 8. Testing: Claude builds and tests it itself

Kano: *"you might need to test it your self. and self building it."*

- This PC already has the Android SDK (API 36), an emulator (`Medium_Phone_API_36`) and a JDK.
- The test library on the emulator includes:
  - Camera, Screenshots, WhatsApp and Telegram-style folders
  - a `.nomedia` folder
  - upright and sideways videos
  - a file the media index doesn't know about
  - a 100k-item library, to test speed
  - at least one video over 1 hour
- Every behaviour in §5 gets checked on the emulator. The final speed checks (§7) happen on the RedMagic 8 Pro over USB.

## 9. Left to the builder

Code structure, libraries beyond those named above, database layout, thumbnail pipeline details, animations, the icon set, empty and error states, and the ⋮ menu inside the viewer and player.
