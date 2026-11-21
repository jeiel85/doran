package com.jeiel85.doran.capture

import java.time.LocalDate
import java.time.LocalDateTime

/** 알림·문자 한 건에서 읽어 낸 거래. */
data class Parsed(
    val type: Type,
    val issuer: String,
    /** 카드사인지(true) 은행인지(false). */
    val isCard: Boolean,
    val amount: Long,
    val last4: String? = null,
    val installment: Int = 0,
    val merchant: String? = null,
    val at: LocalDateTime? = null,
    /** 카드사가 알려 준 누적 이용액. */
    val cumulative: Long? = null,
    /** 은행이 알려 준 거래 후 잔액. */
    val balance: Long? = null,
    /** 해외 결제 원래 금액("USD 12.34"). 이때 amount는 원화 확정 전이라 0일 수 있습니다. */
    val foreign: String? = null,
    /** 체크카드 승인처럼 은행 계좌에서 바로 나가는 카드. */
    val checkCard: Boolean = false,
    /** 같은 결제를 따로 알린 간편결제 앱(예: "네이버페이"). 카드 표시에 덧붙입니다(DECISIONS.md D-5). */
    val via: String? = null,
    /** 간편결제 알림의 결제 수단 문구("결제 수단: 카드 간편결제"). 머니·포인트로 냈는지 가리는 데 씁니다(D-5). */
    val payMethod: String? = null,
) {
    enum class Type { APPROVAL, CANCEL, DEPOSIT, WITHDRAWAL }

    /** 내역의 "카드" 표시: "토스뱅크", "현대카드(1234)", 간편결제로 냈으면 "토스뱅크 · 네이버페이". */
    val cardLabel: String get() = SamePayment.label(issuer + (last4?.let { "($it)" } ?: ""), via)

    /** 은행 출금 중 카드값(카드사 이름이 적힌 출금) — 지출이 아니라 이체입니다 (DECISIONS.md D-5). */
    val cardPaymentTo: String? get() =
        if (type == Type.WITHDRAWAL) merchant?.let { m -> Issuers.cards.firstOrNull { c -> c.matches(m) }?.name } else null

    /** 간편결제 머니·포인트로 낸 결제: 카드·계좌에서 돈이 나가지 않아 짝이 되는 카드·은행 알림이 없습니다(D-5). */
    val paidFromBalance: Boolean get() = Parser.paidFromBalance(payMethod)
}

data class Issuer(val name: String, val card: Boolean, val aliases: List<String>) {
    fun matches(text: String): Boolean = aliases.any { text.contains(it, ignoreCase = true) }
}

object Issuers {
    val cards = listOf(
        Issuer("현대카드", true, listOf("현대카드", "HYUNDAICARD", "HYUNDAI CARD")),
        Issuer("신한카드", true, listOf("신한카드", "SHINHANCARD", "SOL페이")),
        Issuer("삼성카드", true, listOf("삼성카드", "SAMSUNGCARD")),
        Issuer("KB국민카드", true, listOf("KB국민카드", "국민카드", "KB카드", "KB Pay", "KB페이")),
        Issuer("롯데카드", true, listOf("롯데카드", "LOTTECARD")),
        Issuer("하나카드", true, listOf("하나카드", "HANACARD", "하나Pay", "하나페이")),
        Issuer("우리카드", true, listOf("우리카드", "WOORICARD", "우리WON카드")),
        Issuer("BC카드", true, listOf("BC카드", "비씨카드", "BC 카드", "페이북")),
        Issuer("NH농협카드", true, listOf("NH농협카드", "농협카드", "NH카드", "NH pay", "NH페이")),
        Issuer("씨티카드", true, listOf("씨티카드", "CITICARD")),
    )
    val banks = listOf(
        Issuer("KB국민은행", false, listOf("KB국민은행", "국민은행", "[KB]", "KB국민", "KB스타")),
        Issuer("신한은행", false, listOf("신한은행", "[신한]", "신한SOL", "SOL뱅크")),
        Issuer("우리은행", false, listOf("우리은행", "[우리]", "WON뱅킹")),
        Issuer("하나은행", false, listOf("하나은행", "[하나]", "하나원큐")),
        Issuer("NH농협", false, listOf("농협은행", "NH농협", "[농협]", "농협", "올원뱅크", "NH스마트뱅킹")),
        Issuer("IBK기업은행", false, listOf("기업은행", "IBK", "i-ONE")),
        Issuer("카카오뱅크", false, listOf("카카오뱅크")),
        Issuer("토스뱅크", false, listOf("토스뱅크")),
        Issuer("케이뱅크", false, listOf("케이뱅크")),
        Issuer("SC제일은행", false, listOf("SC제일", "제일은행")),
        Issuer("새마을금고", false, listOf("새마을금고", "MG새마을")),
        Issuer("우체국", false, listOf("우체국")),
        Issuer("수협", false, listOf("수협")),
        Issuer("신협", false, listOf("신협")),
        Issuer("부산은행", false, listOf("부산은행", "BNK부산")),
        Issuer("iM뱅크", false, listOf("iM뱅크", "대구은행")),
        Issuer("경남은행", false, listOf("경남은행")),
        Issuer("광주은행", false, listOf("광주은행")),
        Issuer("전북은행", false, listOf("전북은행")),
    )
    val all = cards + banks

