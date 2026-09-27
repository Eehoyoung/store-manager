import { useEffect, useMemo, useState } from "react";
import { LegalFooter } from "../layout/LegalFooter";
import { Link, useLocation } from "react-router-dom";
import "./intro.css";


// ★ 검색 노출용 제목·설명. scripts/prerender.mjs 가 intro.html <head> 에도 같은 값을 쓴다.
//   "자동 답글"이라고 쓰지 말 것 — 자동 게시는 매장별 선택이고 민감 리뷰는 차단된다.
export const INTRO_TITLE = "소담리뷰 | 배달 리뷰 AI 답글 관리 서비스";
export const INTRO_DESCRIPTION =
  "배달앱 리뷰 내용과 매장 말투를 반영해 사장님 답글을 준비하고, 위생·이물질·법적 분쟁처럼 민감한 리뷰는 자동 게시하지 않는 리뷰 답글 관리 서비스. 쿠폰 무료체험 30일 0원.";

const benefits = [
  {
    label: "리뷰 핵심 반영",
    title: "고객이 적은 내용을 놓치지 않습니다",
    body: "맛·양·배송·누락처럼 리뷰에 실제로 적힌 내용을 답글에 반영합니다.",
  },
  {
    label: "매장 말투 적용",
    title: "우리 매장답게 답합니다",
    body: "호칭, 문장 길이, 피하고 싶은 표현을 정해 획일적인 답글을 줄입니다.",
  },
  {
    label: "민감 리뷰 정지",
    title: "사람이 봐야 할 리뷰에서는 멈춥니다",
    body: "위생·이물질·식중독·법적 분쟁처럼 민감한 리뷰는 자동 게시하지 않습니다.",
  },
];

const operationSteps = [
  { title: "리뷰를 한곳에 모읍니다", body: "연결한 배달앱의 새 리뷰를 매장별로 정리해 놓친 리뷰가 없도록 보여줍니다." },
  { title: "내용과 위험 신호를 나눕니다", body: "맛·양·배송·누락 같은 핵심 내용과 위생·이물질·분쟁 같은 위험 신호를 구분합니다." },
  { title: "매장 말투로 답글을 준비합니다", body: "호칭, 시작 문장, 이모지 사용 여부, 답글 길이와 금지 표현을 매장 설정에 맞춥니다." },
  { title: "안전한 답글만 게시합니다", body: "안전 기준을 통과한 답글만 예약하고, 위험 리뷰는 멈춰 사장님 확인 대상으로 남깁니다." },
];

const faqs = [
  {
    question: "기존 자동답글과 무엇이 다른가요?",
    answer:
      "같은 감사 문구를 반복하는 대신, 리뷰에 적힌 핵심 내용과 매장에서 정한 말투 기준을 답글에 반영하는 데 초점을 둡니다.",
  },
  {
    question: "모든 리뷰가 자동으로 게시되나요?",
    answer:
      "아닙니다. 위생·이물질·식중독·법적 분쟁처럼 사람이 확인해야 하는 리뷰는 자동 게시하지 않고 확인 대상으로 분리합니다.",
  },
  {
    question: "무료 이용 후 가격은 얼마인가요?",
    answer: "첫 30일은 0원이며, 그 뒤부터 매장당 월 33,000원(VAT 포함)입니다.",
  },
  {
    question: "리뷰 내용도 만들어 주나요?",
    answer: "아닙니다. 고객이 작성하는 리뷰 본문은 생성하거나 수정하지 않습니다. 사장님 답글만 다룹니다.",
  },
];

