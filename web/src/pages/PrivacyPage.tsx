import { Link } from "react-router-dom";
import { LEGAL_INFO } from "../legal/legalInfo";

export function PrivacyPage() {
  return (
    <div className="legal-page">
      <header className="legal-page__header">
        <p className="legal-page__eyebrow">{LEGAL_INFO.serviceName} · {LEGAL_INFO.operatorName}</p>
        <h1>개인정보 처리방침</h1>
        <p>시행일: {LEGAL_INFO.effectiveDate}</p>
      </header>

      <article className="legal-page__document">
        <section>
          <h2>1. 총칙</h2>
          <p>
            {LEGAL_INFO.operatorName}(이하 “회사”)는 개인정보 보호법 등 관계 법령을 준수하며,
            {LEGAL_INFO.serviceName} 제공 과정에서 처리하는 개인정보의 항목·목적·보유기간·위탁·국외이전 및
            정보주체의 권리를 다음과 같이 공개합니다.
          </p>
        </section>

        <section>
          <h2>2. 처리하는 개인정보</h2>

          <h3>가. 회원가입·계정관리</h3>
          <ul>
            <li><strong>필수:</strong> 이름, 이메일, 비밀번호 해시, 매장명, 매장 주소</li>
            <li><strong>선택:</strong> 휴대전화번호, 가맹코드</li>
            <li><strong>이용 중 생성·변경:</strong> 계정 공개식별자, 가입·수정·최근 로그인 시각, 계정상태</li>
            <li><strong>목적:</strong> 회원 식별·인증, 매장 생성, 가맹소속 확인, 고객지원, 보안 및 서비스 제공</li>
            <li><strong>보유:</strong> 회원탈퇴 시까지. 법령상 보존의무 또는 분쟁·채권채무가 있는 경우 필요한 기간 동안 분리보관할 수 있습니다.</li>
          </ul>

          <h3>나. 배달플랫폼 계정 연동</h3>
          <ul>
            <li><strong>항목:</strong> 연동 플랫폼, 로그인 ID, 암호화된 로그인 비밀번호, 매장 연결정보, 연동상태, 검증·동기화 시각 및 오류상태</li>
            <li><strong>목적:</strong> 이용자가 권한을 가진 배달플랫폼의 리뷰 조회, 매장 연결, 사장님 답글 게시</li>
            <li><strong>보안:</strong> 비밀번호는 평문 DB 필드를 두지 않고 봉투암호화된 암호문과 암호화 키 자료로 저장합니다.</li>
            <li><strong>보유:</strong> 연동 해제 또는 회원탈퇴 시까지. 연동 해제·탈퇴 시 비밀번호 암호문과 복호화에 필요한 암호화 자료를 즉시 무효화·파기하도록 처리합니다.</li>
          </ul>

          <h3>다. 리뷰·답글 데이터</h3>
          <ul>
            <li><strong>항목:</strong> 플랫폼명, 플랫폼 리뷰 ID, 평점, 리뷰 본문, 마스킹된 작성자명, 작성자 해시, 주문메뉴, 이미지 URL, 플랫폼 부가정보, 작성일, 기존 사장님 답글, 분석결과, 생성된 답글 초안 및 게시상태</li>
            <li><strong>목적:</strong> 리뷰 통합조회, 중복수집 방지, AI 분석·답글 생성, 답글 게시, 통계·가맹본부 운영지원, 장애·분쟁 대응</li>
            <li><strong>가명처리:</strong> 원본 작성자 닉네임은 서비스 DB에 그대로 저장하지 않고 마스킹 및 해시값을 사용하도록 설계합니다.</li>
            <li><strong>보유:</strong> 현재 기본 정책은 수집 후 1,095일(3년)이며 기간이 지나면 리뷰 데이터 중 개인을 식별할 수 있는 부분을 익명화합니다. 정책 변경 시 기존 데이터에도 변경된 파기정책을 적용할 수 있습니다.</li>
          </ul>

          <h3>라. AI 처리정보</h3>
          <ul>
            <li><strong>항목:</strong> 리뷰 본문, 평점, 주문메뉴, 위험도·분류 컨텍스트, 매장 답글 스타일·금칙어·길이설정, 선택된 과거 답글 예시</li>
            <li><strong>목적:</strong> 리뷰 분류, 위험도 분석, 답글 초안 생성, 매장 말투 반영</li>
            <li>리뷰 본문에 고객이 직접 개인정보를 작성한 경우 해당 내용이 AI 입력에 포함될 수 있으므로 제6조 국외이전 내용을 함께 확인해야 합니다.</li>
          </ul>

          <h3>마. 결제·구독</h3>
          <ul>
            <li><strong>서비스가 처리하는 정보:</strong> 매장, 구독상태, 요금, 결제기간, 결제상태, 결제·해지 요청시각, 외부 결제 결과 식별정보</li>
            <li><strong>레거시 계좌이체를 사용하는 경우:</strong> 입금자명, 입금확인시각 등 거래 확인정보가 처리될 수 있습니다.</li>
            <li><strong>서비스가 저장하지 않는 정보:</strong> Groble 등 외부 결제화면에 직접 입력한 카드번호와 CVC는 Review Pilot 서버가 저장하지 않습니다.</li>
            <li><strong>보유:</strong> 관계 법령이 적용되는 경우 계약·청약철회 및 대금결제·공급 기록은 5년, 소비자 불만·분쟁처리 기록은 3년, 표시·광고 기록은 6개월 등 법정기간 동안 보존할 수 있습니다.</li>
          </ul>

          <h3>바. 서비스 이용·보안 기록</h3>
          <p>
            접속시각, 인증토큰 식별정보, 오류·작업상태, 수집·게시 성공/실패 기록, 관리자 감사로그 등이
            서비스 안정성, 부정이용 방지, 보안 및 장애 대응을 위해 처리될 수 있습니다.
          </p>
        </section>

        <section>
          <h2>3. 개인정보의 처리 목적</h2>
          <ul>
            <li>회원가입·로그인·본인확인 및 계정관리</li>
            <li>매장과 가맹본부의 권한 분리 및 접근통제</li>
            <li>배달플랫폼 연동, 리뷰 수집 및 답글 게시</li>
            <li>AI 리뷰분석, 답글 초안 및 자동화 기능</li>
            <li>구독·결제·해지 및 고객지원</li>
            <li>보안, 부정이용 탐지, 감사 및 장애 대응</li>
            <li>개인을 식별하지 않는 집계 통계와 서비스 품질 개선</li>
          </ul>
        </section>

        <section>
          <h2>4. 배달플랫폼 리뷰 작성자 정보의 처리 역할</h2>
          <p>
            이용자가 자신의 배달플랫폼 계정을 통해 접근 가능한 리뷰를 Review Pilot에 연동하는 경우,
            이용자 또는 해당 매장 사업자가 리뷰 고객정보의 개인정보 처리주체가 되고 회사가 리뷰 수집·가명처리·
            AI 분석·답글 게시를 위한 처리업무를 위탁받는 관계가 성립할 수 있습니다. 회사는 해당 정보를
            서비스 제공 목적을 넘어 독립적인 마케팅 목적으로 이용하지 않습니다.
          </p>
        </section>

        <section>
          <h2>5. 개인정보 처리업무의 위탁</h2>
          <p>회사는 서비스 제공을 위해 다음과 같은 외부 사업자에게 개인정보 처리업무를 위탁할 수 있습니다.</p>
          <div className="legal-table-wrap">
            <table className="legal-table">
              <thead><tr><th>수탁자</th><th>업무</th><th>처리정보</th><th>보유</th></tr></thead>
              <tbody>
                <tr>
                  <td>{LEGAL_INFO.dataApiProcessor}</td>
                  <td>배달플랫폼 로그인 연동, 리뷰 조회, 답글 게시를 위한 DataAPI 처리</td>
                  <td>플랫폼·로그인 ID, 업체 규격으로 암호화된 로그인 비밀번호, 매장·리뷰·답글 요청정보</td>
                  <td>위탁계약 및 API 처리에 필요한 기간</td>
                </tr>
                <tr>
                  <td>{LEGAL_INFO.cloudProvider}</td>
                  <td>서버·DB·백업 등 인프라 운영</td>
                  <td>서비스에 저장되는 개인정보 및 운영데이터</td>
                  <td>위탁계약 종료 또는 서비스 데이터 삭제 시까지</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p><strong>출시 전 확인:</strong> 계약서상 정확한 법인명, 재위탁사 및 보관지역을 확정하여 위 표를 갱신해야 합니다.</p>
        </section>

        <section>
          <h2>6. 개인정보의 국외 이전 — Anthropic API</h2>
          <p>
            AI 리뷰 분석·답글 생성을 활성화한 경우 서비스는 Anthropic의 상용 API를 호출할 수 있습니다.
            이 과정에서 리뷰 본문에 개인정보가 포함되어 있다면 국외 이전에 해당할 수 있습니다.
          </p>
          <div className="legal-table-wrap">
            <table className="legal-table">
              <tbody>
                <tr><th>이전받는 자</th><td>Anthropic, PBC (privacy@anthropic.com)</td></tr>
                <tr><th>이전 국가</th><td>{LEGAL_INFO.anthropicTransferCountry}</td></tr>
                <tr><th>이전 시점·방법</th><td>AI 분석·답글 생성 요청 시 암호화된 네트워크를 통한 전송</td></tr>
                <tr><th>이전 항목</th><td>리뷰 본문, 평점, 주문메뉴, 답글 스타일·금칙어·생성에 필요한 컨텍스트</td></tr>
                <tr><th>이전 목적</th><td>리뷰 분류·위험도 분석 및 사장님 답글 초안 생성</td></tr>
                <tr><th>보유기간</th><td>{LEGAL_INFO.anthropicRetention}</td></tr>
              </tbody>
            </table>
          </div>
          <p>
            <strong>출시 전 확인:</strong> 실제 Anthropic 상용 API 계약/DPA와 사용 모델의 데이터 보유정책을
            확인하여 이전 국가·보유기간을 확정해야 하며, 개인정보 보호법상 필요한 고지·동의 또는 계약이행에
            필요한 처리위탁 근거를 갖춘 뒤 AI 기능을 운영해야 합니다.
          </p>
        </section>

        <section>
          <h2>7. 외부 결제서비스</h2>
          <p>
            결제화면은 외부 결제서비스에서 제공될 수 있습니다. 카드번호, CVC 등 결제수단 정보는 해당 결제사업자가
            직접 수집·처리하며 Review Pilot 서버는 이를 저장하지 않습니다.
          </p>
          <p>예정 결제서비스 계약주체: {LEGAL_INFO.paymentProvider}</p>
          <p><strong>출시 전 확인:</strong> 결제사업자의 정확한 법인명, 제공·위탁 구조, 처리항목, 환불·해지 연동방식을 확정해야 합니다.</p>
        </section>

        <section>
          <h2>8. 개인정보의 제3자 제공</h2>
          <p>
            회사는 정보주체의 개인정보를 처리목적 범위 안에서 이용하며, 별도의 동의 또는 법령상 근거 없이
            제3자에게 제공하지 않습니다. 향후 결제·제휴 등으로 제3자 제공이 필요한 경우 제공받는 자, 목적,
            항목, 보유기간 및 거부권을 별도로 알리고 필요한 절차를 거칩니다.
          </p>
        </section>

        <section>
          <h2>9. 개인정보의 파기</h2>
          <ol>
            <li>보유기간 경과 또는 처리목적 달성으로 개인정보가 불필요해지면 지체 없이 파기합니다.</li>
            <li>회원탈퇴 시 비밀번호 해시를 삭제하고 배달플랫폼 자격증명 암호문을 파기하며 활성 구독과 자동화 작업을 중지합니다.</li>
            <li>리뷰 개인정보는 기본 1,095일 경과 후 정기 배치에서 개인 식별요소를 익명화하며, 답글·분석·감사 무결성을 위해 비식별 행 자체는 남을 수 있습니다.</li>
            <li>전자적 파일은 복구하기 어렵도록 삭제 또는 비식별 처리하고, 출력물이 있는 경우 복구하기 어려운 방법으로 파기합니다.</li>
          </ol>
        </section>

        <section>
          <h2>10. 정보주체의 권리와 행사방법</h2>
          <p>
            정보주체는 관계 법령이 정하는 범위에서 개인정보 열람, 정정·삭제, 처리정지, 동의철회 등을 요구할 수
            있습니다. 회원은 설정 화면의 정보수정·연동해제·회원탈퇴 기능을 이용하거나 고객센터에 요청할 수
            있습니다. 리뷰 작성자 정보에 관한 요청은 해당 매장·배달플랫폼을 통해 접수될 수 있으며 회사가
            수탁 처리하는 데이터에 기술적 조치가 필요한 경우 매장과 협력하여 처리합니다.
          </p>
          <p>문의: {LEGAL_INFO.customerServiceEmail} / {LEGAL_INFO.customerServicePhone}</p>
        </section>

        <section>
          <h2>11. 개인정보의 안전성 확보조치</h2>
          <ul>
            <li>회원 비밀번호의 단방향 해시 저장</li>
            <li>배달플랫폼 비밀번호의 봉투암호화 및 평문 DB 필드 금지</li>
            <li>연동 해제 시 자격증명 암호문·키 자료 즉시 무효화</li>
            <li>매장·가맹본부·관리자 권한에 따른 접근통제</li>
            <li>리뷰 작성자 원본 닉네임 대신 마스킹·해시 사용</li>
            <li>내부 AI API에 별도 내부 토큰 적용</li>
            <li>인증정보·자격증명의 로그 기록 제한</li>
            <li>HTTPS 등 암호화된 통신 사용</li>
            <li>개인정보 파기배치와 감사기록 운영</li>
          </ul>
        </section>

        <section>
          <h2>12. 쿠키 및 인증정보</h2>
          <p>
            서비스는 로그인 상태 유지를 위해 인증정보를 처리합니다. 현재 웹 클라이언트의 access token은 메모리
            중심으로 관리하고, 세션 복구를 위한 refresh token은 HttpOnly 쿠키 방식으로 처리하도록 설계되어
            있습니다. 브라우저에서 쿠키를 차단하면 로그인 유지 기능이 제한될 수 있습니다.
          </p>
        </section>

        <section>
          <h2>13. 자동화·AI 처리에 관한 안내</h2>
          <p>
            서비스는 리뷰 평점·본문 등을 분석하여 카테고리, 감성, 위험신호를 분류하고 답글 초안을 생성합니다.
            이용자가 자동게시를 활성화하면 설정한 조건과 위험도 판정에 따라 게시 작업이 자동 실행될 수 있습니다.
            고위험·금칙어 등 일부 조건은 자동게시를 차단하도록 설계되어 있습니다. 이 기능은 법률상 권리·의무에
            중대한 영향을 미치는 자동화된 의사결정을 목적으로 하지 않으며, 이용자는 설정을 변경하거나 자동게시를
            중단하고 사람이 직접 검토하는 방식으로 운영할 수 있습니다.
          </p>
        </section>

        <section>
          <h2>14. 개인정보 보호책임자</h2>
          <dl>
            <dt>개인정보 보호책임자</dt><dd>{LEGAL_INFO.privacyOfficer}</dd>
            <dt>이메일</dt><dd>{LEGAL_INFO.customerServiceEmail}</dd>
            <dt>전화</dt><dd>{LEGAL_INFO.customerServicePhone}</dd>
          </dl>
        </section>

        <section>
          <h2>15. 권익침해 구제</h2>
          <p>
            개인정보 침해에 대한 신고·상담이 필요한 경우 개인정보침해 신고센터, 개인정보분쟁조정위원회,
            경찰청 등 관계기관의 공식 안내를 이용할 수 있습니다.
          </p>
        </section>

        <section>
          <h2>16. 처리방침 변경</h2>
          <p>
            이 처리방침이 변경되는 경우 서비스 공지 등을 통해 시행일과 주요 변경내용을 알립니다. 개인정보의
            국외이전, 제3자 제공 등 별도의 동의가 필요한 사항이 변경되는 경우 관계 법령에 따른 절차를 거칩니다.
          </p>
        </section>

        <section>
          <h2>17. 사업자 정보</h2>
          <dl>
            <dt>상호</dt><dd>{LEGAL_INFO.operatorName}</dd>
            <dt>대표자</dt><dd>{LEGAL_INFO.representative}</dd>
            <dt>사업장 주소</dt><dd>{LEGAL_INFO.businessAddress}</dd>
            <dt>사업자등록번호</dt><dd>{LEGAL_INFO.businessRegistrationNumber}</dd>
            <dt>고객센터</dt><dd>{LEGAL_INFO.customerServicePhone} / {LEGAL_INFO.customerServiceEmail}</dd>
          </dl>
        </section>
      </article>

      <footer className="legal-page__footer">
        <Link to="/signup">회원가입으로 돌아가기</Link>
        <Link to="/terms">이용약관</Link>
      </footer>
    </div>
  );
}
