package com.jeiel85.doran.capture

/**
 * 같은 곳이 **따로** 올린 두 알림을 두 거래로 셉니다(DECISIONS.md D-5, 2026-11-23).
 *
 * 같은 은행·금액·3분 안의 알림을 한 건으로 합치는 규칙은 앱 푸시와 문자처럼 **다른 경로**가 같은 결제를 함께 알리는 경우를 위한 것입니다.
 * 그런데 같은 날 같은 금액이 두 번 나가는 일이 있습니다: 토스뱅크에서 같은 금액의 자동이체 두 건이 함께 빠지자 알림도 두 개 왔지만
 * 둘째를 중복으로 보고 한 건만 남겼습니다. 같은 앱이 알림을 두 개 따로 올렸으면(또는 같은 번호가 문자를 두 통 보냈으면) 거래도 둘입니다.
 *
 * 알림 하나를 가리키는 표시([push]·[sms]):
 *  - 앱 알림은 알림 키(앱·알림 id·태그). 앱이 같은 알림을 고쳐 다시 올리거나 시스템이 알림을 묶으며 다시 알려 줘도 키가 같아 한 건입니다.
 *    다른 거래에 같은 키를 다시 쓰는 앱(앞 알림이 바뀌어 알림창에 하나만 보임)은 가릴 수 없어 지금처럼 한 건으로 합칩니다.
 *  - 문자는 받은 시각(초)과 본문. 문자 하나는 한 번만 받습니다(문자 앱이 다시 올린 알림은 [com.jeiel85.doran.data.Db.markSeenSms]가 거름).
 *    문자 앱 알림에서 문자를 나눠 읽지 못하면 그 알림의 키를 씁니다(같은 대화방이면 지금처럼 한 건).
 */
object Notices {
    fun push(key: String): String = "k:$key"
    fun sms(at: Long, body: String): String = "m:${at / 1000}|${body.hashCode()}"

    /** 이미 기록된 거래를 알린 알림 하나: 경로(source 코드 + 앱 이름이나 보낸 번호)와 표시(0.3.8 전 기록은 null). */
    data class Seen(val source: String, val pkg: String?, val notice: String?)

    /**
     * 같은 금액·3분 안에 이미 기록된 거래(그 거래를 알린 알림들 [seen])가 있을 때, 지금 알림이 **다른 거래**인지.
     * 같은 경로의 다른 알림이 그 거래를 이미 알렸으면 다른 거래입니다. 같은 알림이 다시 왔거나 다른 경로로만 알려졌으면(앱 푸시 ↔ 문자)
     * 같은 거래이고, 표시가 없는 예전 기록과 표시를 모르는 지금 알림도 지금처럼 같은 거래로 봅니다.
     */
    fun another(seen: List<Seen>, source: String?, pkg: String?, notice: String?): Boolean {
        if (source == null || notice == null) return false
        val sameRoute = seen.filter { it.source == source && it.pkg == pkg && it.notice != null }
        return sameRoute.isNotEmpty() && sameRoute.none { it.notice == notice }
    }
}
