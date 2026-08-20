import { useId, type SelectHTMLAttributes } from "react";

interface SelectProps extends SelectHTMLAttributes<HTMLSelectElement> {
  label: string;
  error?: string;
}

export function Select({ label, error, id, className, children, ...rest }: SelectProps) {
  const autoId = useId();
  const fieldId = id ?? autoId;

  return (
    <div className="field">
      <label htmlFor={fieldId} className="field__label">
        {label}
      </label>
      <select
        id={fieldId}
        className={["field__input", "field__select", className].filter(Boolean).join(" ")}
        aria-invalid={error ? true : undefined}
        {...rest}
      >
        {children}
      </select>
      {error ? (
        <p className="field__error" role="alert">
          {error}
        </p>
      ) : null}
    </div>
  );
}
