import { useEffect, useMemo } from "react";
import { Link, useLocation } from "react-router-dom";
import "./intro.css";

const OFFER_CODE = "OPEN30";

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
    answer: "첫 1개월은 0원이며, 2개월차부터 매장당 월 33,000원(VAT 포함)입니다.",
  },
  {
    question: "리뷰 내용도 만들어 주나요?",
    answer: "아닙니다. 고객이 작성하는 리뷰 본문은 생성하거나 수정하지 않습니다. 사장님 답글만 다룹니다.",
  },
];

export function IntroPage() {
  const location = useLocation();
  const signupPath = useMemo(() => {
    const params = new URLSearchParams(location.search);
    params.set("promo", OFFER_CODE);
    return `/signup?${params.toString()}`;
  }, [location.search]);

  useEffect(() => {
    const previousTitle = document.title;
    const description = document.querySelector<HTMLMetaElement>('meta[name="description"]');
    const previousDescription = description?.content;

    document.title = "리뷰파일럿 | 배달앱 리뷰 답글 1개월 무료체험";
    if (description) {
      description.content =
        "반복되는 감사 문구 대신 리뷰 내용과 매장 말투를 반영하는 배달앱 리뷰 답글 관리. 선착순 30개 매장, 첫 1개월 0원.";
    }

    return () => {
      document.title = previousTitle;
      if (description && previousDescription) description.content = previousDescription;
    };
  }, []);

  return (
    <div className="intro-page">
      <header className="intro-header" aria-label="리뷰파일럿 소개">
        <Link className="intro-brand" to="/" aria-label="리뷰파일럿 홈">
          <span className="intro-brand__mark" aria-hidden="true">R</span>
          <span>리뷰파일럿</span>
        </Link>
        <span className="intro-header__code">모집 코드 <strong>{OFFER_CODE}</strong></span>
      </header>

      <main>
        <section className="intro-hero" aria-labelledby="intro-title">
          <div className="intro-hero__copy">
            <p className="intro-kicker">배달앱 리뷰 답글 관리</p>
            <h1 id="intro-title">같은 감사 문구만<br />반복하는 답글,<br /><em>이제 그만 쓰세요.</em></h1>
            <p className="intro-hero__lede">
              리뷰에 적힌 맛·양·배송 이야기를 읽고, 매장에서 정한 말투에 맞춰 답합니다.
              민감한 리뷰에서는 자동으로 멈춥니다.
            </p>

            <div className="intro-offer-line" aria-label="무료체험 조건">
              <strong>첫 1개월 0원</strong>
              <span>선착순 30개 매장</span>
              <span>이후 월 33,000원(VAT 포함)</span>
            </div>

            <Link className="intro-cta intro-cta--primary" to={signupPath}>
              1개월 무료체험 신청하기
              <span aria-hidden="true">→</span>
            </Link>
            <p className="intro-hero__fine">신청할 때 모집 코드 {OFFER_CODE}을 확인해 주세요.</p>
          </div>

          <div className="reply-console" aria-label="리뷰 내용을 반영한 답글 예시">
            <div className="reply-console__head">
              <span className="reply-console__status"><i aria-hidden="true" /> 답글 준비 예시</span>
              <span className="reply-console__id">RP-0130</span>
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
            <li><span>1</span><div><strong>무료체험 신청</strong><p>매장 정보와 모집 코드 {OFFER_CODE}을 확인합니다.</p></div></li>
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
            <p>첫 1개월 이용료</p>
            <strong>0원</strong>
            <dl>
              <div><dt>모집 코드</dt><dd>{OFFER_CODE}</dd></div>
              <div><dt>2개월차부터</dt><dd>월 33,000원</dd></div>
              <div><dt>부가세</dt><dd>포함</dd></div>
            </dl>
            <Link className="intro-cta intro-cta--light" to={signupPath}>OPEN30으로 무료체험 신청하기</Link>
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
          <Link className="intro-cta intro-cta--primary" to={signupPath}>첫 1개월 0원으로 신청하기 <span aria-hidden="true">→</span></Link>
          <p>선착순 30개 매장 · 2개월차부터 월 33,000원(VAT 포함)</p>
        </section>
      </main>

      <footer className="intro-footer">
        <span>리뷰파일럿</span>
        <nav aria-label="하단 링크">
          <Link to="/legal/terms">이용약관</Link>
          <Link to="/legal/privacy">개인정보처리방침</Link>
          <Link to="/login">로그인</Link>
        </nav>
      </footer>

      <div className="intro-sticky" aria-label="무료체험 신청">
        <div><strong>첫 1개월 0원</strong><span>이후 월 33,000원</span></div>
        <Link to={signupPath}>무료체험 신청</Link>
      </div>
    </div>
  );
}
