/**
 * 로그인·회원가입 좌측 패널.
 *
 * ★ 히어로에 마케팅 문구 대신 '제품의 동작'을 놓는다.
 * 리뷰파일럿이 파는 것은 생성 속도가 아니라 판정이다. 그래서 첫 화면에서 보여줄 것은
 * "어떤 리뷰는 자동으로 올라가고 어떤 리뷰는 멈추는가" 그 자체다.
 * 셸의 운항 상태 배너와 같은 램프 언어를 써서, 로그인 전후의 화면이 한 제품으로 읽히게 한다.
 */

const RULES = [
  {
    tone: "engaged",
    verdict: "자동 게시",
    example: "“사장님 덕분에 오늘도 맛있게 먹었어요”",
    note: "칭찬·단순 긍정은 매장 말투로 답글을 달고 바로 올립니다.",
  },
  {
    tone: "caution",
    verdict: "검사 후 게시",
    example: "“면이 조금 불어서 왔어요”",
    note: "개선 요청도 답글을 답니다. 보상·환불 약속 같은 표현은 걸러냅니다.",
  },
  {
    tone: "stopped",
    verdict: "멈춤",
    example: "“머리카락이 나왔어요”",
    note: "위생·이물질·법적 분쟁은 자동으로 올리지 않고 사람에게 넘깁니다.",
  },
] as const;

export function AuthAside() {
  return (
    <aside className="auth-aside">
      <p className="auth-aside__eyebrow label-etched">리뷰파일럿</p>
      <h2 className="auth-aside__headline">
        안전한 리뷰는 자동으로,
        <br />
        위험한 리뷰는 멈춤.
      </h2>
      <p className="auth-aside__lede">
        배민·요기요·쿠팡이츠 리뷰를 한곳에서 운영합니다. 무엇을 자동으로 올리고 무엇을 멈출지는
        이렇게 갈립니다.
      </p>

      <ul className="auth-aside__rules">
        {RULES.map((rule) => (
          <li key={rule.verdict} className={`auth-rule auth-rule--${rule.tone}`}>
            <span className="auth-rule__lamp" aria-hidden="true" />
            <span className="auth-rule__verdict">{rule.verdict}</span>
            <span className="auth-rule__example">{rule.example}</span>
            <span className="auth-rule__note">{rule.note}</span>
          </li>
        ))}
      </ul>

      <p className="auth-aside__foot">
        고객 리뷰는 만들지도 고치지도 않습니다. 사장님 답글만 다룹니다.
      </p>
    </aside>
  );
}
