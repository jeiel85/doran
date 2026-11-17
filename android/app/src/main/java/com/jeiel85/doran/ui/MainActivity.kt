package com.jeiel85.doran.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.R
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.update.Updater
import java.time.LocalDate

/** 화면 경로. 탭 4개(홈·내역·통계·자산) 위에 하위 화면을 쌓습니다. */
sealed interface Route {
    data object Main : Route
    data class Entry(val txnId: String? = null, val kind: Kind = Kind.EXPENSE, val date: LocalDate? = null) : Route
    data object Family : Route
    data object Capture : Route
    data object CaptureLog : Route
    data object Settings : Route
    data object Budget : Route
    data object Recurring : Route
    data class RecurringEdit(val id: String?) : Route
    data object Goals : Route
    data class GoalEdit(val id: String?) : Route
    data class AccountEdit(val id: String?) : Route
    data object Allowance : Route
    data object Categories : Route
    data object Search : Route
    data class Invite(val role: String) : Route
    data object Join : Route
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("홈", Icons.Rounded.Home), LEDGER("내역", Icons.Rounded.CalendarMonth),
    STATS("통계", Icons.Rounded.PieChart), ASSETS("자산", Icons.Rounded.AccountBalanceWallet)
}

class Nav {
    val stack = mutableStateListOf<Route>(Route.Main)
    var tab by mutableStateOf(Tab.HOME)
    val top: Route get() = stack.last()
    fun go(r: Route) { stack.add(r) }
    fun back(): Boolean = if (stack.size > 1) { stack.removeAt(stack.lastIndex); true } else false
    fun home() { while (stack.size > 1) stack.removeAt(stack.lastIndex) }
}

class MainActivity : FragmentActivity() {
    private val nav = Nav()
    private var locked by mutableStateOf(false)
    private var lastStop = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Hub.init(this)
        locked = Hub.settings.value.appLock
        handle(intent)
        setContent {
            val settings by Hub.settings.collectAsStateWithLifecycle()
            val id by Hub.identity.state.collectAsStateWithLifecycle()
            DoranTheme(settings.theme) {
                Box(Modifier.fillMaxSize().background(P.c.bg)) {
                    when {
                        locked -> LockScreen { authenticate() }
                        !id.onboarded -> Onboarding()
                        else -> Root(nav)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val txn = intent?.getStringExtra(Notifier.EXTRA_TXN) ?: return
        nav.home()
        nav.go(Route.Entry(txnId = txn))
        intent.removeExtra(Notifier.EXTRA_TXN)
    }

    override fun onStart() {
        super.onStart()
        // 30초 넘게 밖에 있다 돌아오면 다시 잠급니다.
        if (Hub.settings.value.appLock && lastStop > 0 && System.currentTimeMillis() - lastStop > 30_000) locked = true
        if (locked) authenticate()
    }

    override fun onResume() {
        super.onResume()
        Updater.appVisible = true
        Hub.requestSync()
    }

    override fun onPause() {
        super.onPause()
        Updater.appVisible = false
    }

    override fun onStop() {
        super.onStop()
        lastStop = System.currentTimeMillis()
    }

    /** 폰의 지문·얼굴·화면 잠금 PIN으로 엽니다 (DECISIONS.md D-10). */
    private fun authenticate() {
        val authenticators = if (Build.VERSION.SDK_INT >= 30) Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
        else Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { locked = false }
        })
        val info = BiometricPrompt.PromptInfo.Builder().setTitle("도란 열기").setSubtitle("가계부를 보려면 잠금을 풀어 주세요")
            .setAllowedAuthenticators(authenticators).build()
        runCatching { prompt.authenticate(info) }.onFailure {
            // 폰에 화면 잠금이 아예 없으면 잠글 수 없으므로 잠금을 끕니다(설정 화면에서 안내).
            Hub.local.update { s -> s.copy(appLock = false) }
            locked = false
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.size(140.dp))
        Text("도란", style = MaterialTheme.typography.headlineSmall, color = P.c.ink)
        Spacer(Modifier.height(6.dp))
        Text("우리 집 가계부가 잠겨 있어요", style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft)
        Spacer(Modifier.height(32.dp))
        Row(Modifier.clip(RoundedCornerShape(18.dp)).background(P.c.pine).clickable(onClick = onUnlock).padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = Color.White)
            Spacer(Modifier.size(8.dp))
            Text("잠금 풀기", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Root(nav: Nav) {
    BackHandler(enabled = nav.stack.size > 1 || nav.tab != Tab.HOME) {
        if (!nav.back()) nav.tab = Tab.HOME
    }
    LaunchedEffect(Unit) { Hub.requestSync() }
    AnimatedContent(
        targetState = nav.top,
        transitionSpec = {
            val forward = nav.stack.size > 1 && targetState == nav.top && initialState != targetState && targetState != Route.Main
            if (forward) (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))) togetherWith fadeOut(tween(150))
            else fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(180)))
        },
        label = "route",
    ) { route ->
        when (route) {
            Route.Main -> MainTabs(nav)
            is Route.Entry -> EntryScreen(route, nav)
            Route.Family -> FamilyScreen(nav)
            Route.Capture -> CaptureScreen(nav)
            Route.CaptureLog -> CaptureLogScreen(nav)
            Route.Settings -> SettingsScreen(nav)
            Route.Budget -> BudgetScreen(nav)
            Route.Recurring -> RecurringScreen(nav)
            is Route.RecurringEdit -> RecurringEditScreen(route.id, nav)
            Route.Goals -> GoalsScreen(nav)
            is Route.GoalEdit -> GoalEditScreen(route.id, nav)
            is Route.AccountEdit -> AccountEditScreen(route.id, nav)
            Route.Allowance -> AllowanceScreen(nav)
            Route.Categories -> CategoriesScreen(nav)
            Route.Search -> SearchScreen(nav)
            is Route.Invite -> InviteScreen(route.role, nav)
            Route.Join -> JoinScreen(nav)
        }
    }
}

