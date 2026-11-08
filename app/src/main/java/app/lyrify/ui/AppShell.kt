package app.lyrify.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.lyrify.R
import app.lyrify.data.LedgerState
import app.lyrify.lyrics.Removal
import app.lyrify.media.CurrentTrack
import app.lyrify.media.SweepProgress
import kotlinx.coroutines.launch

/** The five tabs: Now is the dock's orb, the others its capsule. */
enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    NOW(R.string.nav_now, R.drawable.ic_play_circle, R.drawable.ic_play_circle_fill),
    SONGS(R.string.nav_songs, R.drawable.ic_library_music, R.drawable.ic_library_music_fill),
    DUPLICATES(R.string.nav_duplicates, R.drawable.ic_content_copy, R.drawable.ic_content_copy_fill),
    TONES(R.string.nav_tones, R.drawable.ic_ring_volume, R.drawable.ic_ring_volume_fill),
    AI(R.string.nav_ai, R.drawable.ic_auto_awesome, R.drawable.ic_auto_awesome_fill),
}

/** A full screen over the tabs, without the dock; back returns to the tab. */
sealed interface Overlay {
    /** Check these, opened at [path] when it comes from a song. */
    data class Check(val path: String?) : Overlay

    data object Settings : Overlay

    companion object {
        val Saver: Saver<Overlay?, String> = Saver(
            save = {
                when (it) {
                    is Overlay.Check -> CHECK + it.path.orEmpty()
                    Overlay.Settings -> SETTINGS
                    null -> null
                }
            },
            restore = {
                when {
                    it == SETTINGS -> Overlay.Settings
                    it.startsWith(CHECK) -> Overlay.Check(it.removePrefix(CHECK).ifEmpty { null })
                    else -> null
                }
            },
        )

        private const val CHECK = "check:"
        private const val SETTINGS = "settings"
    }
}

