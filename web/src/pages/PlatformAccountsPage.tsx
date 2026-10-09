import { useEffect, useState, type ChangeEvent, type FormEvent } from "react";
import { platformAccountsApi, type RegisterPlatformAccountPayload } from "../api/platformAccounts";
import { storesApi } from "../api/stores";
import type { DeliveryPlatform, PlatformAccountResponse, StoreResponse } from "../api/types";
import { ApiError } from "../api/client";
import { Badge } from "../components/Badge";
import { Button } from "../components/Button";
import { Card } from "../components/Card";
import { EmptyState } from "../components/EmptyState";
import { Field } from "../components/Field";
import { Skeleton } from "../components/Skeleton";
import { Modal } from "../components/Modal";
import { BillingCheckout } from "../components/BillingCheckout";
import { agreementsApi } from "../api/agreements";
import { billingApi, isPaymentRequiredError, type BillingResponse } from "../api/billing";
import { Link } from "react-router-dom";

// 모바일 결제창은 페이지를 떠났다가 돌아온다 — 새로고침으로 사라지는 입력값 대신 어느 매장을
// 연동하려 했는지만 남겨서, 돌아왔을 때 결제 완료를 알리고 계정을 다시 입력하게 한다.
const PENDING_PAYMENT_STORE_KEY = "platform-account-payment-store";

const labels: Record<DeliveryPlatform, string> = { BAEMIN: "배민", YOGIYO: "요기요", COUPANGEATS: "쿠팡이츠" };

const LINK_BADGE: Record<string, { tone: "success" | "danger" | "warning"; icon: string; label: string }> = {
  LINKED: { tone: "success", icon: "✓", label: "연동 완료" },
  ERROR: { tone: "danger", icon: "!", label: "연동 오류" },
  PENDING: { tone: "warning", icon: "•", label: "검증 보류" },
  EXPIRED: { tone: "warning", icon: "•", label: "재연동 필요" },
  REVOKED: { tone: "warning", icon: "•", label: "해제됨" },
};

