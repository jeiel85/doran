package com.jeiel85.doran.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jeiel85.doran.R
import com.jeiel85.doran.core.Fmt
import com.jeiel85.doran.model.Kind
import com.jeiel85.doran.model.Member

/** design/art에서 만든 그림 (design/make_art.py). */
object Art {
    private val cats: Map<String, Int> = mapOf(
        "food" to R.drawable.art_cat_food, "cafe" to R.drawable.art_cat_cafe, "drinks" to R.drawable.art_cat_drinks,
        "household" to R.drawable.art_cat_household, "shopping" to R.drawable.art_cat_shopping, "beauty" to R.drawable.art_cat_beauty,
        "transit" to R.drawable.art_cat_transit, "car" to R.drawable.art_cat_car, "housing" to R.drawable.art_cat_housing,
        "telecom" to R.drawable.art_cat_telecom, "medical" to R.drawable.art_cat_medical, "education" to R.drawable.art_cat_education,
        "childcare" to R.drawable.art_cat_childcare, "pets" to R.drawable.art_cat_pets, "leisure" to R.drawable.art_cat_leisure,
        "travel" to R.drawable.art_cat_travel, "gift" to R.drawable.art_cat_gift, "insurance" to R.drawable.art_cat_insurance,
        "tax" to R.drawable.art_cat_tax, "subscription" to R.drawable.art_cat_subscription, "allowance" to R.drawable.art_cat_allowance,
        "etc" to R.drawable.art_cat_etc, "salary" to R.drawable.art_cat_salary, "side" to R.drawable.art_cat_side,
        "interest" to R.drawable.art_cat_interest, "income_etc" to R.drawable.art_cat_income_etc, "transfer" to R.drawable.art_cat_transfer,
        "savings" to R.drawable.art_cat_savings, "cash" to R.drawable.art_cat_cash, "bank" to R.drawable.art_cat_bank,
        "card" to R.drawable.art_cat_card, "invest" to R.drawable.art_cat_invest,
    )
    val names: List<String> get() = cats.keys.toList()
    @DrawableRes fun cat(name: String?): Int = cats[name] ?: R.drawable.art_cat_etc
}

@Composable
fun ArtImage(name: String?, size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(Art.cat(name)), contentDescription = null, modifier = modifier.size(size), contentScale = ContentScale.Fit)
}

