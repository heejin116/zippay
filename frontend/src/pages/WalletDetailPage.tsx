import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { isApiError } from '../api/client'
import { useCharge, useWallet, type Wallet } from '../api/wallets'
import { Button, Card, ErrorMessage, Field, Spinner } from '../components/ui'
import { CURRENCY_NAME, formatMoney } from '../lib/money'

export function WalletDetailPage() {
  const walletId = Number(useParams().walletId)
  const wallet = useWallet(walletId)

  if (!Number.isInteger(walletId) || walletId <= 0 || isApiError(wallet.error, 404)) {
    // 남의 지갑도 서버가 404로 응답한다 (존재 여부 비노출)
    return <NotFound />
  }
  if (wallet.isPending) return <Spinner />
  if (wallet.isError) return <ErrorMessage error={wallet.error} />

  const w = wallet.data

  return (
    <div className="space-y-6">
      <Link to="/wallets" className="text-sm text-neutral-500 hover:text-neutral-900">
        ← 지갑 목록
      </Link>

      <Card>
        <div className="text-sm text-neutral-500">
          {w.currency} · {CURRENCY_NAME[w.currency]} 지갑
        </div>
        <div className="mt-2 text-4xl font-semibold tabular-nums">{formatMoney(w.balance, w.currency)}</div>
      </Card>

      <ChargeForm wallet={w} />

      <section>
        <h3 className="mb-3 text-sm font-semibold">거래 내역</h3>
        {/* TODO: 원장 조회 API(GET /api/wallets/{id}/entries 등)가 생기면 분개 테이블(일시·유형·금액·balance_after)로 교체 */}
        <Card className="text-center text-sm text-neutral-500">거래 내역이 없습니다.</Card>
      </section>
    </div>
  )
}

/**
 * Idempotency-Key는 "충전하려는 의도 1번"당 1개.
 * - 금액을 바꾸면 새 의도 → 새 키
 * - 성공하면 다음 충전을 위해 새 키
 * - 실패(네트워크 오류 등) 후 같은 금액으로 다시 누르면 같은 키 → 서버가 이미 처리했다면 중복 반영 없이 원래 결과를 돌려준다
 * 버튼을 누를 때마다 새 키를 만들면 이중 클릭·재시도에서 멱등성이 의미를 잃는다.
 */
function ChargeForm({ wallet }: { wallet: Wallet }) {
  const [amount, setAmount] = useState('')
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID())
  const charge = useCharge(wallet.id)

  return (
    <Card>
      <h3 className="mb-3 text-sm font-semibold">충전</h3>
      <form
        className="flex items-end gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          charge.mutate(
            { amount: amount.trim(), idempotencyKey },
            {
              onSuccess: () => {
                setAmount('')
                setIdempotencyKey(crypto.randomUUID())
              },
            },
          )
        }}
      >
        <div className="flex-1">
          <Field
            id="charge-amount"
            label={`금액 (${wallet.currency})`}
            inputMode="decimal"
            autoComplete="off"
            placeholder={wallet.currency === 'USD' ? '10.00' : '10000'}
            value={amount}
            onChange={(e) => {
              setAmount(e.target.value)
              setIdempotencyKey(crypto.randomUUID())
              charge.reset()
            }}
          />
        </div>
        <Button type="submit" disabled={charge.isPending || amount.trim() === ''}>
          {charge.isPending ? '충전 중…' : '충전'}
        </Button>
      </form>
      <div className="mt-3 space-y-2">
        <ErrorMessage error={charge.error} />
        {charge.data && (
          <p className="rounded-md border border-green-200 bg-green-50 px-3 py-2 text-sm text-green-800">
            {formatMoney(charge.data.amount, charge.data.currency)} 충전 완료 · 거래 후 잔액{' '}
            {formatMoney(charge.data.balanceAfter, charge.data.currency)}
            {charge.data.replayed && ' (이미 처리된 요청이라 원래 결과를 표시합니다)'}
          </p>
        )}
      </div>
    </Card>
  )
}

function NotFound() {
  return (
    <Card className="text-center">
      <p className="text-sm text-neutral-500">지갑을 찾을 수 없습니다.</p>
      <Link to="/wallets" className="mt-3 inline-block text-sm font-medium underline underline-offset-4">
        지갑 목록으로
      </Link>
    </Card>
  )
}
