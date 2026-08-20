import { useEffect, useId, useRef, type MouseEvent, type ReactNode } from "react";

interface ModalProps {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}

// 네이티브 <dialog> 를 쓴다 — 포커스 트랩·ESC 닫기·backdrop 을 브라우저가 대신 해준다(rung 4: 플랫폼 기본기능).
export function Modal({ open, title, onClose, children, footer }: ModalProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    if (!open && dialog.open) dialog.close();
  }, [open]);

  const handleBackdropClick = (e: MouseEvent<HTMLDialogElement>) => {
    if (e.target === ref.current) onClose();
  };

  return (
    <dialog ref={ref} className="modal" onClose={onClose} onCancel={onClose} onClick={handleBackdropClick} aria-labelledby={titleId}>
      <h2 id={titleId} className="modal__title">
        {title}
      </h2>
      <div className="modal__body">{children}</div>
      {footer ? <div className="modal__footer">{footer}</div> : null}
    </dialog>
  );
}