/** 카테고리 그림을 부드러운 바탕 타일에 얹습니다. */
@Composable
fun CatTile(icon: String?, size: Dp = 44.dp, tint: Color = P.c.surface2) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint), contentAlignment = Alignment.Center) {
        ArtImage(icon, size * 0.78f)
    }
}

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(20.dp),
    color: Color = P.c.surface,
    radius: Dp = 26.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .shadow(if (P.c.dark) 0.dp else 10.dp, shape, ambientColor = P.c.shadow, spotColor = P.c.shadow)
            .clip(shape)
            .background(color)
            .then(if (P.c.dark) Modifier.border(1.dp, P.c.line, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = P.c.ink, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(action, style = MaterialTheme.typography.labelMedium, color = P.c.inkSoft)
                Icon(Icons.Rounded.ChevronRight, null, tint = P.c.inkFaint, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun MemberAvatar(member: Member?, size: Dp = 28.dp, ring: Boolean = false) {
    val color = memberColor(member?.color ?: 5)
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.18f))
            .then(if (ring) Modifier.border(2.dp, color, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(member?.name?.take(1) ?: "?", color = color, fontWeight = FontWeight.ExtraBold,
            fontSize = (size.value * 0.42f).sp)
    }
}

fun kindColor(kind: Kind, p: Palette) = when (kind) { Kind.EXPENSE -> p.expense; Kind.INCOME -> p.income; Kind.TRANSFER -> p.transfer }

@Composable
fun Amount(amount: Long, kind: Kind?, style: TextStyle = MaterialTheme.typography.titleMedium, color: Color? = null, signed: Boolean = true) {
    val c = color ?: when (kind) { Kind.INCOME -> P.c.income; Kind.EXPENSE -> P.c.ink; Kind.TRANSFER -> P.c.transfer; null -> P.c.ink }
    val text = when {
        !signed || kind == null || kind == Kind.TRANSFER -> Fmt.won(amount)
        kind == Kind.INCOME -> "+" + Fmt.won(amount)
        else -> "-" + Fmt.won(amount)
    }
    Text(text, style = style.merge(Money), color = c, maxLines = 1)
}

/**
 * 한 줄에 다 들어가도록 글자 크기를 줄이는 글. 금액이 커져도 옆 칸이나 그림과 겹치지 않게 합니다.
 * [minSize]까지 줄여도 넘치면 말줄임표로 끝냅니다. 크기는 놓인 너비로 미리 재서 정하므로 깜빡이지 않습니다.
 * 안에서 너비를 재므로(SubcomposeLayout) 고유 크기(IntrinsicSize)를 묻는 부모 안에 두지 않습니다.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minSize: TextUnit = 11.sp,
    textAlign: TextAlign? = null,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxW = constraints.maxWidth
        val fitted = remember(text, style, maxW, minSize) {
            val base = style.fontSize
            if (text.isEmpty() || maxW == Constraints.Infinity || !base.isSp || !minSize.isSp) style
            else {
                fun at(size: Float) = style.copy(
                    fontSize = size.sp,
                    lineHeight = if (style.lineHeight.isSp) (style.lineHeight.value * size / base.value).sp else style.lineHeight,
                )
                val size = Fit.size(base.value, minSize.value, maxW) { sz -> measurer.measure(text, at(sz), maxLines = 1, softWrap = false).size.width }
                if (size == base.value) style else at(size)
            }
        }
        Text(text, style = fitted, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = textAlign,
            modifier = if (textAlign != null) Modifier.fillMaxWidth() else Modifier)
    }
}

/** [FitText]의 크기 고르기. 너비 재기([width], 그 크기로 잰 px)를 받아 기기 없이 시험합니다. */
object Fit {
    /** [maxWidth] 안에 한 줄로 들어가는 가장 큰 글자 크기(sp). [min]보다 작게는 줄이지 않습니다. */
    fun size(base: Float, min: Float, maxWidth: Int, width: (Float) -> Int): Float {
        if (maxWidth <= 0 || base <= min) return base
        val full = width(base)
        if (full <= maxWidth) return base
        // 너비는 글자 크기에 거의 비례하므로 한 번에 맞추고, 반올림으로 조금 넘치면 0.5sp씩 더 줄입니다.
        var size = (base * maxWidth / full).coerceIn(min, base)
        while (size > min && width(size) > maxWidth) size = (size - 0.5f).coerceAtLeast(min)
        return size
    }
}

/** 숫자 칸 하나: 위에 이름, 아래 값. [StatStrip]에 나란히 놓습니다. */
data class Stat(val label: String, val value: String, val color: Color? = null)

/**
 * 금액 여러 개를 한 줄에 나눠 놓는 띠. 칸 너비를 글 길이에 맞춰 나누고(긴 금액 칸이 넓게, "10일" 같은 칸은 좁게)
 * 칸 사이에 가는 세로줄을 그어, 금액이 길어져도 붙어 보이거나 겹치지 않게 합니다. 다 못 들어가면 값이 [FitText]로 줄어듭니다.
 */
@Composable
fun StatStrip(
    stats: List<Stat>,
    modifier: Modifier = Modifier,
    background: Color = P.c.surface2,
    labelColor: Color = P.c.inkSoft,
    valueColor: Color = P.c.ink,
    divider: Color = P.c.line,
    valueStyle: TextStyle = MaterialTheme.typography.titleSmall,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall
    val numberStyle = valueStyle.merge(Money)
    val pad = with(LocalDensity.current) { 24.dp.toPx() }
    val weights = remember(stats, labelStyle, numberStyle, pad) {
        stats.map { st ->
            maxOf(measurer.measure(st.label, labelStyle, maxLines = 1, softWrap = false).size.width,
                measurer.measure(st.value, numberStyle, maxLines = 1, softWrap = false).size.width) + pad
        }
    }
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(background).padding(vertical = 12.dp)) {
        stats.forEachIndexed { i, st ->
            Column(
                Modifier.weight(weights[i].coerceAtLeast(1f))
                    .then(if (i > 0) Modifier.drawBehind {
                        val inset = 2.dp.toPx()
                        drawLine(divider, Offset(0f, inset), Offset(0f, size.height - inset), strokeWidth = 1.dp.toPx())
                    } else Modifier)
                    .padding(horizontal = 12.dp),
            ) {
                // 라벨도 잘리지 않고 줄어듭니다("11/30까지"가 "11/30…"이 되면 무슨 날인지 모름 — 좁은 폰에서 확인).
                FitText(st.label, labelStyle, labelColor, minSize = 9.5.sp)
                Spacer(Modifier.height(2.dp))
                FitText(st.value, valueStyle.merge(Money), st.color ?: valueColor)
            }
        }
    }
}

/**
 * 화면 맨 위 큰 카드: 이름과 큰 금액, 오른쪽에 그림. 그림은 금액 옆 자리를 따로 차지해 금액과 겹치지 않고,
 * 금액이 길면 글자가 줄어듭니다. 아래쪽([content])에 진행 막대·[StatStrip] 등을 둡니다.
 */
@Composable
fun HeroCard(
    colors: List<Color>,
    @DrawableRes art: Int,
    label: String,
    amount: String,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(colors))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(22.dp),
    ) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                FitText(amount, MaterialTheme.typography.displaySmall.merge(Money), Color.White, minSize = 22.sp)
            }
            Spacer(Modifier.width(10.dp))
            Image(painterResource(art), null, Modifier.size(64.dp), contentScale = ContentScale.Fit, alpha = 0.95f)
        }
        content()
    }
}