/**
 * The app once set up: one Scaffold with the top bars and the dock (the toast slot above it), over the five tabs or a
 * full screen ([Overlay]). Back closes a sheet or dialog first, then an overlay, then returns to Now. Each tab keeps its
 * scroll position; the Songs query lives here so Now and AI can open Songs filtered, and search is one tap away. What
 * [checkAnswers] say shows here, also once Check these has closed or the phone has turned. The app's messages go to its
 * one [ToastState] ([LocalToasts]), which shows them above the dock or in the window on top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppShell(
    ledger: LedgerState,
    track: CurrentTrack?,
    sweep: SweepProgress?,
    connected: Boolean,
    ai: AiState,
    aiActions: AiActions,
    aiWaiting: Int,
    aiDoneToday: Int,
    access: Access,
    unrestricted: Boolean,
    versionName: String,
    checkActions: CheckActions,
    checkAnswers: CheckAnswers,
    onRetryFailed: () -> Unit,
    onRemove: suspend (String) -> Removal,
    onOpenNotificationAccess: () -> Unit,
    onOpenAllFilesAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.NOW) }
    var overlay by rememberSaveable(stateSaver = Overlay.Saver) { mutableStateOf<Overlay?>(null) }
    var songQuery by rememberSaveable(stateSaver = SongQuery.Saver) { mutableStateOf(SongQuery()) }
    var runLog by remember { mutableStateOf<String?>(null) }
    var aiInfo by remember { mutableStateOf(false) }
    // Search is one tap away: Now and AI land on Songs with the keyboard up; tapping Songs again goes to the top.
    var focusSearch by remember { mutableStateOf(false) }
    // Saved like the list's own place, so a turn of the phone is not read as a tap.
    var songsReselect by rememberSaveable { mutableIntStateOf(0) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val toasts = LocalToasts.current
    val backdrop = rememberGlassBackdrop()
    val holder = rememberSaveableStateHolder()
    val bars = Tab.entries.associateWith { TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState()) }
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val timing = PlainTiming.of(ai.supported, ai.enabled)
    val counts = remember(ledger) { ledger.counts() }
    val checks = remember(ledger) { checkList(ledger).size }
    val removal = rememberRemoval(ledger, track?.player, onRemove, toasts)
    val openSettings = { overlay = Overlay.Settings }
    val search = {
        tab = Tab.SONGS
        focusSearch = true
    }
    fun openSongs(query: SongQuery) {
        songQuery = query
        tab = Tab.SONGS
    }

    LaunchedEffect(focusSearch, tab) {
        if (focusSearch && tab == Tab.SONGS) {
            // The search bar attaches its requester in the frame the tab changes.
            withFrameNanos { }
            try {
                searchFocus.requestFocus()
                keyboard?.show()
            } catch (e: IllegalStateException) {
                // Not attached yet: the user taps the field.
            }
            focusSearch = false
        }
    }
    LaunchedEffect(checkAnswers) {
        checkAnswers.messages.collect { toasts.say(resources, it) }
    }
    BackHandler(enabled = overlay == null && tab != Tab.NOW) { tab = Tab.NOW }

    Scaffold(
        modifier = Modifier.nestedScroll(bars.getValue(tab).nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (overlay == null) {
                when (tab) {
                    Tab.SONGS -> SongSearchBar(songQuery, { songQuery = it }, searchFocus, openSettings, bars.getValue(tab).contentUnder(), backdrop)
                    Tab.DUPLICATES -> DuplicatesTopBar(bars.getValue(tab), backdrop, openSettings)
                    Tab.TONES -> TonesTopBar(bars.getValue(tab), backdrop, openSettings)
                    else -> TabTopBar(
                        tab = tab,
                        scrollBehavior = bars.getValue(tab),
                        backdrop = backdrop,
                        runLog = ai.supported,
                        onSearch = search,
                        onInfo = { aiInfo = true },
                        onSettings = openSettings,
                        onRunLog = { scope.launch { runLog = aiActions.readLog() } },
                    )
                }
            }
        },
        bottomBar = {
            if (overlay == null) {
                BottomChrome {
                    OrbDock(
                        selected = tab,
                        orb = nowOrb(track, ledger),
                        badges = tabBadges(checks, duplicatesBadge(), aiAttention(ai, aiWaiting)),
                        backdrop = backdrop,
                        onSelect = {
                            if (it == Tab.SONGS && tab == Tab.SONGS) {
                                songsReselect++
                                focusSearch = true
                            }
                            tab = it
                        },
                    )
                }
            }
        },
    ) { padding ->
        when (val shown = overlay) {
            is Overlay.Check -> CheckScreen(ledger, timing, checkActions, checkAnswers, shown.path) { overlay = null }
            Overlay.Settings -> SettingsScreen(
                access = access,
                unrestricted = unrestricted,
                versionName = versionName,
                onOpenNotificationAccess = onOpenNotificationAccess,
                onOpenAllFilesAccess = onOpenAllFilesAccess,
                onOpenAppInfo = onOpenAppInfo,
            ) { overlay = null }
            null -> AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    // The new tab comes from the side of the tab tapped; with reduced motion it only fades.
                    val side = tabSlide(initialState, targetState) * if (rtl) -1 else 1
                    val enter = if (reduced) {
                        fadeIn(tween(FADE_IN_MS))
                    } else {
                        slideInHorizontally(TAB_SLIDE) { it / SLIDE_SHARE * side } + fadeIn(tween(FADE_IN_MS, delayMillis = FADE_DELAY_MS))
                    }
                    enter togetherWith fadeOut(tween(FADE_OUT_MS))
                },
                label = "tab",
                // What the dock and the top bar's glass blur.
                modifier = Modifier.glassSource(backdrop, LocalGlassBlur.current),
            ) { shownTab ->
                holder.SaveableStateProvider(shownTab) {
                    val banners: @Composable (Modifier) -> Unit = { modifier ->
                        ShellBanners(ledger.readable, connected = connected || shownTab != Tab.NOW, onOpenNotificationAccess, modifier)
                    }
                    when (shownTab) {
                        Tab.NOW -> NowScreen(
                            ledger = ledger,
                            track = track,
                            sweep = sweep,
                            connected = connected,
                            timing = timing,
                            checks = checks,
                            aiTile = aiTile(ai, aiWaiting),
                            removal = removal,
                            contentPadding = padding,
                            banner = { banners(Modifier) },
                            onRetryFailed = onRetryFailed,
                            onOpenChecks = { overlay = Overlay.Check(it) },
                            onOpenGroup = { openSongs(SongQuery(group = it, sort = songQuery.sort)) },
                            onOpenAi = { tab = Tab.AI },
                            onSeeAll = { openSongs(SongQuery(sort = SongSort.RECENT)) },
                        )
                        Tab.SONGS -> Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                            SongsScreen(
                                ledger = ledger,
                                query = songQuery,
                                onQuery = { songQuery = it },
                                timing = timing,
                                removal = removal,
                                retrying = sweep?.retrying == true,
                                connected = connected,
                                reselect = songsReselect,
                                onRetryFailed = onRetryFailed,
                                onPickLyrics = { overlay = Overlay.Check(it) },
                                banner = { banners(Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 8.dp)) },
                            )
                        }
                        Tab.DUPLICATES -> DuplicatesScreen(contentPadding = padding, toasts = toasts)
                        Tab.TONES -> TonesScreen(ledger = ledger, contentPadding = padding, toasts = toasts)
                        Tab.AI -> AiScreen(
                            state = ai,
                            counts = counts,
                            waiting = aiWaiting,
                            doneToday = aiDoneToday,
                            actions = aiActions,
                            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
                            onOpenStatus = { openSongs(SongQuery(group = it.group, status = it, sort = songQuery.sort)) },
                            banner = { banners(Modifier.padding(start = ScreenPadding, end = ScreenPadding, bottom = 12.dp)) },
                        )
                    }
                }
            }
        }
    }

    RemoveLyricsDialog(removal)
    runLog?.let { RunLogSheet(it) { runLog = null } }
    if (aiInfo) AiInfoSheet { aiInfo = false }
}

/** Which side a new tab comes from: 1 from the end (a tab after the one shown), -1 from the start, 0 for the same tab. */
fun tabSlide(from: Tab, to: Tab): Int = to.ordinal.compareTo(from.ordinal).coerceIn(-1, 1)

