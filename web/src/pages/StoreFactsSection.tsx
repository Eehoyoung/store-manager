import { useEffect, useState } from "react";

import { ApiError } from "../api/client";
import { personaApi } from "../api/persona";
import type { StoreFactDto } from "../api/types";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { Field } from "../components/Field";
import { Skeleton } from "../components/Skeleton";

/**
 * 매장 사실 입력.
 *
 * ★ 왜 있는가 — [절대 규칙] 8번("확인·확정하지 않은 조치를 약속하지 마라") 때문에
 *   주차·대기시간·좌석·소음·접근성 지침이 전부 "확정하지 않은 ~를 약속하지 마라" 로 끝난다.
 *   그 결과 방문 리뷰의 절반이 "확인해 보겠습니다" 한 줄로 수렴한다. 손님은 아무것도 얻지
 *   못하고 사장님은 답글이 다 똑같다고 느낀다.
 *
 * ★ 여기 적은 것은 **사장님이 확정한 사실**이라 답글에 그대로 인용해도 8번을 어기지 않는다.
 *   그래서 안내 문구가 "매장 소개" 가 아니라 "손님이 다음에 써먹을 수 있는 것" 이다.
 *
 * ★ 전부 선택 입력이다. 비워 두면 지금까지와 똑같이 동작한다 — 빈손이 안전한 기본값이다.
 */

/** 항목별 예시. 무엇을 써야 할지 모르면 아무도 안 쓴다 — 빈 칸보다 예시가 중요하다. */
const PLACEHOLDER: Record<string, string> = {
  주차: "건물 뒤 5대, 만차 시 옆 공영주차장 도보 1분",
  대기시간: "네이버 예약 가능, 주말 저녁은 1시간 전 권장",
  좌석: "4인 테이블 6개, 6인 이상은 전화로 문의",
  소음: "2층은 조용한 편, 조용한 자리 원하시면 예약 시 말씀",
  접근성: "2번 출구에서 도보 3분, 지하 1층이라 간판이 잘 안 보임",
  영업시간: "11:30~21:00, 브레이크타임 15:00~17:00, 월요일 휴무",
  예약: "네이버 예약만 받습니다. 당일 예약은 전화로",
  포장: "포장 주문은 전화로 미리 말씀 주시면 대기 없이 픽업",
};

const HINT: Record<string, string> = {
  주차: "주차 불만 리뷰에 그대로 안내됩니다",
  대기시간: "웨이팅 불만 리뷰에 예약 방법을 알려 드립니다",
  좌석: "자리가 좁다는 리뷰에 단체 안내가 나갑니다",
  소음: "시끄러웠다는 리뷰에 조용한 자리를 안내합니다",
  접근성: "찾기 어려웠다는 리뷰에 길 안내가 나갑니다",
  영업시간: "헛걸음하셨다는 리뷰에 정확한 시간을 알려 드립니다",
  예약: "예약 관련 리뷰에 방법을 안내합니다",
  포장: "포장 관련 리뷰에 픽업 방법을 안내합니다",
};

export function StoreFactsSection({ storeId }: { storeId: string }) {
  const [allowedKeys, setAllowedKeys] = useState<string[] | null>(null);
  const [values, setValues] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    personaApi
      .facts(storeId)
      .then((res) => {
        if (!alive) return;
        setAllowedKeys(res.allowedKeys);
        const next: Record<string, string> = {};
        for (const f of res.facts) next[f.key] = f.text;
        setValues(next);
      })
      .catch((e) => alive && setError(e instanceof ApiError ? e.message : "매장 정보를 불러오지 못했습니다."));
    return () => {
      alive = false;
    };
  }, [storeId]);

  const save = async () => {
    setSaving(true);
    setError(null);
    setMessage(null);
    try {
      const facts: StoreFactDto[] = Object.entries(values)
        .filter(([, text]) => text.trim().length > 0)
        .map(([key, text]) => ({ key, text: text.trim() }));
      const res = await personaApi.replaceFacts(storeId, facts);
      const next: Record<string, string> = {};
      for (const f of res.facts) next[f.key] = f.text;
      setValues(next);
      setMessage(
        facts.length === 0
          ? "저장했습니다. 지금은 답글에서 매장 정보를 안내하지 않습니다."
          : `저장했습니다. ${facts.length}개 항목이 관련 리뷰 답글에 안내됩니다.`,
      );
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "저장하지 못했습니다.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card className="persona-page__section">
      <h2>매장 정보</h2>
      <p className="field__hint">
        여기 적어 두시면 관련된 리뷰에 <strong>실제 안내</strong>가 나갑니다. 비워 두시면 답글이
        “확인해 보겠습니다”로만 끝납니다. 전부 선택 입력이고, 나중에 채우셔도 됩니다.
      </p>
      <p className="field__hint">
        ※ 여기 적은 내용은 손님에게 그대로 읽힙니다. <strong>확실한 것만</strong> 적어 주세요.
      </p>

      {allowedKeys === null ? <Skeleton height={220} /> : null}

      {/* ★ Field 를 쓴다 — label 을 placeholder 로 대체하지 않는 규약(요구사항 F2)을
          컴포넌트가 강제한다. 직접 마크업을 짜면 그 규약이 조용히 빠진다. */}
      {allowedKeys?.map((key) => (
        <Field
          key={key}
          label={key}
          type="text"
          maxLength={200}
          value={values[key] ?? ""}
          placeholder={PLACEHOLDER[key] ?? ""}
          hint={HINT[key]}
          onChange={(e) => setValues((v) => ({ ...v, [key]: e.target.value }))}
        />
      ))}

      {error ? (
        <p className="field__error" role="alert">
          {error}
        </p>
      ) : null}
      {message ? <p className="field__hint">{message}</p> : null}

      <Button onClick={save} disabled={saving || allowedKeys === null}>
        {saving ? "저장 중…" : "매장 정보 저장"}
      </Button>
    </Card>
  );
}
