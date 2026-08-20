import type { ReactNode } from "react";

export type BadgeTone = "neutral" | "info" | "success" | "warning" | "danger";

interface BadgeProps {
  tone?: BadgeTone;
  /** 색만으로 상태를 구분하지 않기 위한 보조 기호(색각 이상 대응). 장식용이라 스크린리더에는 숨긴다. */
  icon?: string;
  children: ReactNode;
}

export function Badge({ tone = "neutral", icon, children }: BadgeProps) {
  return (
    <span className={`badge badge--${tone}`}>
      {icon ? (
        <span aria-hidden="true" className="badge__icon">
          {icon}
        </span>
      ) : null}
      <span>{children}</span>
    </span>
  );
}