    /** 사용자가 쓴 표기(예: "현대카드M", "현대 카드")를 해석기의 카드사·은행 이름으로. 모르는 이름은 띄어쓰기·대소문자만 맞춥니다. */
    fun canonical(name: String): String {
        val n = name.trim()
        all.firstOrNull { it.name.equals(n, ignoreCase = true) }?.let { return it.name }
        val squashed = n.replace(" ", "")
        all.firstOrNull { i -> i.aliases.any { a -> squashed.contains(a.replace(" ", ""), ignoreCase = true) } }?.let { return it.name }
        return squashed.lowercase()
    }

    fun same(a: String, b: String) = canonical(a) == canonical(b)

    /**
     * 가맹점 자리에 남은 것이 카드 상품 이름뿐인지("현대 ZERO", "신한 Deep Dream", "현대카드"): 그 카드사의 이름(줄기)으로 시작하고,
     * 그 뒤에서 "카드"를 떼면 한글이 남지 않는 것. "현대백화점"·"롯데마트"처럼 이름 뒤에 한글이 이어지면 실제 가맹점이고,
     * "ABC MART"처럼 영문 이름 가운데에 든 "BC"는 카드사 이름이 아닙니다.
     * 가맹점이 없는 카드 문자에서 상품 이름을 가맹점으로 읽어, 같은 결제를 두 폰이 각각 기록했는데 다른 가게로 보인 일(2026-11-21 주유, D-5).
     */
    fun cardNameOnly(merchant: String?, issuer: String?): Boolean {
        val m = merchant?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val i = all.firstOrNull { it.name == issuer } ?: return false
        val stems = (i.aliases + i.name.removeSuffix("카드").removeSuffix("은행")).filter { it.isNotBlank() }.sortedByDescending { it.length }
        val stem = stems.firstOrNull { s ->
            m.startsWith(s, ignoreCase = true) && !(s.last().isLatinLetter() && m.getOrNull(s.length)?.isLatinLetter() == true)
        } ?: return false
        return m.substring(stem.length).replace("카드", " ").none { it in '가'..'힣' }
    }

    private fun Char.isLatinLetter() = this in 'A'..'Z' || this in 'a'..'z'

    /** 해석기가 아는 카드사·은행 이름인지. 아니면 "앞으로도 자동으로" 규칙으로 정한 간편결제 앱 등입니다(#18). */
    fun listed(name: String?): Boolean = name != null && all.any { it.name == name }

    /** 카드사를 먼저 봅니다: "현대카드 출금"이 담긴 은행 문자는 은행 쪽 이름도 함께 있기 때문입니다. */
    fun find(text: String, preferBank: Boolean = false): Issuer? {
        val cardHit = cards.firstOrNull { it.matches(text) }
        val bankHit = banks.firstOrNull { it.matches(text) }
        return if (preferBank) bankHit ?: cardHit else cardHit ?: bankHit
    }
}

