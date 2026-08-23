import { useEffect, useId, useRef, useState } from "react";
import { Button } from "./Button";

/**
 * 매장 주소 입력 — 카카오 우편번호 서비스.
 *
 * ★ 팝업(open)이 아니라 레이어(embed)로 띄운다. 모바일 웹에서 window.open 은 차단되거나
 *   새 탭으로 열려 사장님이 원래 화면으로 못 돌아온다. 카카오 문서도 웹뷰 환경에는 레이어를 권한다.
 * ★ 스크립트는 이 화면에 들어왔을 때만 받는다. 로그인 화면까지 지도 스크립트를 지고 갈 이유가 없다.
 * ★ 검색 결과는 도로명 주소로 채우고, 상세주소는 사람이 직접 넣는다 — 층·호수는 검색으로 못 찾는다.
 */

const SCRIPT_SRC = "https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js";

declare global {
  interface Window {
    daum?: {
      Postcode: new (options: {
        oncomplete: (data: { roadAddress: string; jibunAddress: string; zonecode: string }) => void;
        onresize?: (size: { height: number }) => void;
        onclose?: () => void;
        width?: string;
        height?: string;
      }) => { embed: (el: HTMLElement) => void };
    };
  }
}

function loadScript(): Promise<void> {
  if (window.daum?.Postcode) return Promise.resolve();
  const existing = document.querySelector<HTMLScriptElement>(`script[src="${SCRIPT_SRC}"]`);
  if (existing) {
    return new Promise((resolve, reject) => {
      existing.addEventListener("load", () => resolve());
      existing.addEventListener("error", () => reject(new Error("load failed")));
    });
  }
  return new Promise((resolve, reject) => {
    const el = document.createElement("script");
    el.src = SCRIPT_SRC;
    el.async = true;
    el.onload = () => resolve();
    el.onerror = () => reject(new Error("load failed"));
    document.head.appendChild(el);
  });
}

interface Props {
  label: string;
  /** 도로명 주소 + 상세주소를 합친 최종 문자열 */
  value: string;
  onChange: (next: string) => void;
  required?: boolean;
  error?: string;
}

export function AddressField({ label, value, onChange, required, error }: Props) {
  const [base, setBase] = useState("");
  const [detail, setDetail] = useState("");
  const [open, setOpen] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const layerRef = useRef<HTMLDivElement>(null);
  const baseId = useId();

  // 바깥에서 넘어온 값(수정 화면 등)을 한 번 갈라 놓는다. 검색으로 채운 뒤에는 건드리지 않는다.
  useEffect(() => {
    if (!base && !detail && value) setBase(value);
  }, [value]); // eslint-disable-line react-hooks/exhaustive-deps

  const push = (nextBase: string, nextDetail: string) => {
    setBase(nextBase);
    setDetail(nextDetail);
    onChange([nextBase, nextDetail].filter((s) => s.trim()).join(" ").trim());
  };

  const search = async () => {
    setLoadError(null);
    try {
      await loadScript();
    } catch {
      // 스크립트를 못 받아도 주소 입력 자체는 막지 않는다 — 직접 칠 수 있어야 한다.
      setLoadError("주소 검색을 열지 못했습니다. 아래 칸에 주소를 직접 입력해 주세요.");
      return;
    }
    setOpen(true);
    // 레이어 DOM 이 그려진 뒤에 붙인다.
    requestAnimationFrame(() => {
      if (!layerRef.current || !window.daum) return;
      layerRef.current.innerHTML = "";
      new window.daum.Postcode({
        width: "100%",
        height: "100%",
        oncomplete: (data) => {
          push(data.roadAddress || data.jibunAddress, detail);
          setOpen(false);
        },
        onclose: () => setOpen(false),
      }).embed(layerRef.current);
    });
  };

  return (
    <div className="field address-field">
      <label htmlFor={baseId} className="field__label">
        {label}
      </label>
      <p className="field__hint">주소 검색으로 도로명 주소를 채운 뒤, 층·호수는 상세주소에 적어 주세요.</p>

      <div className="address-field__row">
        <input
          id={baseId}
          className={["field__input", error ? "field__input--error" : ""].filter(Boolean).join(" ")}
          value={base}
          required={required}
          placeholder="도로명 주소"
          onChange={(e) => push(e.target.value, detail)}
          aria-invalid={error ? true : undefined}
        />
        <Button type="button" variant="secondary" onClick={() => void search()}>
          주소 검색
        </Button>
      </div>

      <input
        className="field__input address-field__detail"
        value={detail}
        placeholder="상세주소 (층·호수)"
        aria-label={`${label} 상세주소`}
        onChange={(e) => push(base, e.target.value)}
      />

      {open ? (
        <div className="address-field__layer">
          <div className="address-field__layer-head">
            <span>주소 검색</span>
            <button type="button" className="address-field__close" onClick={() => setOpen(false)}>
              닫기
            </button>
          </div>
          <div ref={layerRef} className="address-field__embed" />
        </div>
      ) : null}

      {loadError ? (
        <p className="field__error" role="alert">
          {loadError}
        </p>
      ) : null}
      {error ? (
        <p className="field__error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
