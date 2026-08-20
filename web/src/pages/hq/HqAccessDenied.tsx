import { Link } from "react-router-dom";
import { EmptyState } from "../../components/EmptyState";

// 서버는 권한 없는 브랜드·타 브랜드 매장 접근에 404(RESOURCE_NOT_FOUND)를 준다(브랜드 존재 여부를 흘리지 않기 위함).
// 이를 "알 수 없는 오류"로 뭉개지 않고 안내 후 브랜드 선택 화면으로 돌려보낸다(문서 14 §11.5.2, U10).
export function HqAccessDenied() {
  return (
    <EmptyState
      title="접근 권한이 없는 브랜드입니다"
      description="본부 권한이 있는 브랜드가 아니거나 존재하지 않는 브랜드입니다."
      action={
        <Link to="/hq/brands" className="btn btn--secondary">
          브랜드 선택으로 돌아가기
        </Link>
      }
    />
  );
}