/**
 * 카드사·은행의 승인·취소·입출금 알림을 읽습니다. 순수 함수라 단위 시험으로 형식을 고정합니다
 * (ParserTest). 카드사마다 문구가 조금씩 다르므로 "고정 템플릿"이 아니라 금액·날짜·누적·잔액·할부를
 * 각각 찾아 지우고 남는 것을 가맹점으로 봅니다.
 */
object Parser {
    private val NOISE = Regex("""\[?Web\s?발신]?|\[국외발신]""")
    private val AD = Regex("""\(광고\)|\[광고]|^광고""")
    private val PROMO = Regex("""이벤트|혜택|쿠폰|프로모션|추첨|당첨|캐시백|적립|할인 받|무이자|신청하|가입하""")
    private val TXN_WORD = Regex("""승인|취소|입금|출금|이체|지급""")
    private val WON = Regex("""(?<![\d.])(\d{1,3}(?:,\d{3})+|\d+)\s*원""")
    private val BARE_AMOUNT = Regex("""(?:입금|출금|이체|지급|승인|결제|사용)\s*:?\s*(\d{1,3}(?:,\d{3})+|\d{4,})(?!\s*[/:.\d])""")
    private val CUMULATIVE = Regex("""누적\s*(?:금액|이용액|이용금액|사용액|사용금액)?\s*:?\s*(\d{1,3}(?:,\d{3})*|\d+)\s*원?""")
    private val BALANCE = Regex("""잔액\s*:?\s*(-?\d{1,3}(?:,\d{3})*|-?\d+)\s*원?""")
    private val INSTALLMENT = Regex("""(?:할부\s*\(?\s*(\d{1,2})\s*개월\)?|(\d{1,2})\s*개월(?:\s*할부)?)""")
    private val LUMP = Regex("""일시불""")
    // 뒤에 ".숫자"가 더 오면 금액이 아니라 버전입니다: 사내 메신저의 보안 공지 "PHP 8.2.0 ~ 8.2.35"를 필리핀 페소로 읽었어요(2026-11-20).
    private val FOREIGN = Regex("""(USD|US\$|EUR|JPY|CNY|GBP|HKD|SGD|AUD|CAD|VND|THB|PHP|TWD)\s*([\d,]+(?:\.\d{1,2})?)(?!\.?\d)""")
    private val FULL_DATE = Regex("""(20\d{2})[./-](\d{1,2})[./-](\d{1,2})\s*(\d{1,2}):(\d{2})(?::\d{2})?""")
    private val MD_TIME = Regex("""(?<!\d)(\d{1,2})[/.](\d{1,2})\s*(\d{1,2}):(\d{2})(?::\d{2})?""")
    private val KO_DATE_TIME = Regex("""(\d{1,2})월\s*(\d{1,2})일\s*(\d{1,2}):(\d{2})""")
    private val TIME_ONLY = Regex("""(?<![\d:/])(\d{1,2}):(\d{2})(?![\d:])""")
    private val CARD_NO = Regex("""\(?\s*(?:\d{4}[-*]?\*{2,}[-*]?\*{0,4}[-*]?(\d{3,4})|[*]{2,}(\d{3,4}))\s*\)?|\((\d{4})\)""")
    private val ACCOUNT_NO = Regex("""\d{2,6}[-*]+\*+[-*\d]*\d{2,4}|\d{3,6}\*{2,}\d{2,4}""")
    /**
     * 카드사 알림이 체크카드라고 밝힌 낱말("[신한체크승인]", "체크카드", "[KB국민체크]", "체크 취소"). 가맹점 이름의 "체크인"·"헬스체크"는
     * 아닙니다 — 이것으로 자동 기록이 만든 카드를 체크카드로 정하므로 넓게 잡지 않습니다(DECISIONS.md D-29).
     */
    private val CHECK_CARD = Regex("""체크\s*(?:카드|승인|취소|결제|사용|\(|])""")
    private val HOLDER = Regex("""[가-힣A-Z][*＊][가-힣A-Z]{0,2}\s*님?|[가-힣]{1,2}\*\s*님?""")
    /** 간편결제 알림의 "(결제 수단: 카드 간편결제)" — 가맹점이 아닙니다. */
    private val PAY_METHOD = Regex("""\(\s*결제\s*수단[^)\n]*\)|결제\s*수단\s*:[^\n|·,()]*""")
    /** "CU에서 3,610원 결제"처럼 금액 바로 앞의 '에서'. */
    private val AT_PLACE = Regex("""에서\s*$""")
    private val EMPTY_PARENS = Regex("""\(\s*\)""")
    private val SPACES = Regex("""\s+""")
    private val KEYWORDS = Regex(
        // 은행 거래 방식 낱말("스마트폰출금", "인터넷이체", "타행입금"): 받는 분·메모 칸(적요)이 아니라 이체한 길입니다(2026-11-20 KB 알림).
        // 낱말 하나로는 가게 이름에도 쓰여("스마트폰케이스몰") 입출금 낱말이 바로 붙을 때만 뗍니다. ATM·CD는 현금이라는 뜻이 있어 남깁니다.
        """(?:스마트폰|인터넷|모바일|폰뱅킹|오픈뱅킹|타행|당행|FB)(?=\s*(?:출금|입금|이체|지급|송금))|""" +
        """승인취소|취소승인|해외승인|승인|취소|체크카드|체크|신용|일시불|사용|결제|입금|출금|이체|지급|자동이체|전자금융|님""" +
            """|본인|해외|누적|잔액|국내|금액|이용안내|안내|일반|가맹점""" +
            // 문장형 알림("…결제되었습니다")의 끝말이 가맹점으로 뽑히지 않게
            """|완료|되었습니다|되었어요|됐습니다|됐어요|하였습니다|했습니다|했어요|입니다|합니다|드립니다|되었|됐""")

