package com.jeiel85.doran.model

/**
 * 처음 깔 때 넣는 카테고리. id가 고정이라 두 폰이 각자 만들어도 같은 엔티티가 되고
 * 동기화로 중복되지 않습니다(HLC는 Hlc.SEED라 누가 고치든 고친 쪽이 이깁니다).
 * icon 이름은 res/drawable-nodpi/art_<icon>.webp (design/make_art.py가 만듦).
 */
object Defaults {
    val categories: List<Category> = listOf(
        Category("c.food", "식비", Kind.EXPENSE, "food", 0, 0),
        Category("c.cafe", "카페·간식", Kind.EXPENSE, "cafe", 1, 1),
        Category("c.drinks", "술·유흥", Kind.EXPENSE, "drinks", 2, 2),
        Category("c.household", "생활용품", Kind.EXPENSE, "household", 3, 3),
        Category("c.shopping", "쇼핑", Kind.EXPENSE, "shopping", 4, 4),
        Category("c.beauty", "의류·미용", Kind.EXPENSE, "beauty", 5, 5),
        Category("c.transit", "교통", Kind.EXPENSE, "transit", 6, 6),
        Category("c.car", "자동차", Kind.EXPENSE, "car", 7, 7),
        Category("c.housing", "주거·관리비", Kind.EXPENSE, "housing", 8, 8),
        Category("c.telecom", "통신", Kind.EXPENSE, "telecom", 9, 9),
        Category("c.medical", "의료·건강", Kind.EXPENSE, "medical", 10, 10),
        Category("c.education", "교육", Kind.EXPENSE, "education", 11, 11),
        Category("c.childcare", "육아", Kind.EXPENSE, "childcare", 0, 12),
        Category("c.pets", "반려동물", Kind.EXPENSE, "pets", 1, 13),
        Category("c.leisure", "문화·여가", Kind.EXPENSE, "leisure", 2, 14),
        Category("c.travel", "여행", Kind.EXPENSE, "travel", 3, 15),
        Category("c.gift", "경조사·선물", Kind.EXPENSE, "gift", 4, 16),
        Category("c.insurance", "보험", Kind.EXPENSE, "insurance", 5, 17),
        Category("c.tax", "세금·공과금", Kind.EXPENSE, "tax", 6, 18),
        Category("c.subscription", "구독", Kind.EXPENSE, "subscription", 7, 19),
        Category("c.allowance", "용돈", Kind.EXPENSE, "allowance", 8, 20),
        Category("c.etc", "기타", Kind.EXPENSE, "etc", 9, 21),
        Category("c.salary", "급여", Kind.INCOME, "salary", 0, 0),
        Category("c.side", "부수입", Kind.INCOME, "side", 1, 1),
        Category("c.interest", "금융수입", Kind.INCOME, "interest", 2, 2),
        Category("c.income_etc", "기타수입", Kind.INCOME, "income_etc", 3, 3),
    )

    const val EXPENSE_FALLBACK = "c.etc"
    const val INCOME_FALLBACK = "c.income_etc"
    const val ALLOWANCE = "c.allowance"

