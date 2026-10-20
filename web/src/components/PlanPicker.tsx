import { Badge } from "./Badge";
import {
  ALL_PLATFORMS,
  optionPrice,
  PLATFORM_LABEL,
  isSelectionComplete,
  type PlanCount,
  type PlanSelection,
} from "../lib/tieredPlan";
import type { DeliveryPlatform } from "../api/types";

interface PlanPickerProps {
  value: PlanSelection;
  onChange: (next: PlanSelection) => void;
  disabled?: boolean;
  /** radio name 충돌 방지용 */
  name?: string;
  /** 서버 상품표(tiered.optionTotalsKrw). */
  prices: number[];
}

const COUNTS: PlanCount[] = [1, 2, 3];

/** 상품 선택 — 배달앱 1·2·3개. 네이버는 모든 상품에 포함이라 고르지 않는다. 처음엔 아무것도 골라져 있지 않다. */
export function PlanPicker({ value, onChange, disabled, name = "plan-count", prices }: PlanPickerProps) {
  const setCount = (count: PlanCount) =>
    // 개수를 바꾸면 이전에 고른 배달앱이 새 개수를 넘을 수 있어 잘라낸다.
    onChange({ count, platforms: count === 3 ? [] : value.platforms.slice(0, count) });

  const toggle = (p: DeliveryPlatform) => {
    if (value.count === null || value.count === 3) return;
    const has = value.platforms.includes(p);
    if (!has && value.platforms.length >= value.count) return;
    onChange({ ...value, platforms: has ? value.platforms.filter((x) => x !== p) : [...value.platforms, p] });
  };

  return (
    <fieldset className="plan-picker" disabled={disabled}>
      <legend className="plan-picker__legend">이용할 상품을 골라 주세요</legend>
      <p className="field__hint">네이버 스마트플레이스는 모든 상품에 포함돼요. 크롬 확장으로 답글 초안을 받고, 등록은 사장님이 직접 해요. 금액은 VAT 포함이에요.</p>
      <div className="plan-picker__options">
        {COUNTS.map((n) => (
          <label key={n} className={`plan-picker__option${value.count === n ? " plan-picker__option--on" : ""}`}>
            <input type="radio" name={name} checked={value.count === n} onChange={() => setCount(n)} />
            <span className="plan-picker__text">
              <strong>
                배달앱 {n}개{n === 3 ? " (배달의민족·쿠팡이츠·요기요)" : ""}
              </strong>
              <span>{optionPrice(prices, n) === null ? "금액 확인 중" : `월 ${optionPrice(prices, n)!.toLocaleString("ko-KR")}원`}</span>
            </span>
            {n === 3 ? <Badge tone="info">추천</Badge> : null}
          </label>
        ))}
      </div>
      {value.count === 1 || value.count === 2 ? (
        <div className="plan-picker__platforms" role="group" aria-label={`연동할 배달앱 ${value.count}개`}>
          <p className="field__hint">
            연동할 배달앱 {value.count}개를 골라 주세요. ({value.platforms.length}/{value.count})
          </p>
          {ALL_PLATFORMS.map((p) => {
            const checked = value.platforms.includes(p);
            return (
              <label key={p} className="plan-picker__platform">
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={!checked && value.platforms.length >= value.count!}
                  onChange={() => toggle(p)}
                />
                {PLATFORM_LABEL[p]}
              </label>
            );
          })}
        </div>
      ) : null}
      {value.count !== null && !isSelectionComplete(value) ? (
        <p role="status" className="field__hint">
          배달앱을 {value.count}개 골라야 다음으로 갈 수 있어요.
        </p>
      ) : null}
    </fieldset>
  );
}
