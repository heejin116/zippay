import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Currency } from '../lib/money'
import { api, apiRaw } from './client'

export type Wallet = {
  id: number
  currency: Currency
  /** 문자열 그대로 다룬다. Number로 바꾸면 큰 금액·소수에서 정밀도를 잃는다. */
  balance: string
}

export type Charge = {
  transactionId: number
  walletId: number
  currency: Currency
  amount: string
  /** 그 거래 직후 잔액. 재요청(replayed)이면 현재 잔액이 아니라 원래 거래 시점의 잔액이다. */
  balanceAfter: string
  createdAt: string
}

export type ChargeResult = Charge & { replayed: boolean }

export const walletKeys = {
  all: ['wallets'] as const,
  detail: (id: number) => ['wallets', id] as const,
}

export const walletApi = {
  list: () => api<Wallet[]>('/wallets'),
  get: (id: number) => api<Wallet>(`/wallets/${id}`),
  open: (currency: Currency) => api<Wallet>('/wallets', { method: 'POST', body: { currency } }),
  /** amount는 문자열 그대로 보낸다 (Number 변환 시 정밀도 손실) */
  charge: async (walletId: number, amount: string, idempotencyKey: string): Promise<ChargeResult> => {
    const res = await apiRaw<Charge>(`/wallets/${walletId}/charges`, {
      method: 'POST',
      body: { amount },
      headers: { 'Idempotency-Key': idempotencyKey },
    })
    return { ...res.data, replayed: res.headers.get('Idempotent-Replayed') === 'true' }
  },
}

export function useWallets() {
  return useQuery({ queryKey: walletKeys.all, queryFn: walletApi.list })
}

export function useWallet(id: number) {
  return useQuery({
    queryKey: walletKeys.detail(id),
    queryFn: () => walletApi.get(id),
    enabled: Number.isInteger(id) && id > 0,
  })
}

export function useOpenWallet() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: walletApi.open,
    // 잔액을 바꾸는 요청(충전·송금)도 같은 방식으로 지갑 쿼리를 무효화하면 화면이 갱신된다
    onSuccess: () => queryClient.invalidateQueries({ queryKey: walletKeys.all }),
  })
}

export function useCharge(walletId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ amount, idempotencyKey }: { amount: string; idempotencyKey: string }) =>
      walletApi.charge(walletId, amount, idempotencyKey),
    // ['wallets'] 접두사 무효화 → 목록과 상세가 함께 갱신된다
    onSuccess: () => queryClient.invalidateQueries({ queryKey: walletKeys.all }),
  })
}
