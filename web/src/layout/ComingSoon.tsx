// 이번 스프린트에서 만들지 않는 화면(리뷰 목록/대시보드/페르소나/설정 등)의 자리표시자.
// 동작하지 않는 화면을 진짜처럼 흉내내지 않는다 — 목업 데이터를 넣지 않는다.
export function ComingSoon({ title }: { title: string }) {
  return (
    <div className="empty-state">
      <p className="empty-state__title">{title}</p>
      <p className="empty-state__desc">이 화면은 아직 준비 중입니다. 곧 제공될 예정입니다.</p>
    </div>
  );
}