    /**
     * [hint]: 알림을 올린 앱의 이름(예: "현대카드"). 카드사 앱 푸시는 본문에 카드사 이름이 없을 수 있어서,
     * 본문에서 못 찾으면 앱 이름으로 카드사를 정합니다.
     */
    /**
     * 거래로 읽지는 못했지만 금액이 적힌 알림인지. "최근 받은 알림"에서 골라 보낼 수 있게 남겨 둘 후보를 고릅니다.
     * 광고·이벤트는 빼고, 금액과 함께 결제·카드·계좌 같은 낱말이 있어야 합니다.
     */
    fun looksFinancial(raw: String): Boolean = !isFailure(raw) && !isScheduled(raw) && moneyLike(raw, loose = false)

    /** 돈이 나가지 않은 알림의 종류(DECISIONS.md D-4): 결제·출금 실패, 결제·출금 예정·청구 안내. */
    enum class Unspent { FAILED, SCHEDULED }

    /**
     * 금액이 적힌, 돈이 나가지 않은 알림인지와 그 종류. 기록하지 않되, 왜 빠졌는지 "최근 받은 알림"에 남깁니다.
     * 은행 실패 문자는 "출금불능 50,000"처럼 '원'도 거래 낱말도 금액 앞에 없을 수 있어 쉼표로 묶인 숫자도 금액으로 봅니다.
     * 실패와 예정이 함께 적혔으면("출금 예정이던 30,000원이 출금되지 않았어요") 실패로 봅니다.
     */
    fun unspent(raw: String): Unspent? = when {
        !moneyLike(raw, loose = true) -> null
        isFailure(raw) -> Unspent.FAILED
        isScheduled(raw) -> Unspent.SCHEDULED
        else -> null
    }

    /** 금액이 적힌 결제·출금 실패 알림인지([unspent]가 [Unspent.FAILED]). */
    fun isFailedPayment(raw: String): Boolean = unspent(raw) == Unspent.FAILED

    /**
     * 결제·출금 예정, 청구 안내인지(D-4 "돈이 나가지 않은 알림" 2). 돈은 아직 나가지 않았고, 실제로 나가면 그때 알림이 따로 옵니다.
     * 토스 "납부 예정 / 오늘 ○○에서 30,000원 출금할 예정이에요"처럼 띄어쓰기·어미가 제각각이라 공백을 지운 본문으로 봅니다.
     */
    fun isScheduled(raw: String): Boolean {
        val compact = WHITESPACE.replace(raw, "")
        return SCHEDULED.any { it.containsMatchIn(compact) } || compact.contains("청구") && !compact.contains("승인")
    }

