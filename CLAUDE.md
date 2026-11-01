# Gallery — CLAUDE.md

## ⛔ STOP. Read before you write.

**Do not write, edit, or plan code until you have read every file in this list.**
Not one of them. All of them. If you are about to edit a file and you have not
read these, you have not started work — you have started damage.

1. `docs/STATUS.md` — what exists, where it lives, what's wired, what's verified
2. `docs/superpowers/specs/2026-10-30-gallery-design.md` — the design Kano approved (the *why* and every behaviour)
3. `docs/superpowers/plans/2026-10-30-gallery.md` — the build plan, task by task
4. `docs/architecture/` — every file in here (empty until a tool or subsystem needs one)

`docs/archive/` is NOT in the read path. It is old session logs.
`docs/superpowers/specs/gallery-v11.html` is the approved mockup; open it only when a question is about the look.

## The docs can be wrong. The code cannot.

Where a doc and the code disagree, **the code is right.** Fix the doc then and
there, say so out loud, and continue.

## Before you write ANY function

**Search for it first.** It probably exists.

- Exists and fits → **import it.** Never copy its body.
- Exists but doesn't fit → name the `file:line`, extend it properly or ask. Never fork it.
- Truly doesn't exist → build it properly, in the right package, to the conventions here.

## Hardcoding is a bug report, not an implementation

An invented constant to make two modules fit is an interface mismatch. **Say so.**

## "It runs" is not done

Done = every value flowing through came out of a real module and you can name which one.

## Kano's rules for this project (verbatim)

- 2026-10-30: *"you might need to test it your self. and self building it...."*
- 2026-10-30: *"remember to check with android studio or whatever first before handing me over."* → Never hand over a build that hasn't been installed and clicked through on the emulator (or a real phone) by us first.
- 2026-10-31: *"un.. you can send me the build and i check on my phones is also fine. real review also."* / *"my phone have all the photos i need. and video. so let me test it."* / *"try to use as much resource we can."* → His phones (real library) are the at-scale test; we still smoke the build on the emulator before sending it.

## Project specifics

- **What:** a fast, offline Android photo + video gallery for Kano's phones (main: RedMagic 8 Pro). App name "Gallery", package `dev.kanorin.gallery`.
- **Stack:** Kotlin, Jetpack Compose, Room, Paging 3, Coil 3, Media3 (ExoPlayer), DataStore. Single `app` module. minSdk 30, target/compile 36.
- **Build:** `scripts/gradle.ps1 <tasks>` — sets `JAVA_HOME` to Android Studio's JBR 21, then runs `gradlew`. Never rely on the system JDK 25.
- **Tests:** `scripts/gradle.ps1 testDebugUnitTest` (pure-Kotlin + Robolectric). Device tests: `connectedDebugAndroidTest` on emulator `Medium_Phone_API_36` or the phone over USB.
- **RAM:** this PC often sits at 95%+ RAM (games, MuMu). Check free RAM before starting the Android emulator. Never touch the MuMu emulator — it isn't ours.
- **Git:** repo-local identity `Kanorin <8781tyo@gmail.com>`. **No commits unless Kano says "commit".** Stage (`git add -A`) after each reviewed task as the checkpoint.
- **Conventions that will bite you:**
  - A file is identified by its **absolute path** everywhere (DB keys, selection, favourites). Moves/renames done by this app must update every table that stores a path.
  - Pure logic (grouping, selection ranges, gestures, zoom math, validators, policies) lives in `dev.kanorin.gallery.logic` with **no Android imports**, tested on the JVM.
  - All UI copy is English and fixed by the design doc; don't reword it.
  - Room schema v1 is frozen from the first device install (2026-10-31). Any entity change needs a version bump + a Migration + a MigrationTestHelper test. Never add a destructive fallback.