/** 큰 카드 안의 숫자 띠: 반투명 흰 바탕. */
@Composable
fun HeroStats(stats: List<Stat>) {
    StatStrip(stats, background = Color.White.copy(alpha = 0.12f), labelColor = Color.White.copy(alpha = 0.72f),
        valueColor = Color.White, divider = Color.White.copy(alpha = 0.22f))
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, leading: (@Composable () -> Unit)? = null, color: Color = P.c.pine) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) color else P.c.surface)
            .border(1.dp, if (selected) color else P.c.line, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leading != null) {
            if (selected) Box(Modifier.clip(CircleShape).background(Color.White).padding(1.dp)) { leading() } else leading()
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White else P.c.inkSoft, maxLines = 1)
    }
}

@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** 둥근 끝 진행 막대. 1을 넘으면 넘친 부분을 코랄로. */
@Composable
fun Progress(ratio: Float, color: Color = P.c.pine, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val anim by animateFloatAsState(ratio.coerceIn(0f, 1.5f), tween(700), label = "progress")
    val track = P.c.line
    val over = P.c.expense
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(track, cornerRadius = r)
        val w = size.width * anim.coerceAtMost(1f)
        if (w > 0) drawRoundRect(if (anim > 1f) over else color, size = Size(w.coerceAtLeast(size.height), size.height), cornerRadius = r)
    }
}