    /**
     * 결제·출금이 실제로 일어나지 않았다는 알림인지(DECISIONS.md D-4 "돈이 나가지 않은 알림").
     * 띄어쓰기가 카드사마다 달라("잔액 부족", "출금이 되지 않았습니다") 공백을 지운 본문으로 봅니다.
     */
    fun isFailure(raw: String): Boolean {
        val compact = WHITESPACE.replace(raw, "")
        if (FAILURE.any { it.containsMatchIn(compact) }) return true
        // 간편결제는 "잔액 부족분을 자동충전해 결제"하기도 해서, 충전 안내가 함께 있으면 '부족'만으로는 실패가 아닙니다.
        return SHORTAGE.containsMatchIn(compact) && !compact.contains("충전")
    }

    private fun moneyLike(raw: String, loose: Boolean): Boolean {
        if (AD.containsMatchIn(raw.trim())) return false
        if (PROMO.containsMatchIn(raw) && !TXN_WORD.containsMatchIn(raw)) return false
        val amount = WON.containsMatchIn(raw) || BARE_AMOUNT.containsMatchIn(raw) || FOREIGN.containsMatchIn(raw) ||
            loose && GROUPED_NUMBER.containsMatchIn(raw)
        return amount && (TXN_WORD.containsMatchIn(raw) || MONEY_WORD.containsMatchIn(raw))
    }
    private val MONEY_WORD = Regex("""결제|사용|카드|계좌|잔액|누적|송금|충전|페이""")
    private val GROUPED_NUMBER = Regex("""(?<![\d.])\d{1,3}(?:,\d{3})+""")
    private val WHITESPACE = Regex("""\s+""")
    private const val MONEY_VERB = "결제|출금|이체|승인|처리|납부|지급|송금|충전|거래"
    private val FAILURE = listOf(
        Regex("""실패|거절"""),
        Regex("""(?:$MONEY_VERB|사용|이용)(?:불가|불능)"""),
        // "미결제"는 '미결제잔액' 같은 정상 안내에도 쓰여 넣지 않습니다.
        Regex("""미승인|미출금|미이체|미처리|미납|연체"""),
        Regex("""한도(?:를|가)?초과"""),
        Regex("""(?:$MONEY_VERB|완료)(?:가|이|를)?(?:되지|하지|할수|될수)(?:않|못|없)"""),
        Regex("""(?:$MONEY_VERB)(?:가|이)?안(?:됐|돼|되|됨)"""),
        Regex("""(?:승인|결제|출금|이체|거래|처리)오류"""),
        Regex("""DECLINED|FAILED""", RegexOption.IGNORE_CASE),
    )
    private val SHORTAGE = Regex("""(?:잔액|잔고|한도|예수금)(?:이|가)?부족""")
    // 나가는 돈의 낱말만 넣습니다. '처리·입금·지급·적립'은 이미 일어난 거래의 덧말("환불은 3일 안에 입금 예정",
    // "캐시백 500원 지급 예정")에도 쓰여, 넣으면 실제 취소·결제 알림을 놓칩니다.
    private const val DUE_VERB = "결제|출금|이체|납부|인출|송금|청구"
    private val SCHEDULED = listOf(
        // "결제예정금액", "출금 예정", "출금할 예정이에요", "결제될 예정", "납부 예정일", "자동이체 예정"
        Regex("""(?:$DUE_VERB)(?:이|가)?(?:할|될|하실|되실)?예정"""),
        // "이번 달 납부할 금액", "결제하실 금액": 아직 내지 않은 돈
        Regex("""(?:$DUE_VERB)(?:할|될|하실|되실)금액"""),
    )

