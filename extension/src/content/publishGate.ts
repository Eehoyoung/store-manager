/**
 * 게시 감지 게이트. ★ 이 제품의 법적 방어선이다.
 *
 * 확장은 게시(등록) 버튼을 절대 대신 누르지 않는다. 사람이 실제로 누른
 * 이벤트(event.isTrusted === true)만 POSTED 로 인정한다. 스크립트가
 * dispatchEvent 로 만든 이벤트는 항상 isTrusted === false 이며 위조할 수 없다.
 *
 * content/index.ts 는 replyForm 의 submit, replySubmitButton 의 click 리스너에
 * 이 함수를 그대로 연결한다 — 여기 말고 다른 곳에서 POSTED 를 만들지 않는다.
 */
export function handlePublishEvent(event: Event, onPosted: () => void): void {
  if (!event.isTrusted) return;
  onPosted();
}
