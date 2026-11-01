package dev.kanorin.gallery

import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.kanorin.gallery.index.FsWalker
import dev.kanorin.gallery.logic.Transfer
import dev.kanorin.gallery.ui.bin.BIN_ROUTE
import dev.kanorin.gallery.ui.bin.BinScreen
import dev.kanorin.gallery.ui.bin.BinViewModel
import dev.kanorin.gallery.ui.common.SelectionActions
import dev.kanorin.gallery.ui.common.ToastHost
import dev.kanorin.gallery.ui.folder.FAVOURITES_ROUTE
import dev.kanorin.gallery.ui.folder.FOLDER_ARG
import dev.kanorin.gallery.ui.folder.FOLDER_ROUTE
import dev.kanorin.gallery.ui.folder.FolderScreen
import dev.kanorin.gallery.ui.folder.FolderViewModel
import dev.kanorin.gallery.ui.folders.HIDDEN_FOLDERS_ROUTE
import dev.kanorin.gallery.ui.folders.HiddenFoldersScreen
import dev.kanorin.gallery.ui.folders.HiddenFoldersViewModel
import dev.kanorin.gallery.ui.main.MainScreen
import dev.kanorin.gallery.ui.main.MainViewModel
import dev.kanorin.gallery.ui.permission.PermissionGate
import dev.kanorin.gallery.ui.permission.PermissionState
import dev.kanorin.gallery.ui.permission.launchAllFilesAccess
import dev.kanorin.gallery.ui.permission.launchManageMedia
import dev.kanorin.gallery.ui.permission.readPermissionState
import dev.kanorin.gallery.ui.pick.FolderPickerScreen
import dev.kanorin.gallery.ui.pick.PICK_FOLDER_ROUTE
import dev.kanorin.gallery.ui.player.PLAYER_ARG
import dev.kanorin.gallery.ui.player.PLAYER_INTENT_ARGUMENTS
import dev.kanorin.gallery.ui.player.PLAYER_INTENT_ROUTE
import dev.kanorin.gallery.ui.player.PLAYER_ROUTE
import dev.kanorin.gallery.ui.player.PlayerScreen
import dev.kanorin.gallery.ui.player.PlayerViewModel
import dev.kanorin.gallery.ui.player.playerRoute
import dev.kanorin.gallery.ui.settings.PERMISSIONS_ROUTE
import dev.kanorin.gallery.ui.settings.PermissionsSettingsScreen
import dev.kanorin.gallery.ui.settings.SETTINGS_ROUTE
import dev.kanorin.gallery.ui.settings.SettingsScreen
import dev.kanorin.gallery.ui.theme.GalleryTheme
import dev.kanorin.gallery.ui.viewer.VIEWER_ARGUMENTS
import dev.kanorin.gallery.ui.viewer.VIEWER_ROUTE
import dev.kanorin.gallery.ui.viewer.ViewerScreen
import dev.kanorin.gallery.ui.viewer.ViewerViewModel
import dev.kanorin.gallery.ui.viewer.viewerRoute

class MainActivity : ComponentActivity() {
    private var permissions by mutableStateOf(PermissionState(allFiles = false, manageMedia = false))