/** Above the gesture area: the toast slot, then the dock; the dock hides while the keyboard is up, and the message stays above it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BottomChrome(dock: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 12.dp)) {
        ToastSlot(Modifier.padding(horizontal = ScreenPadding), padding = PaddingValues(bottom = 8.dp))
        AnimatedVisibility(
            visible = !WindowInsets.isImeVisible,
            enter = if (reduced) fadeIn() else slideInVertically { it } + fadeIn(),
            exit = if (reduced) fadeOut() else slideOutVertically { it } + fadeOut(),
        ) { dock() }
    }
}

/**
 * Now: the brand, Search and More options (Settings); AI: its name, Search, How it works and More options (Settings,
 * Run log). Clear at rest (Now over its cover backdrop); glass once the list scrolls under it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabTopBar(
    tab: Tab,
    scrollBehavior: TopAppBarScrollBehavior,
    backdrop: GlassBackdrop,
    runLog: Boolean,
    onSearch: () -> Unit,
    onInfo: () -> Unit,
    onSettings: () -> Unit,
    onRunLog: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    LyrifyTopBar(
        title = {
            if (tab == Tab.NOW) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandMark()
                    Text(stringResource(R.string.app_name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Text(stringResource(R.string.ai_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        under = scrollBehavior.contentUnder(),
        backdrop = backdrop,
        actions = {
            ChromeIconButton(R.drawable.ic_search, stringResource(R.string.menu_search), onSearch)
            if (tab == Tab.AI) ChromeIconButton(R.drawable.ic_info, stringResource(R.string.ai_info_cd), onInfo)
            Box {
                ChromeIconButton(R.drawable.ic_more_horiz, stringResource(R.string.menu_more), { menu = true })
                LyrifyMenu(expanded = menu, onDismiss = { menu = false }) {
                    MenuRow(
                        stringResource(R.string.menu_settings),
                        R.drawable.ic_settings,
                        onClick = {
                            menu = false
                            onSettings()
                        },
                    )
                    if (tab == Tab.AI && runLog) {
                        MenuRow(
                            stringResource(R.string.menu_run_log),
                            R.drawable.ic_notes,
                            onClick = {
                                menu = false
                                onRunLog()
                            },
                        )
                    }
                }
            }
        },
    )
}

/**
 * The one-line banners under the top bar, each with a button that opens its sheet: the song list unreadable (every tab),
 * and Lyrify not seeing the music app (Now; pass [connected] true elsewhere).
 */
@Composable
private fun ShellBanners(readable: Boolean, connected: Boolean, onOpenNotificationAccess: () -> Unit, modifier: Modifier = Modifier) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    if (readable && connected && sheet == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!readable) {
            Banner(R.drawable.ic_warning, stringResource(R.string.ledger_unreadable_title), stringResource(R.string.banner_why), colors.errorContainer, colors.onErrorContainer, onAction = { sheet = WHY })
        }
        if (!connected) {
            Banner(
                R.drawable.ic_link_off,
                stringResource(R.string.listener_not_connected_title),
                stringResource(R.string.banner_fix),
                brand.highlightContainer,
                brand.onHighlightContainer,
                onAction = { sheet = FIX },
            )
        }
    }
    when (sheet) {
        WHY -> HelpSheet(stringResource(R.string.ledger_unreadable_title), stringResource(R.string.ledger_unreadable)) { sheet = null }
        FIX -> HelpSheet(
            stringResource(R.string.listener_not_connected_title),
            stringResource(R.string.listener_not_connected),
            action = stringResource(R.string.setup_notifications_action),
            onAction = onOpenNotificationAccess,
        ) { sheet = null }
    }
}

private const val FADE_IN_MS = 180
private const val FADE_DELAY_MS = 40
private const val FADE_OUT_MS = 90

/** A new tab slides in from a tenth of the width, on the settle spring (Motion.settle). */
private const val SLIDE_SHARE = 10
private val TAB_SLIDE = spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntOffset.VisibilityThreshold)
private const val WHY = "why"
private const val FIX = "fix"
