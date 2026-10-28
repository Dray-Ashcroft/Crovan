package com.dking.crocapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.dking.crocapp.data.preferences.UserPreferencesRepository
import com.dking.crocapp.croc.BinarySetupPhase
import com.dking.crocapp.croc.CrocBinaryManager
import com.dking.crocapp.ui.components.LiquidBackdrop
import com.dking.crocapp.ui.components.liquidGlass
import com.dking.crocapp.ui.guide.GuideScreen
import com.dking.crocapp.ui.history.HistoryScreen
import com.dking.crocapp.ui.navigation.CrocDestination
import com.dking.crocapp.ui.receive.ReceiveScreen
import com.dking.crocapp.ui.receive.ReceiveViewModel
import com.dking.crocapp.ui.scanner.QrScannerScreen
import com.dking.crocapp.ui.send.SendScreen
import com.dking.crocapp.ui.send.SendViewModel
import com.dking.crocapp.ui.setup.CrocBinarySetupScreen
import com.dking.crocapp.ui.settings.SettingsScreen
import com.dking.crocapp.ui.settings.SettingsViewModel
import com.dking.crocapp.ui.theme.CrocTheme
import com.dking.crocapp.util.QrCodeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedContent = handleShareIntent(intent)
        val binaryManager = (application as CrocApp).binaryManager

        setContent {
            val settingsViewModel: SettingsViewModel = viewModel()
            val prefs by settingsViewModel.preferences.collectAsStateWithLifecycle()

            val isDark = when (prefs.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            CrocTheme(
                darkTheme = isDark,
                amoledDark = prefs.amoledDark
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Soft colour field behind every tab — what the glass tints against.
                        LiquidBackdrop(modifier = Modifier.fillMaxSize())
                        CrocApp(
                            sharedContent = sharedContent,
                            settingsViewModel = settingsViewModel,
                            binaryManager = binaryManager
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?): SharedContent {
        if (intent == null) return SharedContent.None

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                // Check for file URI first
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (uri != null) {
                    SharedContent.Files(listOf(uri))
                } else {
                    // Fallback to shared text (e.g. URLs, copied text from other apps)
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) {
                        SharedContent.Text(text)
                    } else {
                        SharedContent.None
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) {
                    SharedContent.Files(uris)
                } else {
                    SharedContent.None
                }
            }
            Intent.ACTION_VIEW -> {
                val rawData = intent.data?.toString().orEmpty()
                val code = QrCodeParser.parseCode(rawData)
                when {
                    code.isEmpty() -> SharedContent.None
                    // A panel's QR: the panel receives, so this phone sends.
                    QrCodeParser.isSendDeepLink(rawData) -> SharedContent.SendCode(code)
                    else -> SharedContent.ReceiveCode(code)
                }
            }
            else -> SharedContent.None
        }
    }
}

sealed class SharedContent {
    data object None : SharedContent()
    data class Files(val uris: List<Uri>) : SharedContent()
    data class Text(val text: String) : SharedContent()
    data class ReceiveCode(val code: String) : SharedContent()
    data class SendCode(val code: String) : SharedContent()
}

