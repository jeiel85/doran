package com.jeiel85.doran.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Shop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.BuildConfig
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.update.SelfUpdate
import com.jeiel85.doran.update.StoreUpdateWorker
import com.jeiel85.doran.update.UpdateState
import kotlinx.coroutines.launch

/** Play 빌드: 새 버전이 있는지 Play에 묻고, 업데이트는 Play 스토어에서 합니다 (DECISIONS.md D-16·D-21). */
@Composable
fun UpdateSection(autoUpdate: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by SelfUpdate.state.collectAsStateWithLifecycle()

    ToggleRow("새 버전 알림", "Play 스토어에 새 버전이 나오면 알려 줘요", Icons.Rounded.SystemUpdate, P.c.pine, autoUpdate) { on ->
        Hub.local.update { it.copy(autoUpdate = on) }
        StoreUpdateWorker.schedule(context, on)
    }
    val (title, sub) = when (val st = state) {
        is UpdateState.StoreUpdate -> "새 버전 ${st.version}이 나왔어요" to "지금 ${BuildConfig.VERSION_NAME} · Play 스토어에서 업데이트해요"
        UpdateState.Checking -> "확인하는 중…" to "Play 스토어에 새 버전이 있는지 보고 있어요"
        is UpdateState.Failed -> st.reason to "지금 ${BuildConfig.VERSION_NAME}"
        else -> "최신 버전이에요 · ${BuildConfig.VERSION_NAME}" to "업데이트는 Play 스토어가 해요"
    }
    ListRow(title, sub, Icons.Rounded.InstallMobile, if (state is UpdateState.StoreUpdate) P.c.income else P.c.pine) {
        if (state is UpdateState.StoreUpdate) SmallButton("업데이트") { SelfUpdate.openStore(context) }
        else SmallButton("확인", enabled = state != UpdateState.Checking) { scope.launch { SelfUpdate.check(context, userInitiated = true) } }
    }
    ListRow("Google Play에서 받은 둥지",
        "GitHub APK로 받은 둥지와는 서명이 달라 덮어 설치할 수 없어요 — 바꾸려면 백업 → 지우기 → 설치 → 되살리기",
        Icons.Rounded.Shop, P.c.inkSoft, onClick = { SelfUpdate.openStore(context) })
}