export function IntroPage() {
  const location = useLocation();
  // ★ 이 페이지는 빌드 시 query 없는 `/` 로 미리 렌더링된다(intro.html). 첫 렌더는 그 HTML 과
  //   같아야 하므로 query(utm 등)는 하이드레이션 뒤에 반영한다.
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);
  const search = hydrated ? location.search : "";
  const signupPath = useMemo(() => {
    // 쿠폰번호는 비공개로 개별 전달한다(2026-09-28). 여기서 채우지 않는다.
    const params = new URLSearchParams(search);
    const query = params.toString();
    return query ? `/signup?${query}` : "/signup";
  }, [search]);

  useEffect(() => {
    const previousTitle = document.title;
    const description = document.querySelector<HTMLMetaElement>('meta[name="description"]');
    const previousDescription = description?.content;

    document.title = INTRO_TITLE;
    if (description) description.content = INTRO_DESCRIPTION;

    return () => {
      document.title = previousTitle;
      if (description && previousDescription) description.content = previousDescription;
    };
  }, []);

  return (
    <div className="intro-page">
      <header className="intro-header" aria-label="소담리뷰 소개">
        <Link className="intro-brand" to="/" aria-label="소담리뷰 홈">
          <img src="/sodam-wordmark.png" alt="소담" />
          <span className="intro-brand__divider" aria-hidden="true" />
          <span>소담리뷰</span>
        </Link>
      </header>

      <main>
        <section className="intro-hero" aria-labelledby="intro-title">
          <div className="intro-hero__copy">
            <h1 id="intro-title"><span className="intro-hero__brand">소담리뷰</span>고객의 목소리는<br />놓치지 않고,<br /><em>위험한 답글은 멈춥니다.</em></h1>
            <p className="intro-hero__lede">
              소담리뷰가 리뷰에 적힌 맛·양·배송 이야기를 읽고, 매장에서 정한 말투에 맞춰 답합니다.
              사람이 판단해야 하는 리뷰에서는 자동으로 멈춥니다.
            </p>

            <div className="intro-offer-line" aria-label="무료체험 조건">
              <strong>첫 30일 0원</strong>
              <span>선착순 30개 매장</span>
              <span>이후 월 33,000원(VAT 포함)</span>
            </div>

            <Link className="intro-cta intro-cta--primary" to={signupPath}>
              30일 무료체험 신청하기
              <span aria-hidden="true">→</span>
            </Link>
            <p className="intro-hero__fine">가입할 때 안내받은 쿠폰번호를 입력해 주세요.</p>
          </div>

          <div className="reply-console" aria-label="리뷰 내용을 반영한 답글 예시">
            <div className="reply-console__head">
              <span className="reply-console__status"><i aria-hidden="true" /> 답글 준비 예시</span>
              <span className="reply-console__id">SODAM REVIEW</span>
            </div>
            <article className="reply-signal">
              <p className="reply-signal__label">고객 리뷰</p>
              <p className="reply-signal__review">“양도 넉넉하고 배송도 빨랐어요.”</p>
              <div className="reply-signal__tags" aria-label="리뷰에서 확인한 핵심">
                <span>양</span><span>배송</span><span>긍정</span>
              </div>
            </article>
            <div className="reply-console__route" aria-hidden="true"><span />리뷰 핵심과 매장 말투 반영<span /></div>
            <article className="reply-signal reply-signal--answer">
              <p className="reply-signal__label">사장님 답글</p>
              <p>고객님, 넉넉한 양과 빠른 배송을 좋게 봐주셔서 감사합니다. 다음 주문도 정성껏 준비하겠습니다.</p>
            </article>
            <p className="reply-console__caption">화면 이해를 돕기 위한 답글 예시입니다.</p>
          </div>
        </section>

        <section className="intro-section intro-operation" aria-labelledby="operation-title">
          <div className="intro-section__heading">
            <p className="intro-section-label">소담리뷰 작동 방식</p>
            <h2 id="operation-title">리뷰가 도착한 뒤,<br />답글이 올라가기까지.</h2>
            <p>사장님이 매번 복사하고 붙여넣지 않아도 되도록 반복 업무를 잇고, 사람의 판단이 필요한 순간에는 자동으로 멈춥니다.</p>
          </div>
          <ol>
            {operationSteps.map((step, index) => (
              <li key={step.title}>
                <span className="readout">{String(index + 1).padStart(2, "0")}</span>
                <div><h3>{step.title}</h3><p>{step.body}</p></div>
              </li>
            ))}
          </ol>
          <div className="intro-operation__result" role="note">
            <strong>결과</strong>
            <span>반복 답글은 줄이고</span><span>매장 말투는 지키고</span><span>위험 답글은 사람이 확인합니다</span>
          </div>
        </section>

        <section className="intro-problem" aria-labelledby="problem-title">
          <p className="intro-section-label">지금 쓰는 답글을 확인해 보세요</p>
          <h2 id="problem-title">자동답글인데도 직접 다시 손보고 있나요?</h2>
          <div className="intro-problem__list">
            <p><span>01</span> 리뷰와 상관없는 감사 문구가 반복됩니다.</p>
            <p><span>02</span> 맛·양·배송 같은 고객의 핵심 이야기가 빠집니다.</p>
            <p><span>03</span> 모든 매장이 비슷한 말투로 답하게 됩니다.</p>
          </div>
        </section>

        <section className="intro-section" aria-labelledby="benefit-title">
          <div className="intro-section__heading">
            <p className="intro-section-label">답글 개수보다 답글의 내용</p>
            <h2 id="benefit-title">리뷰 내용을 읽고,<br />매장답게 답합니다.</h2>
          </div>
          <div className="intro-benefits">
            {benefits.map((benefit) => (
              <article className="intro-benefit" key={benefit.label}>
                <span>{benefit.label}</span>
                <h3>{benefit.title}</h3>
                <p>{benefit.body}</p>
              </article>
            ))}
          </div>
        </section>

        <section className="intro-safety" aria-labelledby="safety-title">
          <div className="intro-safety__lamp" aria-hidden="true"><span /></div>
          <div>
            <p className="intro-section-label">자동 게시 정지 기준</p>
            <h2 id="safety-title">민감한 리뷰는<br />사장님 확인 없이 올리지 않습니다.</h2>
            <p>
              위생·이물질·식중독·법적 분쟁처럼 사람이 판단해야 하는 리뷰는 자동 게시하지 않습니다.
              고객 리뷰 본문은 생성하거나 수정하지 않고, 사장님 답글만 다룹니다.
            </p>
          </div>
          <div className="intro-safety__sample" aria-label="민감한 리뷰 처리 예시">
            <img className="intro-safety__mascot" src="/sodam-mascot.png" alt="" aria-hidden="true" />
            <span>위생·이물질 관련 표현 감지</span>
            <strong>자동 게시 안 함</strong>
            <p>사장님 확인 대상으로 분리</p>
          </div>
        </section>

        <section className="intro-section intro-process" aria-labelledby="process-title">
          <div className="intro-section__heading">
            <p className="intro-section-label">도입 과정</p>
            <h2 id="process-title">시작은 세 단계면 됩니다.</h2>
          </div>
          <ol>
            <li><span>1</span><div><strong>무료체험 신청</strong><p>가입하면서 안내받은 쿠폰번호를 입력합니다.</p></div></li>
            <li><span>2</span><div><strong>매장 답글 기준 설정</strong><p>호칭·문장 길이·피하고 싶은 표현을 정합니다.</p></div></li>
            <li><span>3</span><div><strong>실제 리뷰로 확인</strong><p>답글의 구체성과 민감 리뷰 정지를 한 달 동안 확인합니다.</p></div></li>
          </ol>
        </section>

        <section className="intro-offer" id="offer" aria-labelledby="offer-title">
          <div className="intro-offer__main">
            <p className="intro-section-label">정식 오픈 기념 · 선착순 30개 매장</p>
            <h2 id="offer-title">비용을 내기 전에<br />한 달 동안 확인하세요.</h2>
            <p>실제 매장 리뷰에 어떤 답글이 작성되는지 확인한 뒤 계속 사용할지 결정하세요.</p>
          </div>
          <div className="intro-offer__price">
            <p>첫 30일 이용료</p>
            <strong>0원</strong>
            <dl>
              <div><dt>신청 방법</dt><dd>쿠폰번호 입력</dd></div>
              <div><dt>체험 이후</dt><dd>월 33,000원</dd></div>
              <div><dt>부가세</dt><dd>포함</dd></div>
            </dl>
            <Link className="intro-cta intro-cta--light" to={signupPath}>30일 무료체험 신청하기</Link>
          </div>
        </section>

        <section className="intro-faq" aria-labelledby="faq-title">
          <p className="intro-section-label">신청 전 확인</p>
          <h2 id="faq-title">자주 묻는 질문</h2>
          <div>
            {faqs.map((faq) => (
              <details key={faq.question}>
                <summary>{faq.question}</summary>
                <p>{faq.answer}</p>
              </details>
            ))}
          </div>
        </section>

        <section className="intro-final" aria-labelledby="final-title">
          <p className="intro-section-label">오늘 쌓인 답글부터</p>
          <h2 id="final-title">한 달 동안 직접 써보고 결정하세요.</h2>
          <Link className="intro-cta intro-cta--primary" to={signupPath}>첫 30일 0원으로 신청하기 <span aria-hidden="true">→</span></Link>
          <p>선착순 30개 매장 · 체험 이후 월 33,000원(VAT 포함)</p>
        </section>
      </main>

      {/* ★ 전자상거래법 제10조 표시는 '거래 전' 화면에 있어야 한다 — 로그인 뒤가 아니라 여기다. */}
      <footer className="intro-footer">
        <nav aria-label="하단 링크">
          <Link to="/login">로그인</Link>
        </nav>
        <LegalFooter />
      </footer>

      <div className="intro-sticky" aria-label="무료체험 신청">
        <div><strong>첫 30일 0원</strong><span>이후 월 33,000원</span></div>
        <Link to={signupPath}>무료체험 신청</Link>
      </div>
    </div>
  );
}
