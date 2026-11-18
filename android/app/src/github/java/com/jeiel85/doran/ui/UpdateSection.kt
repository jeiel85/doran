package com.jeiel85.doran.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.BuildConfig
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.update.UpdateState
import com.jeiel85.doran.update.UpdateWorker
import com.jeiel85.doran.update.Updater
import kotlinx.coroutines.launch

/** GitHub 빌드: GitHub Releases에서 스스로 업데이트 (DECISIONS.md D-8). 포너디 UpdateSection과 같은 흐름. */
@Composable
fun UpdateSection(autoUpdate: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val canInstall = remember(tick) { context.packageManager.canRequestPackageInstalls() }
    val checkedAt = remember(tick, state) { Updater.lastCheckedAt(context) }

    if (!Updater.supported) {
        ListRow("이 빌드는 직접 업데이트해요", "디버그 빌드는 자동 업데이트를 하지 않아요 · v${BuildConfig.VERSION_NAME}", Icons.Rounded.SystemUpdate, P.c.inkSoft)
        return
    }
    ToggleRow("자동 업데이트", "하루 두 번 새 버전을 확인하고 설치해요", Icons.Rounded.SystemUpdate, P.c.pine, autoUpdate) { on ->
        Hub.local.update { it.copy(autoUpdate = on) }
        UpdateWorker.schedule(context, on)
    }
    val (title, sub) = when (val st = state) {
        UpdateState.Idle -> "현재 버전 ${BuildConfig.VERSION_NAME}" to "마지막 확인: ${Fmt.relative(checkedAt)}"
        UpdateState.Checking -> "확인하는 중…" to "GitHub에서 최신 릴리스를 보고 있어요"
        UpdateState.UpToDate -> "최신 버전이에요" to "${BuildConfig.VERSION_NAME} · 마지막 확인 ${Fmt.relative(checkedAt)}"
        is UpdateState.Available -> "새 버전 ${st.release.version}이 있어요" to "지금 ${BuildConfig.VERSION_NAME}"
        is UpdateState.Downloading -> "내려받는 중 ${st.percent}%" to "새 버전 ${st.release.version}"
        is UpdateState.Installing -> "설치하는 중…" to "잠시 뒤 앱이 다시 시작돼요"
        is UpdateState.NeedsConfirm -> "설치를 허락해 주세요" to "안드로이드가 한 번 확인을 요청해요"
        is UpdateState.Failed -> st.reason to "현재 ${BuildConfig.VERSION_NAME} · 다시 시도해 주세요"
        is UpdateState.StoreUpdate -> "새 버전 ${st.version}" to "Play 스토어에서 받아 주세요"
    }
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
    ListRow(title, sub, Icons.Rounded.InstallMobile, if (state is UpdateState.Failed) P.c.danger else P.c.pine) {
        when (val st = state) {
            is UpdateState.Available -> SmallButton("설치") { scope.launch { Updater.run(context, userInitiated = true) } }
            is UpdateState.NeedsConfirm -> SmallButton("설치") { runCatching { context.startActivity(st.confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            else -> SmallButton("확인", enabled = !busy) { scope.launch { Updater.run(context, userInitiated = true) } }
        }
    }
    if (!canInstall) {
        ListRow("업데이트 설치 허용", "처음 한 번, 도란이 새 버전을 설치할 수 있게 해 주세요", Icons.Rounded.Security, P.c.apricot, onClick = {
            context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        }) { Text("설정", color = P.c.apricot, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
    }
    ListRow("GitHub에서 받은 도란", "Google Play 버전과는 서명이 달라 덮어 설치할 수 없어요 — 바꾸려면 백업 → 지우기 → Play에서 설치 → 되살리기",
        Icons.Rounded.Security, P.c.inkSoft)
}
