export type { FieldParse, FieldSpec, PageSpec, SelectorSpec } from "./types/index.ts";
export {
  validateSelectorSpec,
  isCompatible,
  ROOT_REQUIRED_KEYS,
  PAGE_REQUIRED_KEYS,
} from "./validator/index.ts";
export type { ValidationResult } from "./validator/index.ts";