/** 도넛 차트. 조각 사이에 작은 틈을 둬 카드처럼 보이게 합니다. */
@Composable
fun Donut(values: List<Pair<Color, Long>>, modifier: Modifier = Modifier, thickness: Dp = 22.dp, center: @Composable BoxScope.() -> Unit = {}) {
    val total = values.sumOf { it.second }.coerceAtLeast(1)
    val anim by animateFloatAsState(1f, tween(900), label = "donut")
    val empty = P.c.line
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val d = size.minDimension - stroke
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            if (values.isEmpty()) {
                drawArc(empty, 0f, 360f, false, tl, Size(d, d), style = Stroke(stroke))
                return@Canvas
            }
            var start = -90f
            val gap = if (values.size > 1) 2.2f else 0f
            for ((c, v) in values) {
                val sweep = 360f * v / total * anim
                if (sweep > gap) drawArc(c, start + gap / 2, sweep - gap, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        center()
    }
}

/** 막대 그래프(월별 추이). 마지막 막대를 강조합니다. */
@Composable
fun Bars(values: List<Long>, labels: List<String>, modifier: Modifier = Modifier, color: Color = P.c.pine, highlightLast: Boolean = true) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val anim by animateFloatAsState(1f, tween(800), label = "bars")
    val dim = color.copy(alpha = 0.28f)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val bw = (slot * 0.46f).coerceAtMost(28.dp.toPx())
            values.forEachIndexed { i, v ->
                val h = (size.height * v / max * anim).coerceAtLeast(if (v > 0) 4.dp.toPx() else 2.dp.toPx())
                val x = slot * i + (slot - bw) / 2
                drawRoundRect(if (highlightLast && i == n - 1) color else dim, Offset(x, size.height - h), Size(bw, h), CornerRadius(bw / 3, bw / 3))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = P.c.inkFaint, maxLines = 1) }
        }
    }
}

@Composable
fun ListRow(
    title: String,
    sub: String? = null,
    icon: ImageVector? = null,
    tint: Color = P.c.pine,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            leading != null -> leading()
            icon != null -> Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
        if (leading != null || icon != null) Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = P.c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = P.c.inkSoft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        else if (onClick != null) Icon(Icons.Rounded.ChevronRight, null, tint = P.c.inkFaint)
    }
}

@Composable
fun ToggleRow(title: String, sub: String?, icon: ImageVector, tint: Color = P.c.pine, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListRow(title, sub, icon, tint, onClick = if (enabled) ({ onChange(!checked) }) else null) {
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedTrackColor = P.c.pine, checkedThumbColor = Color.White, uncheckedTrackColor = P.c.line, uncheckedBorderColor = P.c.line,
            uncheckedThumbColor = P.c.surface))
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = P.c.pine, onClick: () -> Unit) {
    Box(
        modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp))
            .background(if (enabled) color else P.c.line).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Color.White else P.c.inkFaint)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = P.c.inkSoft, onClick: () -> Unit) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(16.dp)).background(P.c.surface2).clickable(onClick = onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
fun SmallButton(text: String, enabled: Boolean = true, color: Color = P.c.pine, onClick: () -> Unit) {
    // 보이는 크기는 작게 두되, 누르는 자리는 안드로이드 권장 최소 48dp를 채웁니다(전에는 36dp쯤이라 '이날 쓰기'가 잘 안 눌렸어요).
    Box(
        Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(12.dp)).background(if (enabled) color.copy(alpha = 0.12f) else P.c.line)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (enabled) color else P.c.inkFaint)
    }
}

@Composable
fun EmptyState(@DrawableRes art: Int, title: String, sub: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(art), null, Modifier.height(150.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = P.c.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = P.c.inkSoft, textAlign = TextAlign.Center)
    }
}

/** 폰 하단 내비게이션 바(3버튼·제스처 막대) 높이에 [extra]를 더한 값. 스크롤 목록의 아래 여백에 씁니다. */
@Composable
fun navBarBottom(extra: Dp): Dp = extra + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/** 하위 화면 틀: 뒤로 가기 + 제목 + 스크롤 목록. */
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    bottom: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(P.c.bg)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", tint = P.c.ink) }
            Text(title, style = MaterialTheme.typography.titleLarge, color = P.c.ink, modifier = Modifier.weight(1f))
            actions()
        }
        // 아래 버튼 칸이 없으면 목록 끝이 폰의 뒤로·홈·최근 앱 버튼 뒤로 숨지 않게 그 높이만큼 더 띄웁니다.
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp,
            bottom = if (bottom == null) navBarBottom(32.dp) else 32.dp), content = content)
        if (bottom != null) Box(Modifier.fillMaxWidth().background(P.c.bg).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) { bottom() }
    }
}

/** 설정 화면 등에서 권한을 다시 확인하려고 화면에 돌아올 때마다 바뀌는 값. */
@Composable
fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return tick
}