    /**
     * [custom]: "앞으로도 자동으로 보내기"로 사용자가 정한 발신처의 카드사·은행(목록에 없는 곳도 됨).
     * [ignoreFailure]: 돈이 나가지 않은 알림(실패·예정)으로 빠진 것을 사용자가 "그래도 보내기"로 고를 때 초기값을 채우려고만 씁니다(#13).
     */
    fun parse(raw: String, now: LocalDateTime = LocalDateTime.now(), hint: String? = null, custom: Issuer? = null,
              ignoreFailure: Boolean = false): Parsed? {
        // 광고·이벤트 알림은 금액이 적혀 있어도 거래가 아닙니다.
        if (AD.containsMatchIn(raw.trim())) return null
        if (PROMO.containsMatchIn(raw) && !TXN_WORD.containsMatchIn(raw)) return null
        val text = NOISE.replace(raw, " ").replace('\u00a0', ' ')
        // 돈이 나가지 않은 알림: 결제·출금 실패(거절·잔액 부족·미납 등)와 결제·출금 예정·청구 안내 (DECISIONS.md D-4)
        if (!ignoreFailure && (isFailure(text) || isScheduled(text))) return null

        val cancel = Regex("""취소""").containsMatchIn(text) && (text.contains("승인") || text.contains("카드") || text.contains("결제"))
        val deposit = Regex("""입금""").containsMatchIn(text) && !text.contains("입금예정")
        val withdrawal = Regex("""출금|자동이체|이체출금|지급""").containsMatchIn(text)
        val approval = Regex("""승인|결제|사용""").containsMatchIn(text)
        val check = text.contains("체크")

        val preferBank = (deposit || withdrawal) && !approval
        val issuer = Issuers.find(text, preferBank) ?: hint?.let { Issuers.find(it, preferBank) } ?: custom ?: return null
        val type = when {
            cancel -> Parsed.Type.CANCEL
            issuer.card && (approval || !deposit && !withdrawal) -> Parsed.Type.APPROVAL
            !issuer.card && approval && check -> Parsed.Type.APPROVAL
            deposit && !withdrawal -> Parsed.Type.DEPOSIT
            withdrawal -> Parsed.Type.WITHDRAWAL
            deposit -> Parsed.Type.DEPOSIT
            approval -> Parsed.Type.APPROVAL
            else -> return null
        }

        // 지울 구간을 모아 두었다가 남은 글자에서 가맹점을 찾습니다.
        val consumed = mutableListOf<IntRange>()
        fun take(m: MatchResult?): MatchResult? = m?.also { consumed += it.range }

        val cumulative = take(CUMULATIVE.find(text))?.groupValues?.get(1)?.let(::toLong)
        val balance = take(BALANCE.find(text))?.groupValues?.get(1)?.let(::toLong)
        val foreignMatch = FOREIGN.find(text)
        val foreign = take(foreignMatch)?.let { "${it.groupValues[1].replace("US$", "USD")} ${it.groupValues[2]}" }

        val wonMatch = WON.findAll(text).firstOrNull { m -> consumed.none { r -> m.range.first in r } }
        val amountMatch = wonMatch ?: BARE_AMOUNT.findAll(text).firstOrNull { m -> consumed.none { r -> m.range.first in r } }
        // '원' 없는 금액은 숫자만 지웁니다. 앞의 "출금"까지 지우면 "인터넷출금"의 "인터넷"이 거래 방식 낱말로 안 보여 가맹점에 남습니다.
        val amount = amountMatch?.let { m -> if (m === wonMatch) take(m) else m.groups[1]?.let { g -> consumed += g.range }; toLong(m.groupValues[1]) } ?: 0L
        if (amount <= 0 && foreign == null) return null
        // 은행 알림은 제목("출금 4,044,525원")과 본문("… 스마트폰출금 4,044,525")에 금액을 두 번 적습니다. 되풀이된 금액이 남으면
        // 쉼표로 갈려 "급여 스마트폰 4" 같은 가맹점이 됩니다.
        if (amount > 0) echoes(amount).forEach { r -> r.findAll(text).forEach { m -> if (consumed.none { m.range.first in it }) take(m) } }
        // "CU에서 3,610원": '에서' 바로 앞이 가맹점입니다. 알림 제목(앱 이름)이 더 길어도 그쪽을 고르지 않게 위치를 기억합니다.
        val place = amountMatch?.let { m ->
            val from = maxOf(0, m.range.first - 6)
            AT_PLACE.find(text.substring(from, m.range.first))?.let { (from + it.range.first).also { p -> consumed += p until m.range.first } }
        }
        val payMethod = PAY_METHOD.findAll(text).map { take(it)!!.value.trim('(', ')', ' ') }.toList().firstOrNull()

        val at = parseDate(text, now, consumed)
        val installment = when {
            LUMP.containsMatchIn(text) -> 0
            else -> take(INSTALLMENT.find(text))?.let { m ->
                (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull()?.takeIf { it in 2..36 } ?: 0
            } ?: 0
        }
        val cardNo = take(CARD_NO.find(text))
        val last4 = cardNo?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        take(ACCOUNT_NO.find(text))
        Regex(Regex.escape(issuer.name)).find(text)?.let { take(it) }
        issuer.aliases.forEach { a -> Regex(Regex.escape(a), RegexOption.IGNORE_CASE).findAll(text).forEach { take(it) } }
        HOLDER.findAll(text).forEach { take(it) }

        val merchant = merchantFrom(text, consumed, place, issuer.name)
        return Parsed(
            type = type, issuer = issuer.name, isCard = issuer.card, amount = amount, last4 = last4,
            installment = installment, merchant = merchant, at = at, cumulative = cumulative, balance = balance,
            foreign = foreign, checkCard = CHECK_CARD.containsMatchIn(text) || !issuer.card && type == Parsed.Type.APPROVAL, payMethod = payMethod,
        )
    }

    private fun toLong(s: String): Long = s.replace(",", "").toLongOrNull() ?: 0

    /**
     * 본문에 되풀이된 같은 금액("4,044,525", "4044525", 뒤의 '원' 포함). 더 긴 숫자의 일부나, "1000원샵"처럼 '원'에 글자가 바로 붙은
     * 가게 이름은 아닙니다.
     */
    private fun echoes(amount: Long): List<Regex> =
        listOf(String.format(java.util.Locale.US, "%,d", amount), amount.toString()).distinct()
            .map { Regex("""(?<![\d,.])${Regex.escape(it)}(?![\d,.]|\s*원[가-힣A-Za-z])\s*원?""") }

    private fun parseDate(text: String, now: LocalDateTime, consumed: MutableList<IntRange>): LocalDateTime? {
        FULL_DATE.find(text)?.let { m ->
            consumed += m.range
            val (y, mo, d, h, mi) = m.destructured
            return runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt()) }.getOrNull()
        }
        val md = MD_TIME.find(text) ?: KO_DATE_TIME.find(text)
        if (md != null) {
            consumed += md.range
            val (mo, d, h, mi) = md.destructured
            return runCatching {
                var date = LocalDate.of(now.year, mo.toInt(), d.toInt())
                // 연말에 받은 1월 1일 이전 알림처럼, 미래 날짜면 작년입니다.
                if (date.isAfter(now.toLocalDate().plusDays(1))) date = date.minusYears(1)
                date.atTime(h.toInt(), mi.toInt())
            }.getOrNull()
        }
        TIME_ONLY.find(text)?.let { m ->
            consumed += m.range
            val (h, mi) = m.destructured
            return runCatching {
                val t = now.toLocalDate().atTime(h.toInt(), mi.toInt())
                if (t.isAfter(now.plusMinutes(5))) t.minusDays(1) else t
            }.getOrNull()
        }
        return null
    }

    /**
     * [place]: "…에서 N원"의 '에서' 위치. 있으면 그 바로 앞 덩어리를 가맹점으로 씁니다.
     * 카드 상품 이름뿐인 덩어리("현대 ZERO")는 가맹점이 아닙니다([Issuers.cardNameOnly]) — 가맹점이 없는 문자면 비워 둡니다.
     */
    private fun merchantFrom(text: String, consumed: List<IntRange>, place: Int? = null, issuer: String? = null): String? {
        val sb = StringBuilder(text)
        for (r in consumed.sortedByDescending { it.first }) {
            val end = minOf(r.last + 1, sb.length)
            if (r.first < end) for (i in r.first until end) sb.setCharAt(i, '\n')
        }
        fun usable(s: String) = s.length >= 2 && !s.all { c -> c.isDigit() || c == '*' || c == ' ' } && !Issuers.cardNameOnly(s, issuer)
        place?.let { p -> tidy(sb.substring(0, p).split(*SEPARATORS).last()).takeIf(::usable)?.let { return it.take(40) } }
        // 괄호로는 나누지 않습니다: "씨유(CU)가산하이엔드6차점"이 "가산하이엔드6차점"만 남아 브랜드를 잃기 때문입니다.
        val chunks = sb.toString().split(*SEPARATORS).map(::tidy).filter(::usable)
        return chunks.maxByOrNull { scoreMerchant(it) }?.take(40)
    }
    private val SEPARATORS = charArrayOf('\n', '|', '/', '·', ',', '[', ']')

    /** 낱말을 지운 뒤 남은 빈 괄호·짝 없는 괄호("(할부 3개월)" 앞의 "(")와 통째로 싼 괄호를 정리합니다. */
    private fun tidy(chunk: String): String {
        var s = EMPTY_PARENS.replace(KEYWORDS.replace(chunk, " "), " ")
        if (s.count { it == '(' } != s.count { it == ')' }) s = s.replace('(', ' ').replace(')', ' ')
        s = SPACES.replace(s, " ").trim(' ', ':', '-', '.', '>')
        if (s.length > 2 && s.first() == '(' && s.indexOf(')') == s.lastIndex) s = s.substring(1, s.lastIndex).trim()
        return s
    }

    /**
     * 카드사·은행이 아닌 간편결제 앱(네이버페이 등)의 결제·취소 안내를 읽습니다: "CU에서 3,610원 결제되었습니다. (결제 수단: 카드 간편결제)".
     * 돈은 카드·계좌에서 나가므로 이것만으로 기록하지 않고, 같은 결제의 카드·은행 알림과 묶는 데 씁니다(DECISIONS.md D-5).
     * [app]: 알림을 올린 앱 이름. 그 이름이 가맹점으로 읽히지 않게 지웁니다.
     */
    fun payNotice(raw: String, now: LocalDateTime, app: String?): Parsed? {
        if (!looksFinancial(raw)) return null
        val name = app?.trim()?.takeIf { it.isNotEmpty() } ?: SamePayment.UNKNOWN_APP
        return parse(raw, now, custom = Issuer(name, true, listOf(name)))
            ?.takeIf { (it.type == Parsed.Type.APPROVAL || it.type == Parsed.Type.CANCEL) && it.amount > 0 }
    }

    /**
     * 실패 알림을 "그래도 보내기"로 고를 때 가맹점 초기값에서 실패 문구를 뗍니다("올리브영 이 부족해요" → "올리브영", #13).
     */
    fun withoutFailureWords(merchant: String?): String? =
        merchant?.let { FAILURE_TAIL.replace(it, "") }?.trim()?.takeIf { it.length >= 2 }
    private val FAILURE_TAIL = Regex("""\s*(?:이|가)?\s*(?:부족|실패|거절|불가|불능|초과|안\s*됐|되지\s*않|하지\s*못)\S*.*$""")

    /** 알림 원문의 결제 수단 문구("결제 수단: 네이버페이 머니"). 없으면 null. */
    fun payMethodIn(raw: String): String? = PAY_METHOD.find(raw)?.value?.trim('(', ')', ' ')

    /** 결제 수단이 간편결제 머니·포인트뿐인지(카드·계좌 언급 없음). */
    fun paidFromBalance(method: String?): Boolean =
        method != null && BALANCE_PAY.containsMatchIn(method) && !CARD_OR_ACCOUNT.containsMatchIn(method)
    private val BALANCE_PAY = Regex("""머니|포인트|충전금|캐시""")
    private val CARD_OR_ACCOUNT = Regex("""카드|계좌|통장""")

    /** 한글·영문이 많은 덩어리일수록 가맹점일 가능성이 높습니다. */
    private fun scoreMerchant(s: String): Int = s.count { it.isLetter() } * 2 - s.count { it.isDigit() }
}
