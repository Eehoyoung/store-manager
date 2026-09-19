import type { FieldParse, PageSpec, SelectorSpec } from "../types/index.ts";

/**
 * 손수 짠 런타임 검증기. ajv 등 외부 라이브러리를 쓰지 않는다 —
 * 확장 번들 예산(500KB)에 검증기 하나로 100KB+ 를 쓸 이유가 없다.
 *
 * 셀렉터 문자열을 실제 document.querySelector 로 실행해보는 검사는
 * 넣지 않는다 — Node(테스트) 환경에는 DOM 이 없다. 형태만 검증한다.
 */

export type ValidationResult =
  | { ok: true; spec: SelectorSpec }
  | { ok: false; errors: string[] };

/** 최상위 필수 키. schema.json 의 required 와 반드시 일치해야 한다 (테스트로 대조). */
export const ROOT_REQUIRED_KEYS = ["version", "minExtensionVersion", "pages"] as const;

/** PageSpec 필수 키. schema.json 의 $defs.pageSpec.required 와 반드시 일치해야 한다. */
export const PAGE_REQUIRED_KEYS = ["match", "container", "item", "fields"] as const;

const FIELD_PARSE_VALUES: readonly FieldParse[] = ["text", "int", "float", "exists"];

function isPlainObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function isNonBlankString(v: unknown): v is string {
  return typeof v === "string" && v.trim().length > 0;
}

function isUserKey(key: string): boolean {
  // "_note" 같은 언더스코어 키는 주석용으로 허용 — 검사 대상에서 제외한다.
  return !key.startsWith("_");
}

function validateFieldSpec(value: unknown, path: string, errors: string[]): void {
  if (!isPlainObject(value)) {
    errors.push(`${path}: 객체가 아닙니다`);
    return;
  }
  if (!isNonBlankString(value.selector)) {
    errors.push(`${path}.selector: 빈 문자열이거나 없습니다`);
  }
  if (value.attr !== undefined && !isNonBlankString(value.attr)) {
    errors.push(`${path}.attr: 빈 문자열이거나 문자열이 아닙니다`);
  }
  if (value.parse !== undefined && !FIELD_PARSE_VALUES.includes(value.parse as FieldParse)) {
    errors.push(`${path}.parse: 허용되지 않는 값`);
  }
}

function validatePageSpec(value: unknown, path: string, errors: string[]): void {
  if (!isPlainObject(value)) {
    errors.push(`${path}: 객체가 아닙니다`);
    return;
  }
  for (const key of PAGE_REQUIRED_KEYS) {
    if (!(key in value)) {
      errors.push(`${path}.${key}: 필수 항목이 없습니다`);
    }
  }
  for (const key of ["match", "container", "item"] as const) {
    if (key in value && !isNonBlankString(value[key])) {
      errors.push(`${path}.${key}: 빈 문자열이거나 문자열이 아닙니다`);
    }
  }
  if ("fields" in value) {
    const fields = value.fields;
    if (!isPlainObject(fields) || Object.keys(fields).filter(isUserKey).length === 0) {
      errors.push(`${path}.fields: 비어있지 않은 객체여야 합니다`);
    } else {
      for (const [fieldName, fieldSpec] of Object.entries(fields)) {
        if (!isUserKey(fieldName)) continue;
        validateFieldSpec(fieldSpec, `${path}.fields.${fieldName}`, errors);
      }
    }
  }
  for (const key of ["replyInput", "replyForm", "replyOpenButton", "replySubmitButton"] as const) {
    if (key in value && value[key] !== undefined && !isNonBlankString(value[key])) {
      errors.push(`${path}.${key}: 빈 문자열이거나 문자열이 아닙니다`);
    }
  }
}

export function validateSelectorSpec(input: unknown): ValidationResult {
  const errors: string[] = [];

  if (!isPlainObject(input)) {
    return { ok: false, errors: ["최상위: 객체가 아닙니다"] };
  }

  for (const key of ROOT_REQUIRED_KEYS) {
    if (!(key in input)) {
      errors.push(`${key}: 필수 항목이 없습니다`);
    }
  }

  if ("version" in input) {
    if (!isNonBlankString(input.version)) {
      errors.push("version: 빈 문자열이거나 문자열이 아닙니다");
    } else if (Number.isNaN(Date.parse(input.version))) {
      errors.push("version: 파싱 가능한 날짜가 아닙니다");
    }
  }

  if ("minExtensionVersion" in input) {
    if (!isNonBlankString(input.minExtensionVersion) || !/^\d+\.\d+\.\d+$/.test(input.minExtensionVersion)) {
      errors.push("minExtensionVersion: x.y.z 형식의 문자열이어야 합니다");
    }
  }

  if ("pages" in input) {
    const pages = input.pages;
    if (!isPlainObject(pages) || Object.keys(pages).filter(isUserKey).length === 0) {
      errors.push("pages: 비어있지 않은 객체여야 합니다");
    } else {
      for (const [pageName, pageSpec] of Object.entries(pages)) {
        if (!isUserKey(pageName)) continue;
        validatePageSpec(pageSpec, `pages.${pageName}`, errors);
      }
    }
  }

  if (errors.length > 0) {
    return { ok: false, errors };
  }
  return { ok: true, spec: input as unknown as SelectorSpec };
}

/**
 * spec.minExtensionVersion <= extensionVersion 인지 확인한다.
 * semver 패키지를 쓰지 않는다 — x.y.z 3분할 숫자 비교로 충분하다.
 */
export function isCompatible(spec: Pick<SelectorSpec, "minExtensionVersion">, extensionVersion: string): boolean {
  const required = spec.minExtensionVersion.split(".").map(Number);
  const actual = extensionVersion.split(".").map(Number);
  for (let i = 0; i < 3; i++) {
    const r = required[i] ?? 0;
    const a = actual[i] ?? 0;
    if (a > r) return true;
    if (a < r) return false;
  }
  return true; // 완전히 같음
}