    /** 가맹점 이름 → 카테고리. 위에서부터 먼저 맞는 것을 씁니다(구독을 쇼핑보다 먼저 보는 식). */
    val keywords: List<Pair<String, List<String>>> = listOf(
        "c.subscription" to listOf("넷플릭스", "NETFLIX", "유튜브", "YOUTUBE", "디즈니", "DISNEY", "티빙", "TVING", "웨이브",
            "왓챠", "쿠팡와우", "와우멤버십", "멜론", "지니뮤직", "스포티파이", "SPOTIFY", "APPLE.COM", "애플", "GOOGLE",
            "구글", "네이버플러스", "밀리의서재", "리디", "CHATGPT", "OPENAI", "ANTHROPIC", "CLAUDE"),
        "c.cafe" to listOf("스타벅스", "STARBUCKS", "투썸", "이디야", "메가커피", "메가엠지씨", "메가MGC", "컴포즈", "빽다방", "폴바셋",
            "할리스", "커피", "카페", "CAFE", "COFFEE", "파리바게", "뚜레쥬르", "베이커리", "제과", "배스킨", "던킨", "설빙",
            "공차", "노티드"),
        "c.drinks" to listOf("호프", "포차", "주점", "와인", "BAR", "펍", "PUB", "노래", "이자카야"),
        "c.food" to listOf("배달의민족", "배민", "요기요", "쿠팡이츠", "식당", "김밥", "국밥", "치킨", "피자", "버거", "맥도날드",
            "롯데리아", "버거킹", "맘스터치", "서브웨이", "본죽", "한솥", "도시락", "분식", "떡볶이", "냉면", "갈비", "고기",
            "삼겹", "횟집", "초밥", "스시", "짜장", "중국집", "반점", "칼국수", "보쌈", "족발", "푸드", "FOOD", "키친", "마트",
            "이마트", "홈플러스", "롯데마트", "코스트코", "트레이더스", "하나로", "식자재", "정육", "농협", "컬리", "청과",
            "GS25", "CU ", "CU(", "씨유", "세븐일레븐", "7-ELEVEN", "이마트24", "미니스톱", "편의점"),
        "c.household" to listOf("다이소", "생활", "철물", "세탁", "크린토피아", "이케아", "IKEA"),
        "c.beauty" to listOf("올리브영", "미용", "헤어", "HAIR", "네일", "뷰티", "무신사", "유니클로", "UNIQLO", "자라", "ZARA",
            "에이블리", "지그재그", "탑텐", "스파오", "H&M", "나이키", "NIKE", "아디다스"),
        "c.shopping" to listOf("쿠팡", "COUPANG", "11번가", "G마켓", "지마켓", "옥션", "SSG", "신세계", "롯데백화점",
            "현대백화점", "아울렛", "네이버페이", "NAVERPAY", "스마트스토어", "알리", "ALIEXPRESS", "테무", "TEMU", "오늘의집"),
        "c.transit" to listOf("지하철", "버스", "택시", "카카오T", "카카오모빌리티", "티머니", "TMONEY", "코레일", "KTX",
            "SRT", "고속버스", "교통", "타다"),
        "c.car" to listOf("주유", "에너지", "칼텍스", "S-OIL", "에쓰오일", "오일뱅크", "충전소", "하이패스", "주차", "PARKING",
            "세차", "카센터", "타이어", "자동차"),
        "c.housing" to listOf("관리비", "월세", "도시가스", "가스", "한국전력", "한전", "수도", "아파트"),
        "c.telecom" to listOf("SKT", "에스케이텔레콤", "KT ", "케이티", "LG U+", "LGU+", "엘지유플러스", "통신", "알뜰폰", "헬로모바일"),
        // 동물병원이 '병원'(의료)에 먼저 걸리지 않게 반려동물을 앞에 둡니다.
        "c.pets" to listOf("동물병원", "펫", "PET", "애견", "고양이", "강아지"),
        // 진료과 이름: "OO소아청소년과"는 '소아과'에 안 걸렸습니다(2026-11-28). '외과'는 정형·성형·일반외과, '의학과'는 가정·재활·영상의학과 등.
        "c.medical" to listOf("병원", "의원", "약국", "치과", "한의원", "소아과", "청소년과", "내과", "외과", "안과", "피부과",
            "이비인후과", "산부인과", "비뇨", "의학과", "신경과", "정신과", "보건소", "메디컬", "클리닉", "검진"),
        "c.education" to listOf("학원", "교육", "서점", "교보문고", "예스24", "YES24", "알라딘", "영풍문고", "클래스101", "인프런", "학습지"),
        "c.childcare" to listOf("키즈", "유아", "아기", "베이비", "어린이집", "유치원", "장난감", "토이", "TOY"),
        "c.leisure" to listOf("CGV", "메가박스", "롯데시네마", "영화", "공연", "티켓", "인터파크", "게임", "PC방", "볼링", "헬스",
            "필라테스", "요가", "골프", "수영", "스포츠", "놀이공원", "에버랜드", "롯데월드"),
        "c.travel" to listOf("호텔", "HOTEL", "숙박", "모텔", "펜션", "리조트", "야놀자", "여기어때", "에어비앤비", "AIRBNB", "항공",
            "AIR", "대한항공", "아시아나", "제주항공", "진에어", "티웨이", "트립닷컴", "아고다", "AGODA", "면세"),
        "c.insurance" to listOf("보험", "생명", "화재", "손해"),
        "c.tax" to listOf("국세", "지방세", "세금", "과태료", "범칙금", "국민연금", "건강보험", "4대보험"),
        "c.gift" to listOf("축의", "부의", "꽃", "플라워", "FLOWER", "선물", "카카오선물"),
    )

    /** 아내·남편·다른 가족 색 (Theme.memberColors 순서). */
    const val MEMBER_COLORS = 6
}
