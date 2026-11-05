/** 백엔드 Currency enum과 같은 목록을 유지한다 */
export const CURRENCIES = ['KRW', 'USD', 'JPY'] as const
export type Currency = (typeof CURRENCIES)[number]

const SYMBOL: Record<Currency, string> = { KRW: '₩', USD: '$', JPY: '¥' }

export const CURRENCY_NAME: Record<Currency, string> = {
  KRW: '원화',
  USD: '미국 달러',
  JPY: '일본 엔',
}

/**
 * "1234567.50" → "1,234,567.50"
 * 숫자로 변환하지 않고 문자열에서 정수부에만 콤마를 넣는다. 소수 자릿수는 서버가 맞춰 준 그대로 둔다.
 */
export function formatAmount(amount: string): string {
  const negative = amount.startsWith('-')
  const [int, frac] = (negative ? amount.slice(1) : amount).split('.')
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (negative ? '-' : '') + grouped + (frac === undefined ? '' : `.${frac}`)
}

export function formatMoney(amount: string, currency: Currency): string {
  const formatted = formatAmount(amount)
  return formatted.startsWith('-')
    ? `-${SYMBOL[currency]}${formatted.slice(1)}`
    : `${SYMBOL[currency]}${formatted}`
}
