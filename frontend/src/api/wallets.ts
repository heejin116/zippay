import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Currency } from '../lib/money'
import { api } from './client'

export type Wallet = {
  id: number
  currency: Currency
  /** 문자열 그대로 다룬다. Number로 바꾸면 큰 금액·소수에서 정밀도를 잃는다. */
  balance: string
}

export const walletKeys = {
  all: ['wallets'] as const,
  detail: (id: number) => ['wallets', id] as const,
}

export const walletApi = {
  list: () => api<Wallet[]>('/wallets'),
  get: (id: number) => api<Wallet>(`/wallets/${id}`),
  open: (currency: Currency) => api<Wallet>('/wallets', { method: 'POST', body: { currency } }),
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
