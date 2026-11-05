import { useState } from 'react'
import { Link } from 'react-router'
import { useOpenWallet, useWallets } from '../api/wallets'
import { Button, Card, ErrorMessage, Spinner } from '../components/ui'
import { CURRENCIES, CURRENCY_NAME, formatMoney, type Currency } from '../lib/money'

export function WalletsPage() {
  const wallets = useWallets()

  if (wallets.isPending) return <Spinner />
  if (wallets.isError) return <ErrorMessage error={wallets.error} />

  const opened = new Set(wallets.data.map((w) => w.currency))
  const available = CURRENCIES.filter((c) => !opened.has(c))

  return (
    <div className="space-y-8">
      <h2 className="text-xl font-semibold">내 지갑</h2>

      {wallets.data.length === 0 ? (
        <Card className="text-center text-sm text-neutral-500">아직 지갑이 없습니다. 아래에서 통화를 골라 개설하세요.</Card>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2">
          {wallets.data.map((w) => (
            <li key={w.id}>
              <Link to={`/wallets/${w.id}`} className="block rounded-lg border border-neutral-200 bg-white p-5 transition hover:border-neutral-400">
                <div className="flex items-baseline justify-between">
                  <span className="text-sm font-semibold">{w.currency}</span>
                  <span className="text-xs text-neutral-500">{CURRENCY_NAME[w.currency]}</span>
                </div>
                <div className="mt-3 text-right text-2xl font-semibold tabular-nums">{formatMoney(w.balance, w.currency)}</div>
              </Link>
            </li>
          ))}
        </ul>
      )}

      {available.length > 0 && <OpenWalletForm available={available} />}
    </div>
  )
}

function OpenWalletForm({ available }: { available: Currency[] }) {
  const [currency, setCurrency] = useState<Currency>(available[0])
  const open = useOpenWallet()

  // 개설 후 목록에서 빠진 통화가 선택돼 있으면 남은 첫 통화로 맞춘다
  const selected = available.includes(currency) ? currency : available[0]

  return (
    <Card>
      <h3 className="mb-3 text-sm font-semibold">지갑 개설</h3>
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault()
          open.mutate(selected)
        }}
      >
        <select
          value={selected}
          onChange={(e) => setCurrency(e.target.value as Currency)}
          className="h-10 flex-1 rounded-md border border-neutral-300 bg-white px-3 text-sm"
        >
          {available.map((c) => (
            <option key={c} value={c}>
              {c} · {CURRENCY_NAME[c]}
            </option>
          ))}
        </select>
        <Button type="submit" disabled={open.isPending}>
          {open.isPending ? '개설 중…' : '개설'}
        </Button>
      </form>
      <div className="mt-3">
        <ErrorMessage error={open.error} />
      </div>
    </Card>
  )
}
