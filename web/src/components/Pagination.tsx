interface PaginationProps {
  page: number;
  hasMore: boolean;
  onPageChange: (page: number) => void;
  disabled?: boolean;
}

// 목록 API는 page/size + hasMore 규약을 공통 사용한다.
export function Pagination({ page, hasMore, onPageChange, disabled }: PaginationProps) {
  return (
    <nav className="pagination" aria-label="페이지 이동">
      <button
        type="button"
        className="btn btn--secondary"
        disabled={disabled || page === 0}
        onClick={() => onPageChange(page - 1)}
      >
        이전
      </button>
      <span className="pagination__label" aria-current="page">
        {page + 1} 페이지
      </span>
      <button
        type="button"
        className="btn btn--secondary"
        disabled={disabled || !hasMore}
        onClick={() => onPageChange(page + 1)}
      >
        다음
      </button>
    </nav>
  );
}
