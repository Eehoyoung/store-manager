import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { legalApi, type BusinessInfo } from "../api/legal";

/**
 * 화면 하단 사업자 정보 — 전자상거래법 제10조 제1항 표시사항.
 *
 * ★ 값은 서버(`app.business.*`)에서만 온다. 여기에 사업자등록번호를 적어 두지 말 것 —
 *   같은 값이 두 곳에 있으면 한쪽만 고쳐지는 날이 오고, 표시 의무가 있는 값이 화면마다
 *   다른 것은 그 자체로 위반이다.
 *
 * ★ 못 불러왔을 때 링크는 그대로 남긴다. 약관·처리방침 접근이 서버 상태에 걸리면 안 된다.
 */
export function LegalFooter() {
  const [info, setInfo] = useState<BusinessInfo | null>(null);

  useEffect(() => {
    legalApi.businessInfo().then(setInfo).catch(() => setInfo(null));
  }, []);

  return (
    <footer className="legal-footer">
      <nav className="legal-footer__links" aria-label="약관 및 정책">
        <Link to="/legal/terms">이용약관</Link>
        <Link to="/legal/privacy">개인정보처리방침</Link>
        <Link to="/legal/hq-data-sharing">가맹본부 제3자 제공</Link>
        <Link to="/legal/platform-credential">계정정보 처리위탁</Link>
      </nav>
      {info ? (
        <dl className="legal-footer__business">
          <div><dt>상호</dt><dd>{info.name}</dd></div>
          <div><dt>대표자</dt><dd>{field(info.representative)}</dd></div>
          <div><dt>사업자등록번호</dt><dd>{field(info.registrationNumber)}</dd></div>
          <div><dt>통신판매업 신고번호</dt><dd>{field(info.mailOrderNumber)}</dd></div>
          <div><dt>사업장 주소</dt><dd>{field(info.address)}</dd></div>
          <div><dt>전화</dt><dd>{field(info.phone)}</dd></div>
          <div><dt>이메일</dt><dd>{field(info.email)}</dd></div>
          <div><dt>개인정보 보호책임자</dt><dd>{field(info.privacyOfficer)}</dd></div>
        </dl>
      ) : null}
      <p className="legal-footer__copy">
        © {new Date().getFullYear()} {info?.name ?? "소담"} · {info?.serviceName ?? "리뷰파일럿"}
      </p>
    </footer>
  );
}

/** 아직 등록 전이면 빈칸으로 두지 않고 그렇다고 말한다 — 빈칸은 아무도 눈치채지 못한다. */
function field(value: string) {
  return value ? value : <span className="legal-footer__pending">사업자 등록 후 표시</span>;
}