export function PlatformAccountsPage() {
  const [accounts, setAccounts] = useState<PlatformAccountResponse[] | null>(null);
  const [stores, setStores] = useState<StoreResponse[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    setError(null);
    Promise.all([platformAccountsApi.list(), storesApi.list()])
      .then(([nextAccounts, nextStores]) => {
        setAccounts(nextAccounts);
        setStores(nextStores);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : "배달앱 계정 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."));
  };

  useEffect(load, []);

  const onRegistered = (account: PlatformAccountResponse) => setAccounts((current) => [account, ...(current ?? [])]);
  const revoke = async (account: PlatformAccountResponse) => {
    if (!window.confirm(`${labels[account.platform]} 계정을 연동 해제하고 암호문을 파기할까요?`)) return;
    try {
      await platformAccountsApi.revoke(account.id);
      setAccounts((current) => (current ?? []).filter((item) => item.id !== account.id));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "연동 해제에 실패했어요. 잠시 후 다시 시도해 주세요.");
    }
  };

  return (
    <div className="platform-accounts-page">
      <div className="stores-page__header">
        <div>
          <h1>배달앱 계정 연동</h1>
          <p>배민·요기요·쿠팡이츠 계정과 내 매장을 등록해요. 비밀번호는 봉투암호화로만 저장해요.</p>
        </div>
      </div>
      <Card className="platform-accounts-page__notice">
        <Badge tone="warning" icon="⚠">매장 조회 대기</Badge>
        <p>등록 1건당 DataAPI 리뷰관리 조회 1회로 플랫폼 매장을 자동으로 찾아요.</p>
        <p>조회는 등록 직후가 아니라 <strong>수집 작업이 처음 도는 시점</strong>에 이뤄져요. 그전까지 등록 결과는 <strong>매장 조회 대기(PENDING)</strong>로 표시돼요.</p>
      </Card>
      {stores.length > 0 ? <PlatformAccountForm stores={stores} onRegistered={onRegistered} /> : null}
      {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
      {accounts === null && !error ? <Skeleton height={100} /> : null}
      {accounts && accounts.length === 0 ? (
        <EmptyState title="등록된 배달앱 계정이 없어요" description="계정을 등록하면 선택한 매장과 매핑돼요." />
      ) : null}
      {accounts && accounts.length > 0 ? (
        <ul className="platform-account-list">
          {accounts.map((account) => (
            <li key={account.id}>
              <Card>
                <div className="platform-account-card__head">
                  <div>
                    <h2>{labels[account.platform]}</h2>
                    <p>{account.maskedLoginId}</p>
                  </div>
                  {/* 상태는 3종이다. LINKED 를 '검증 보류' 로 묶어 버리면 연동이 끝났는지 알 수 없다. */}
                  <Badge
                    tone={LINK_BADGE[account.linkStatus]?.tone ?? "warning"}
                    icon={LINK_BADGE[account.linkStatus]?.icon ?? "•"}
                  >
                    {LINK_BADGE[account.linkStatus]?.label ?? "검증 보류"}
                  </Badge>
                </div>
                <p>{account.statusMessage}</p>
                <p className="platform-account-card__links">
                  {account.links.length
                    ? `플랫폼 매장 ${account.links.length}개 자동 매핑됨`
                    : "플랫폼 매장 조회 대기 중"}
                </p>
                <Button type="button" variant="danger" small onClick={() => void revoke(account)}>연동 해제</Button>
              </Card>
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  );
}

function PlatformAccountForm({ stores, onRegistered }: { stores: StoreResponse[]; onRegistered: (a: PlatformAccountResponse) => void }) {
  const [form, setForm] = useState<RegisterPlatformAccountPayload>({ platform: "BAEMIN", loginId: "", password: "", storeId: stores[0].id, agreedCredentialEntrust: false, docVersion: "" });
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  // 결제 안내 팝업. autoRetry 는 팝업을 이 세션에서 직접 열었을 때만 true — 모바일 리다이렉트로
  // 페이지가 새로고침되면 입력값과 함께 사라지므로, 그때는 자동 재시도 대신 안내만 한다.
  const [payment, setPayment] = useState<{ storeId: string; billing: BillingResponse | null; autoRetry: boolean } | null>(null);

  useEffect(() => { agreementsApi.catalog().then((c) => setForm((f) => ({ ...f, docVersion: c.currentVersion }))).catch(() => setError("동의 문서를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.")); }, []);

  // 모바일 결제창에서 돌아온 경우를 새로고침 직후에 감지한다.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (!params.get("billingKey") && !params.get("code")) return;
    const pendingStoreId = sessionStorage.getItem(PENDING_PAYMENT_STORE_KEY);
    if (!pendingStoreId) return;
    openPaymentModal(pendingStoreId, false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const openPaymentModal = (storeId: string, autoRetry: boolean) => {
    sessionStorage.setItem(PENDING_PAYMENT_STORE_KEY, storeId);
    setPayment({ storeId, billing: null, autoRetry });
    billingApi
      .get(storeId)
      .then((billing) => setPayment((current) => (current ? { ...current, billing } : current)))
      .catch(() => setError("결제 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."));
  };

  const closePaymentModal = () => {
    sessionStorage.removeItem(PENDING_PAYMENT_STORE_KEY);
    setPayment(null);
    // 절대규칙 5 — 결제 팝업을 그냥 닫으면 입력해 둔 배달앱 아이디·비밀번호를 지운다.
    setForm((f) => ({ ...f, loginId: "", password: "" }));
  };

  const update = (key: keyof RegisterPlatformAccountPayload) => (e: ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
    setForm((current) => ({ ...current, [key]: e.target.value }));

  const register = async (payload: RegisterPlatformAccountPayload) => {
    setLoading(true);
    setError(null);
    try {
      const account = await platformAccountsApi.register({ ...payload, loginId: payload.loginId.trim() });
      onRegistered(account);
      setForm((current) => ({ ...current, loginId: "", password: "", agreedCredentialEntrust: false }));
      return true;
    } catch (e) {
      if (isPaymentRequiredError(e)) {
        openPaymentModal(payload.storeId, true);
        return false;
      }
      setError(e instanceof ApiError ? e.message : "계정 등록에 실패했어요. 잠시 후 다시 시도해 주세요.");
      return false;
    } finally {
      setLoading(false);
    }
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    setNotice(null);
    if (!form.loginId.trim() || !form.password) return setError("아이디와 비밀번호를 입력해 주세요.");
    if (!form.agreedCredentialEntrust) return setError("배달앱 로그인 정보 처리 위탁에 동의해 주세요.");
    await register(form);
  };

  const handlePaid = () => {
    const retry = payment?.autoRetry && form.loginId.trim() && form.password ? { ...form } : null;
    sessionStorage.removeItem(PENDING_PAYMENT_STORE_KEY);
    setPayment(null);
    if (retry) {
      void register(retry);
    } else {
      setForm((f) => ({ ...f, loginId: "", password: "" }));
      setNotice("결제가 완료됐어요. 계정을 다시 입력해 연동해 주세요.");
    }
  };

  return (
    <Card className="platform-account-form">
      <h2>계정 등록</h2>
      <form onSubmit={submit} noValidate>
        <section className="consent-box">
          <details className="consent-fold">
          <summary>리뷰를 가져오고 답글을 올리려고 배달앱 로그인 정보를 암호화해 보관합니다</summary>
          <p>사장님을 대신해 배달앱에 들어가서 <strong>리뷰를 가져오고 답글을 올리려면</strong> 배달앱 로그인 정보가 필요합니다.</p>
          <div className="consent-table-wrap"><table><tbody><tr><th>저장 방식</th><td>비밀번호는 암호로 잠가서 저장합니다. 저희 직원도 그냥은 볼 수 없습니다</td></tr><tr><th>기록에 남나요</th><td>비밀번호는 어떤 기록에도 그대로 남기지 않습니다</td></tr><tr><th>언제 쓰나요</th><td>리뷰를 가져올 때와 답글을 올릴 때만 씁니다</td></tr><tr><th>쓸 때마다</th><td>누가 언제 썼는지 기록이 남습니다</td></tr><tr><th>지우고 싶으면</th><td>연동을 해제하시면 바로 지웁니다</td></tr></tbody></table></div>
          </details>
          <label className="consent-check"><input type="checkbox" checked={form.agreedCredentialEntrust} onChange={(e) => setForm((f) => ({ ...f, agreedCredentialEntrust: e.target.checked }))} /> (필수) 배달앱 로그인 정보의 처리 위탁에 동의합니다</label>
          <details className="consent-fold">
          <summary>맡기는 곳: 기웅정보통신(주) — 배달앱 접속·답글 등록 대행</summary>
          <p>배달앱 접속과 답글 등록은 저희가 직접 하지 않고 전문 업체가 대신합니다.</p>
          <ul><li>맡기는 곳: 기웅정보통신(주)</li><li>맡기는 일: 배달앱에 접속해 리뷰를 가져오고 답글을 등록하는 일</li><li>넘기는 정보: 배달앱 로그인 아이디와 비밀번호, 조회할 기간, 올릴 답글 내용</li></ul>
          <p>저희는 이 업체가 정보를 안전하게 다루도록 계약으로 정하고 관리합니다. <Link to="/legal/platform-credential" target="_blank">전문 보기</Link></p>
          </details>
        </section>
        <section className="service-warning"><details className="consent-fold"><summary>⚠️ 본인 매장 계정만 넣어 주세요 · 비밀번호를 바꾸면 여기서도 바꿔 주세요</summary><ul><li><strong>사장님이 직접 운영하시는 매장의 계정만</strong> 넣어 주세요. 다른 사람 계정을 넣으시면 안 됩니다.</li><li>배달앱에서 비밀번호를 바꾸시면 <strong>여기서도 꼭 바꿔 주세요.</strong> 그렇지 않으면 리뷰를 가져오지 못합니다.</li><li>배달앱 회사의 정책에 따라 이런 자동 프로그램 사용이 제한될 수 있습니다. 사장님께서 이용 중인 배달앱의 약관을 확인해 주세요.</li></ul></details></section>
        <div className="platform-account-form__grid">
          <div className="field"><label className="field__label" htmlFor="platform">플랫폼</label><select id="platform" className="field__input field__select" value={form.platform} onChange={update("platform")}><option value="BAEMIN">배민</option><option value="YOGIYO">요기요</option><option value="COUPANGEATS">쿠팡이츠</option></select></div>
          <div className="field"><label className="field__label" htmlFor="storeId">매장</label><select id="storeId" className="field__input field__select" value={form.storeId} onChange={update("storeId")}>{stores.map((store) => <option key={store.id} value={store.id}>{store.name}</option>)}</select></div>
        </div>
        <Field label="배달앱 아이디" required value={form.loginId} onChange={update("loginId")} autoComplete="username" />
        <Field label="배달앱 비밀번호" required type="password" value={form.password} onChange={update("password")} autoComplete="current-password" hint="화면에 다시 표시하지 않으며 서버에서 봉투암호화해요." />
        {error ? <p className="auth-card__error" role="alert">{error}</p> : null}
        {notice ? <p role="status" className="field__hint">{notice}</p> : null}
        <Button type="submit" loading={loading} disabled={!form.docVersion}>암호화 저장 및 플랫폼 매장 조회 대기</Button>
      </form>

      <Modal open={payment !== null} title="결제수단을 먼저 등록해 주세요" onClose={closePaymentModal}>
        <p>이 매장은 결제수단을 등록해야 배달앱 계정을 연동할 수 있어요.</p>
        {payment?.billing ? (
          <BillingCheckout storeId={payment.storeId} billing={payment.billing} onSuccess={handlePaid} />
        ) : (
          <Skeleton height={160} />
        )}
      </Modal>
    </Card>
  );
}
