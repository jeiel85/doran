package com.jeiel85.doran.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.core.Hub
import com.jeiel85.doran.logic.Stats
import com.jeiel85.doran.model.AccountType

/** 자산 탭: 순자산, 계좌·카드·저축·대출 묶음, 저축 목표. */
@Composable
fun AssetsScreen(nav: Nav) {
    val s by Hub.ledger.state.collectAsStateWithLifecycle()
    val worth = remember(s.version) { Stats.netWorth(s) }
    val groups = remember(s.version) {
        val visible = s.accounts.values.filter { !it.hidden }.sortedBy { it.order }
        // 잔액을 세지 않는 체크카드는 카드 묶음에(이번 달 쓴 돈), 잔액을 적어 통장처럼 쓰는 체크카드는 입출금 묶음에(D-29).
        listOf(
            "현금·입출금" to visible.filter {
                it.type in setOf(AccountType.CASH, AccountType.BANK, AccountType.POINT) || it.type == AccountType.CHECK && Stats.keepsBalance(it)
            },
            "카드" to visible.filter { it.type == AccountType.CARD || it.type == AccountType.CHECK && !Stats.keepsBalance(it) },
            "저축·투자" to visible.filter { it.type in setOf(AccountType.SAVINGS, AccountType.INVEST) },
            "대출" to visible.filter { it.type == AccountType.LOAN },
        ).filter { it.second.isNotEmpty() }
    }
    val goals = s.goals.values.filter { !it.done }.sortedBy { it.order }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)) {
        item {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("자산", style = MaterialTheme.typography.headlineSmall, color = P.c.ink, modifier = Modifier.weight(1f))
                IconButton(onClick = { nav.go(Route.AccountEdit(null)) }) { Icon(Icons.Rounded.Add, "계좌 추가", tint = P.c.ink) }
            }
        }
        item {
            HeroCard(listOf(Color(0xFF2B3A67), Color(0xFF1E2747)), com.jeiel85.doran.R.drawable.art_cat_bank, "우리집 순자산", Fmt.won(worth.net)) {
                Spacer(Modifier.height(16.dp))
                HeroStats(listOf(
                    Stat("자산", Fmt.won(worth.assets)),
                    Stat("갚을 돈", Fmt.won(-worth.debts), Color(0xFFFFB4A8)),
                ))
            }
        }
        if (groups.isEmpty()) item {
            Spacer(Modifier.height(12.dp))
            EmptyState(com.jeiel85.doran.R.drawable.art_capture, "계좌·카드가 아직 없어요",
                "카드 알림이 오면 자동으로 생기고,\n오른쪽 위 +로 직접 더할 수도 있어요")
        }
        groups.forEach { (title, list) ->
            item(key = title) {
                Spacer(Modifier.height(20.dp))
                SectionTitle(title)
                SoftCard(padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    list.forEach { a ->
                        val bal = Stats.balance(s, a)
                        val owner = s.member(a.ownerId)
                        val checkOnly = a.type == AccountType.CHECK && !Stats.keepsBalance(a)
                        val sub = buildList {
                            if (a.family) add("가족 카드" + (owner?.name?.let { " · 명의 $it" } ?: "")) else owner?.name?.let(::add)
                            add(a.type.label)
                            if (a.type == AccountType.CARD) {
                                val c = Stats.card(s, a)
                                add("이번 달 ${Fmt.compact(c.monthSpent)}")
                                c.dueDate?.let { add("${it.monthValue}/${it.dayOfMonth} 결제 ${Fmt.compact(c.dueAmount)}") }
                            }
                            if (checkOnly) add("통장에서 바로 나가요")
                            if (a.anchorAt != null) add("잔액 알림 ${Fmt.relative(a.anchorAt)}")
                        }.joinToString(" · ")
                        ListRow(a.name, sub, leading = {
                            CatTile(when (a.type) {
                                AccountType.CASH -> "cash"; AccountType.CARD, AccountType.CHECK -> "card"
                                AccountType.SAVINGS -> "savings"; AccountType.INVEST -> "invest"; AccountType.LOAN -> "tax"
                                AccountType.POINT -> "income_etc"; AccountType.BANK -> "bank"
                            }, 42.dp)
                        }, onClick = { nav.go(Route.AccountEdit(a.id)) }) {
                            if (checkOnly) Column(horizontalAlignment = Alignment.End) {
                                Text("이번 달", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                                Text(Fmt.won(Stats.card(s, a).monthSpent), style = MaterialTheme.typography.titleSmall.merge(Money), color = P.c.ink)
                            } else Text(Fmt.won(bal), style = MaterialTheme.typography.titleSmall.merge(Money),
                                color = if (bal < 0) P.c.expense else P.c.ink)
                        }
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            SectionTitle("저축 목표", action = if (goals.isEmpty()) "만들기" else "전체", onAction = { nav.go(Route.Goals) })
        }
        if (goals.isEmpty()) item {
            SoftCard(onClick = { nav.go(Route.GoalEdit(null)) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CatTile("savings", 48.dp, P.c.pineSoft)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("여행·비상금·적금 목표를 세워 보세요", style = MaterialTheme.typography.titleSmall, color = P.c.ink)
                        Text("모은 돈과 남은 기간을 한눈에", style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft)
                    }
                }
            }
        } else item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(goals, key = { it.id }) { g ->
                    val saved = Stats.goalSaved(s, g)
                    SoftCard(Modifier.width(210.dp), onClick = { nav.go(Route.GoalEdit(g.id)) }) {
                        CatTile(g.icon, 40.dp, P.c.pineSoft)
                        Spacer(Modifier.height(10.dp))
                        Text(g.name, style = MaterialTheme.typography.titleSmall, color = P.c.ink, maxLines = 1)
                        Text("${Fmt.won(saved)} / ${Fmt.compact(g.target)}", style = MaterialTheme.typography.bodySmall.merge(Money), color = P.c.inkSoft)
                        Spacer(Modifier.height(8.dp))
                        Progress(if (g.target > 0) saved.toFloat() / g.target else 0f, P.c.pine, height = 8.dp)
                    }
                }
            }
        }
    }
}