@Composable
fun CrocApp(
    sharedContent: SharedContent = SharedContent.None,
    settingsViewModel: SettingsViewModel,
    binaryManager: CrocBinaryManager
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val setupState by binaryManager.setupState.collectAsStateWithLifecycle()
    val retryScope = rememberCoroutineScope()

    val sendViewModel: SendViewModel = viewModel()
    val receiveViewModel: ReceiveViewModel = viewModel()

    // Send and Receive live side by side in one pager on the "home" route.
    // Both screens are built once and kept, so switching only slides them:
    // rebuilding a whole screen on every tap is what froze slow smart panels
    // for ~0.5 s. Rimas (panel mode) is Receive-only: one page, no bottom bar.
    val appPrefs by settingsViewModel.preferences.collectAsStateWithLifecycle()
    val panelMode = appPrefs.panelMode
    val homeTabs = if (panelMode) listOf(HomeTab.RECEIVE) else listOf(HomeTab.SEND, HomeTab.RECEIVE)
    val pagerState = rememberPagerState(initialPage = 0) { homeTabs.size }
    // A tab another part of the app asked for (share, deep link, History);
    // applied by the home route once it is showing.
    var pendingTab by remember { mutableStateOf<HomeTab?>(null) }

    fun goToTab(tab: HomeTab) {
        pendingTab = tab
        val route = navController.currentDestination?.route
        if (route != null && route != HOME_ROUTE) {
            navController.popBackStack(HOME_ROUTE, inclusive = false)
        }
    }

    val showBottomBar = currentRoute == HOME_ROUTE && !panelMode

    var handledSharedContent by rememberSaveable { mutableStateOf(false) }
    if (sharedContent !is SharedContent.None && !handledSharedContent) {
        handledSharedContent = true
        when (sharedContent) {
            is SharedContent.Files -> {
                sendViewModel.addFiles(sharedContent.uris)
                // Show the Send tab so the user sees the shared files
                androidx.compose.runtime.LaunchedEffect(Unit) { goToTab(HomeTab.SEND) }
            }
            is SharedContent.Text -> {
                sendViewModel.setSharedText(sharedContent.text)
                // Show the Send tab in text mode
                androidx.compose.runtime.LaunchedEffect(Unit) { goToTab(HomeTab.SEND) }
            }
            is SharedContent.SendCode -> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    sendViewModel.useScannedCode(sharedContent.code)
                    goToTab(HomeTab.SEND)
                }
            }
            is SharedContent.ReceiveCode -> {
                // croc:// deep link: fill the code and (if the croc binary is ready)
                // start receiving, mirroring the QR-scan flow.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    receiveViewModel.setCodeFromQr(sharedContent.code)
                    goToTab(HomeTab.RECEIVE)
                    if (binaryManager.isBinaryReady()) receiveViewModel.startReceive()
                }
            }
            else -> {}
        }
    }

    // Only "home" is a tab route now; everything else slides in over it.
    val tabRoutes = listOf(HOME_ROUTE)

    val shouldShowSetup = setupState.phase == BinarySetupPhase.Error ||
            (!binaryManager.isBinaryReady() && setupState.phase != BinarySetupPhase.Ready)

    if (shouldShowSetup) {
        CrocBinarySetupScreen(
            state = setupState,
            onRetry = {
                retryScope.launch(Dispatchers.IO) {
                    binaryManager.initialize()
                }
            }
        )
        return
    }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = slideInVertically(tween(PAGE_MS, easing = PageEasing)) { it } +
                        fadeIn(tween(PAGE_MS, easing = PageEasing)),
                exit = slideOutVertically(tween(PAGE_MS, easing = PageEasing)) { it } +
                        fadeOut(tween(PAGE_MS / 2))
            ) {
                // Floating liquid-glass pill instead of a flat full-width bar.
                NavigationBar(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .liquidGlass(
                            shape = NavPillShape,
                            tint = MaterialTheme.colorScheme.surfaceContainer,
                            fillAlpha = 0.6f,
                            shadowElevation = 8.dp
                        )
                        .clip(NavPillShape),
                    containerColor = Color.Transparent,
                    tonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0)
                ) {
                    val shownTab = homeTabs.getOrNull(pagerState.targetPage)
                    CrocDestination.bottomNavItems.forEach { destination ->
                        val tab = if (destination == CrocDestination.Send) HomeTab.SEND else HomeTab.RECEIVE
                        val selected = shownTab == tab
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                val index = homeTabs.indexOf(tab)
                                if (index >= 0) {
                                    retryScope.launch {
                                        pagerState.animateScrollToPage(
                                            index,
                                            animationSpec = tween(PAGE_MS, easing = PageEasing)
                                        )
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = stringResource(destination.labelRes)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(destination.labelRes),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        // Smart panels / big tablets: much weaker GPU per pixel, so page
        // switches there use a plain slide (no card scale/clip, no alpha).
        val largeScreen = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
        Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = HOME_ROUTE,
            modifier = Modifier.padding(paddingValues),
            // Page-style motion, like swiping between pages of a book:
            //  • tab ↔ tab: both pages slide a full screen width together over
            //    the fixed glass backdrop (no half-slide + crossfade mush).
            //  • opening Settings / History / Guide / Scanner: the new page slides
            //    in from the right, the old one drifts a quarter-width left and
            //    dims; back reverses it exactly.
            // Every part uses the same duration and easing, so nothing is still
            // moving after something else has stopped — that mismatch is what
            // reads as stutter.
            enterTransition = {
                val fromIndex = tabRoutes.indexOf(initialState.destination.route)
                val toIndex = tabRoutes.indexOf(targetState.destination.route)
                if (fromIndex >= 0 && toIndex >= 0) {
                    // Pure slide: an alpha fade makes Android render each whole
                    // page off-screen every frame, which is the main source of
                    // jank. The card fill (TabPageCard) already keeps pages solid.
                    val direction = if (toIndex > fromIndex) 1 else -1
                    slideInHorizontally(pageSpec()) { direction * it }
                } else {
                    slideInHorizontally(pageSpec()) { it }
                }
            },
            exitTransition = {
                val fromIndex = tabRoutes.indexOf(initialState.destination.route)
                val toIndex = tabRoutes.indexOf(targetState.destination.route)
                if (fromIndex >= 0 && toIndex >= 0) {
                    val direction = if (toIndex > fromIndex) -1 else 1
                    slideOutHorizontally(pageSpec()) { direction * it }
                } else if (largeScreen) {
                    slideOutHorizontally(pageSpec()) { -it / 4 }
                } else {
                    slideOutHorizontally(pageSpec()) { -it / 4 } +
                            fadeOut(pageFloatSpec(), targetAlpha = 0.4f)
                }
            },
            popEnterTransition = {
                if (largeScreen) {
                    slideInHorizontally(pageSpec()) { -it / 4 }
                } else {
                    slideInHorizontally(pageSpec()) { -it / 4 } +
                            fadeIn(pageFloatSpec(), initialAlpha = 0.4f)
                }
            },
            popExitTransition = {
                slideOutHorizontally(pageSpec()) { it }
            }
        ) {
            composable(HOME_ROUTE) {
                // Apply a tab requested from elsewhere (share, deep link, History).
                androidx.compose.runtime.LaunchedEffect(pendingTab, homeTabs) {
                    val tab = pendingTab ?: return@LaunchedEffect
                    val index = homeTabs.indexOf(tab)
                    if (index >= 0) pagerState.scrollToPage(index)
                    pendingTab = null
                }
                // Leaving Rimas: land on Receive (where the panel was), not Send.
                var lastPanelMode by remember { mutableStateOf<Boolean?>(null) }
                androidx.compose.runtime.LaunchedEffect(panelMode) {
                    if (lastPanelMode == true && !panelMode) {
                        pagerState.scrollToPage(homeTabs.indexOf(HomeTab.RECEIVE))
                    }
                    lastPanelMode = panelMode
                }
                // System Back on Receive goes to Send first (as before), then exits.
                androidx.activity.compose.BackHandler(enabled = !panelMode && pagerState.currentPage != 0) {
                    retryScope.launch { pagerState.animateScrollToPage(0) }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // Keep the other tab built and ready (this is the whole point).
                    beyondViewportPageCount = 1,
                    // Swipe between Send and Receive; Rimas has a single page.
                    userScrollEnabled = !panelMode,
                    key = { homeTabs.getOrElse(it) { HomeTab.RECEIVE } }
                ) { page ->
                    when (homeTabs.getOrElse(page) { HomeTab.RECEIVE }) {
                        HomeTab.SEND -> SendScreen(
                            viewModel = sendViewModel,
                            onNavigateToGuide = {
                                navController.navigate(CrocDestination.Guide.route)
                            },
                            onOpenScanner = {
                                navController.navigate("scanner_send")
                            },
                            onNavigateToHistory = {
                                navController.navigate(CrocDestination.History.route)
                            },
                            onNavigateToSettings = {
                                navController.navigate(CrocDestination.Settings.route)
                            }
                        )
                        HomeTab.RECEIVE -> ReceiveScreen(
                            viewModel = receiveViewModel,
                            onOpenScanner = {
                                navController.navigate("scanner")
                            },
                            onNavigateToHistory = {
                                navController.navigate(CrocDestination.History.route)
                            },
                            onNavigateToSettings = {
                                navController.navigate(CrocDestination.Settings.route)
                            }
                        )
                    }
                }
            }
            composable(CrocDestination.History.route) {
                HistoryScreen(
                    onCodeSelected = { code ->
                        receiveViewModel.setCodeFromQr(code)
                        goToTab(HomeTab.RECEIVE)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(CrocDestination.Settings.route) {
                SettingsScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable(CrocDestination.Guide.route) {
                GuideScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable("scanner") {
                QrScannerScreen(
                    onCodeScanned = { code ->
                        // The scanner screen goes back by itself (to Receive) after a
                        // scan; navigating here as well used to drop the user on Send.
                        receiveViewModel.setCodeFromQr(code)
                        receiveViewModel.startReceive()
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
            composable("scanner_send") {
                // Scan a panel's QR: fill its code, then the user picks files and sends.
                QrScannerScreen(
                    onCodeScanned = { code ->
                        // The scanner screen navigates back by itself after a scan;
                        // popping here too would skip past Send.
                        sendViewModel.useScannedCode(code)
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
        // Timer for the "Meko / Rimas is summoned" label shown beside the mode dot.
        com.dking.crocapp.ui.components.ModeCalloutHost()
        }
    }
}

// ── Home: Send + Receive pager ─────────────────────────────────
private const val HOME_ROUTE = "home"

private enum class HomeTab { SEND, RECEIVE }

// ── Page motion ─────────────────────────────────────────────────
// Material 3 "emphasized" curve: quick start, long gentle landing.
private val PageEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val PAGE_MS = 380

private fun pageSpec() = tween<IntOffset>(PAGE_MS, easing = PageEasing)
private fun pageFloatSpec() = tween<Float>(PAGE_MS, easing = PageEasing)

private val NavPillShape = RoundedCornerShape(32.dp)

/**
 * Card-deck page effect for the three main tabs.
 *
 * While a tab is settled on screen it is invisible glass over the backdrop,
 * exactly as before. The moment it starts leaving or arriving it lifts into
 * a card: it shrinks a little, gets rounded corners, a frosted fill and a
 * bright rim, and slides as one solid sheet. As it lands, all of that melts
 * back into the page. Because both pages shrink while moving, a gap opens
 * between them — so it reads as two cards gliding past each other, not as
 * two transparent screens overlapping.
 *
 * Every value is read inside graphicsLayer / draw lambdas, so the animation
 * only re-draws; the screen itself never recomposes per frame.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun AnimatedVisibilityScope.TabPageCard(
    simple: Boolean = false,
    content: @Composable () -> Unit
) {
    if (simple) {
        // Large screens: just the slide. Scaling + rounded clipping a whole
        // panel-sized page every frame is what drops frames there.
        Box(modifier = Modifier.fillMaxSize()) { content() }
        return
    }
    // 0 = settled page, 1 = fully a card (off-screen / just starting to move).
    val cardness = transition.animateFloat(
        transitionSpec = { tween(PAGE_MS, easing = PageEasing) },
        label = "pageCard"
    ) { state -> if (state == EnterExitState.Visible) 0f else 1f }

    val fill = MaterialTheme.colorScheme.surface
    val dark = fill.luminance() < 0.5f
    val rim = Color.White.copy(alpha = if (dark) 0.22f else 0.85f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val k = cardness.value
                val s = 1f - CARD_SHRINK * k
                scaleX = s
                scaleY = s
                shape = RoundedCornerShape(CARD_CORNER * k)
                clip = k > 0.001f
            }
            .drawWithContent {
                val k = cardness.value
                if (k > 0.001f) {
                    // Fade the fill in quickly so the card is solid for most of the move.
                    drawRect(fill.copy(alpha = 0.9f * (k * 2.5f).coerceAtMost(1f)))
                }
                drawContent()
                if (k > 0.001f) {
                    val r = (CARD_CORNER * k).toPx()
                    drawRoundRect(
                        color = rim.copy(alpha = rim.alpha * k),
                        cornerRadius = CornerRadius(r, r),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
    ) {
        content()
    }
}

private const val CARD_SHRINK = 0.08f
private val CARD_CORNER = 36.dp