    /** A VIEW intent's viewer / player route until the NavHost has opened it (it may wait behind the permission screen). */
    private var pendingView by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated: the viewer is already in the restored back stack, or still pending.
        pendingView = if (savedInstanceState == null) viewRoute(intent) else savedInstanceState.getString(KEY_PENDING_VIEW)
        refreshPermissions()
        val graph = (application as GalleryApp).graph
        setContent {
            GalleryTheme {
                // Above every screen, so a toast or an op's progress line shows wherever the user is.
                ToastHost(graph.toasts) {
                    PermissionGate(
                        state = permissions,
                        settings = graph.settings,
                        onGrantAllFiles = { launchAllFilesAccess(this@MainActivity) },
                        onGrantManageMedia = { launchManageMedia(this@MainActivity) },
                    ) { GalleryNavHost(pendingView = pendingView, onViewOpened = { pendingView = null }) }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewRoute(intent)?.let { pendingView = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING_VIEW, pendingView)
    }

    /** Another app's "open with" (spec 6), or a camera's REVIEW: an image opens the viewer, a video the player. */
    private fun viewRoute(intent: Intent?): String? {
        if (intent == null || intent.action !in VIEW_ACTIONS) return null
        val uri = intent.data ?: return null
        val type = intent.type
            ?: runCatching { contentResolver.getType(uri) }.getOrNull()
            ?: FsWalker.mimeTypeOf(uri.lastPathSegment.orEmpty())
        return when {
            type?.startsWith("image/") == true -> viewerRoute(uri, type)
            type?.startsWith("video/") == true -> playerRoute(uri, type)
            else -> null
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions() // user may be back from Settings
        if (permissions.allFiles) (application as GalleryApp).graph.mediaIndex.onAppResumed()
    }

    private fun refreshPermissions() {
        permissions = readPermissionState(this)
        if (permissions.allFiles) (application as GalleryApp).graph.mediaIndex.start() // idempotent
    }

    private companion object {
        const val KEY_PENDING_VIEW = "pendingView"
        val VIEW_ACTIONS = setOf(Intent.ACTION_VIEW, MediaStore.ACTION_REVIEW, "com.android.camera.action.REVIEW")
    }
}

/** Screen routes; later tasks add theirs here. */
object Routes {
    const val MAIN = "main"
    const val HIDDEN_FOLDERS = HIDDEN_FOLDERS_ROUTE
    const val PICK_FOLDER = PICK_FOLDER_ROUTE
    const val BIN = BIN_ROUTE
    const val SETTINGS = SETTINGS_ROUTE
    const val PERMISSIONS = PERMISSIONS_ROUTE
    const val FOLDER = FOLDER_ROUTE
    const val FAVOURITES = FAVOURITES_ROUTE
    const val VIEWER = VIEWER_ROUTE
    const val PLAYER = PLAYER_ROUTE
    const val PLAYER_INTENT = PLAYER_INTENT_ROUTE
}

/** [start] is for tests that open one screen directly. [pendingView] (a VIEW intent's viewer / player) opens over it once. */
@Composable
internal fun GalleryNavHost(
    start: String = Routes.MAIN,
    pendingView: String? = null,
    onViewOpened: () -> Unit = {},
    nav: NavHostController = rememberNavController(),
) {
    NavHost(nav, startDestination = start) {
        composable(Routes.MAIN) {
            MainScreen(
                viewModel(factory = MainViewModel.Factory),
                onNavigate = { nav.navigate(it) },
                onPickFolder = { nav.navigate(Routes.PICK_FOLDER) },
            )
        }
        composable(Routes.PICK_FOLDER) { entry ->
            // The folder list comes from the main screen's ViewModel; the selection and the Move / Copy choice
            // belong to whichever screen began the pick: the folder screen below, else the main screen.
            val main = remember(entry) { nav.getBackStackEntry(Routes.MAIN) }
            val vm: MainViewModel = viewModel(main, factory = MainViewModel.Factory)
            val from = remember(entry) { nav.previousBackStackEntry }
            val actions = when {
                from == null -> vm.actions
                from.destination.route in FOLDER_ROUTES -> viewModel<FolderViewModel>(from, factory = FolderViewModel.Factory).actions
                from.destination.route == Routes.VIEWER -> viewModel<ViewerViewModel>(from, factory = ViewerViewModel.Factory).actions
                else -> vm.actions
            }
            // Once only: a second tap mustn't pop the screen below too.
            val done = { if (nav.currentBackStackEntry === entry) nav.popBackStack() }
            PickFolder(vm, actions, onPick = { actions.transferTo(it); done() }, onCreate = { actions.createFolderForTransfer(it); done() }, onBack = { done() })
        }
        composable(Routes.FOLDER, arguments = listOf(navArgument(FOLDER_ARG) { type = NavType.StringType })) {
            FolderRoute(nav)
        }
        composable(Routes.FAVOURITES) { FolderRoute(nav) }
        composable(Routes.HIDDEN_FOLDERS) {
            HiddenFoldersScreen(viewModel(factory = HiddenFoldersViewModel.Factory), onBack = nav::popBackStack)
        }
        // The ⋮ sheet's "Recycle bin" row links here (Task 11).
        composable(Routes.BIN) {
            BinScreen(viewModel(factory = BinViewModel.Factory), onBack = nav::popBackStack, onOpen = { nav.navigate(it) })
        }
        composable(Routes.SETTINGS) {
            val settings = (LocalContext.current.applicationContext as GalleryApp).graph.settings
            SettingsScreen(settings, onBack = nav::popBackStack, onPermissions = { nav.navigate(Routes.PERMISSIONS) })
        }
        composable(Routes.PERMISSIONS) { PermissionsSettingsScreen(onBack = nav::popBackStack) }
        composable(Routes.VIEWER, arguments = VIEWER_ARGUMENTS) { entry ->
            val vm: ViewerViewModel = viewModel(factory = ViewerViewModel.Factory)
            val activity = LocalActivity.current
            // Once only; opened by another app, back returns there.
            val close = {
                if (nav.currentBackStackEntry === entry) {
                    if (vm.args.fromIntent) activity?.finish() else nav.popBackStack()
                }
            }
            BackHandler(enabled = vm.args.fromIntent) { close() }
            ViewerScreen(vm, onBack = { close() }, onNavigate = { nav.navigate(it) }, onPickFolder = { nav.navigate(Routes.PICK_FOLDER) })
        }
        composable(Routes.PLAYER, arguments = listOf(navArgument(PLAYER_ARG) { type = NavType.StringType })) { entry ->
            PlayerRoute(nav, entry)
        }
        composable(Routes.PLAYER_INTENT, arguments = PLAYER_INTENT_ARGUMENTS) { entry -> PlayerRoute(nav, entry) }
    }
    // The lowest viewer / player on the back stack, tracked here: Navigation's own back-stack list is restricted API.
    var openWith by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(nav) {
        val track = NavController.OnDestinationChangedListener { controller, destination, _ ->
            if (openWith?.let { controller.isOnBackStack(it) } == false) openWith = null
            if (openWith == null && destination.route in OPEN_WITH_ROUTES) openWith = destination.route
        }
        nav.addOnDestinationChangedListener(track)
        onDispose { nav.removeOnDestinationChangedListener(track) }
    }
    LaunchedEffect(pendingView) {
        pendingView?.let { route ->
            // A second "open with" replaces the viewer / player it lands on (and anything over it) instead of piling up.
            val open = openWith?.takeIf { nav.isOnBackStack(it) }
            nav.navigate(route) { if (open != null) popUpTo(open) { inclusive = true } }
            onViewOpened()
        }
    }
}

private fun NavController.isOnBackStack(route: String): Boolean =
    try {
        getBackStackEntry(route)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

private val OPEN_WITH_ROUTES = setOf(Routes.VIEWER, Routes.PLAYER, Routes.PLAYER_INTENT)

@Composable
private fun PlayerRoute(nav: NavHostController, entry: NavBackStackEntry) {
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory)
    val activity = LocalActivity.current
    // Once only; opened by another app, back returns there.
    val close = {
        if (nav.currentBackStackEntry === entry) {
            if (vm.args.fromIntent) activity?.finish() else nav.popBackStack()
        }
    }
    BackHandler(enabled = vm.args.fromIntent) { close() }
    PlayerScreen(vm, onBack = { close() })
}

@Composable
private fun FolderRoute(nav: NavHostController) {
    FolderScreen(
        viewModel(factory = FolderViewModel.Factory),
        onBack = { nav.popBackStack() },
        onPickFolder = { nav.navigate(Routes.PICK_FOLDER) },
        onNavigate = { nav.navigate(it) },
    )
}

private val FOLDER_ROUTES = setOf(FOLDER_ROUTE, FAVOURITES_ROUTE)

@Composable
private fun PickFolder(vm: MainViewModel, actions: SelectionActions, onPick: (String) -> Unit, onCreate: (String) -> Unit, onBack: () -> Unit) {
    // Read once: a pick clears the choice, and the title mustn't flip while the screen animates out.
    val title = remember { if (actions.pendingTransfer.value == Transfer.COPY) "Copy to folder" else "Move to folder" }
    val folders by vm.folders.collectAsStateWithLifecycle()
    val columns by vm.folderColumns.collectAsStateWithLifecycle()
    FolderPickerScreen(
        title = title,
        model = folders,
        columns = columns,
        onColumns = vm::setFolderColumns,
        onPick = onPick,
        newFolderSiblings = vm::newFolderSiblings,
        onCreateFolder = onCreate,
        onBack = onBack,
    )
}
