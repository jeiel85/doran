// 연결 페이지(docs/join/) 동작. 상태: opening → install / other / invalid (DECISIONS.md D-15)
  // 같은 탭에서 다른 초대 링크를 열면 # 뒤만 바뀝니다: 처음부터 다시.
  window.addEventListener("hashchange", () => location.reload());
  (() => {
    const PKG = "com.jeiel85.doran";
    const frag = location.hash.slice(1);
    const setState = (s) => { document.body.dataset.state = s; };
    // 형식: <m|s>.<비밀값 43자>[.<초대한 사람 이름 base64url>] — 앱이 모든 값을 다시 검사합니다.
    const m = /^([ms])\.([A-Za-z0-9_-]{43})(?:\.([A-Za-z0-9_-]{1,64}))?$/.exec(frag);
    if (!m) return setState("invalid");

    const role = m[1] === "s" ? "sender" : "member";
    let inviter = "";
    try {
      if (m[3]) {
        const b64 = m[3].replace(/-/g, "+").replace(/_/g, "/");
        inviter = new TextDecoder().decode(Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))).slice(0, 12);
      }
    } catch { /* 이름이 깨졌으면 일반 문구 */ }
    document.getElementById("title").textContent = inviter ? `${inviter}님이 초대했어요` : "둥지 가족 연결";
    document.getElementById("lede").textContent = role === "sender"
      ? "이 폰의 카드·은행 알림을 가족 가계부로 보내 주세요. 가족 폰에는 \"○○님이 현대카드로 … 썼어요\"처럼 알림이 가요."
      : "같은 가계부를 함께 써요. 결제 알림이 오면 서로의 폰에 바로 기록돼요.";
    if (role === "sender") document.getElementById("senderNote").hidden = false;

    if (!/Android/i.test(navigator.userAgent)) return setState("other");

    // Chrome·삼성 인터넷: 설치돼 있으면 앱을 열고, 없으면 설치 안내로 이 페이지에 돌아옵니다.
    const back = `${location.origin}${location.pathname}?install=1#${frag}`;
    const intentUrl = `intent://join/${frag}#Intent;scheme=doran;package=${PKG};` +
      `S.browser_fallback_url=${encodeURIComponent(back)};end`;
    document.querySelectorAll("[data-open]").forEach((a) => { a.href = intentUrl; });
    document.getElementById("toInstall").addEventListener("click", (e) => { e.preventDefault(); setState("install"); });

    if (new URLSearchParams(location.search).has("install")) return setState("install");

    // 카메라 앱이 연 페이지라면 한 번 바로 시도합니다. 잠시 뒤에도 이 페이지가 앞에 있으면 설치 안내를 보여 줍니다.
    let left = false;
    document.addEventListener("visibilitychange", () => { if (document.hidden) left = true; });
    location.href = intentUrl;
    setTimeout(() => { if (!left && document.body.dataset.state === "opening") setState("install"); }, 2500);
  })();