@Composable
private fun MainTabs(nav: Nav) {
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(nav.tab, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "tab") { tab ->
            when (tab) {
                Tab.HOME -> HomeScreen(nav)
                Tab.LEDGER -> LedgerScreen(nav)
                Tab.STATS -> StatsScreen(nav)
                Tab.ASSETS -> AssetsScreen(nav)
            }
        }
        BottomBar(nav, Modifier.align(Alignment.BottomCenter))
    }
}

/** 떠 있는 하단 막대: 탭 4개 사이 가운데에 + 버튼. */
@Composable
private fun BottomBar(nav: Nav, modifier: Modifier) {
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(66.dp)
                .shadow(18.dp, RoundedCornerShape(24.dp), ambientColor = P.c.shadow, spotColor = Color(0x22000000))
                .clip(RoundedCornerShape(24.dp)).background(P.c.surface).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(Tab.HOME, Tab.LEDGER).forEach { TabItem(it, nav, Modifier.weight(1f)) }
            Spacer(Modifier.weight(1f))
            listOf(Tab.STATS, Tab.ASSETS).forEach { TabItem(it, nav, Modifier.weight(1f)) }
        }
        Box(
            Modifier.align(Alignment.Center).offset(y = (-14).dp).size(60.dp)
                .shadow(14.dp, CircleShape, spotColor = P.c.pine.copy(alpha = 0.5f))
                .clip(CircleShape).background(P.c.pine)
                .clickable { nav.go(Route.Entry()) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Add, "내역 쓰기", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun TabItem(tab: Tab, nav: Nav, modifier: Modifier) {
    val selected = nav.tab == tab
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable { nav.tab = tab }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, tab.label, tint = if (selected) P.c.pine else P.c.inkFaint, modifier = Modifier.size(24.dp))
        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = if (selected) P.c.pine else P.c.inkFaint)
    }
}
