import { Link, useParams } from 'react-router'
import { isApiError } from '../api/client'
import { useWallet } from '../api/wallets'
import { Card, ErrorMessage, Spinner } from '../components/ui'
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

      <section>
        <h3 className="mb-3 text-sm font-semibold">거래 내역</h3>
        {/* TODO: 원장 조회 API(GET /api/wallets/{id}/entries 등)가 생기면 분개 테이블(일시·유형·금액·balance_after)로 교체 */}
        <Card className="text-center text-sm text-neutral-500">거래 내역이 없습니다.</Card>
      </section>
    </div>
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
