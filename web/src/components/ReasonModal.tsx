import { useState } from "react";
import { Button } from "./Button";
import { Modal } from "./Modal";

interface ReasonModalProps {
  open: boolean;
  title: string;
  /** 이 변경이 무엇을 의미하는지 — 근거 없이 누르는 변경은 감사기록이라 부를 수 없다(docs/26 §5.3). */
  description: string;
  confirmLabel?: string;
  danger?: boolean;
  onCancel: () => void;
  onConfirm: (reason: string) => Promise<void> | void;
}

/**
 * 시스템 콘솔의 모든 변경은 사유 입력이 필수다(docs/26 §5.3, §5.5 — "변경 사유 필수 입력").
 * 본부 생성·중지, 담당자 변경, 가맹코드 교체, 소속 승인·거절·해제까지 전부 이 컴포넌트로 통일한다.
 */
export function ReasonModal({ open, title, description, confirmLabel = "확인", danger, onCancel, onConfirm }: ReasonModalProps) {
  const [reason, setReason] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const close = () => {
    setReason("");
    setError(null);
    onCancel();
  };

  const confirm = async () => {
    if (!reason.trim()) {
      setError("변경 사유를 입력해 주세요.");
      return;
    }
    setError(null);
    setLoading(true);
    try {
      await onConfirm(reason.trim());
      setReason("");
    } catch {
      // 실패 메시지는 호출한 화면이 자신의 상태로 처리한다 — 여기서는 로딩만 풀어 재시도할 수 있게 한다.
    } finally {
      setLoading(false);
    }
  };

  return (
    <Modal
      open={open}
      title={title}
      onClose={close}
      footer={
        <>
          <Button type="button" variant="secondary" onClick={close} disabled={loading}>
            취소
          </Button>
          <Button type="button" variant={danger ? "danger" : "primary"} loading={loading} onClick={() => void confirm()}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <p className="reason-modal__desc">{description}</p>
      <div className="field">
        <label htmlFor="reason-modal-input" className="field__label">
          변경 사유
        </label>
        <textarea
          id="reason-modal-input"
          className="field__input reason-modal__textarea"
          value={reason}
          maxLength={300}
          onChange={(e) => setReason(e.target.value)}
          placeholder="나중에 확인할 수 있는 구체적인 사유를 적어 주세요."
        />
        {error ? (
          <p className="field__error" role="alert">
            {error}
          </p>
        ) : null}
      </div>
    </Modal>
  );
}
