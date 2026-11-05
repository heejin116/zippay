import { QueryCache, QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router'
import { authKeys } from './api/auth'
import { isApiError } from './api/client'
import './index.css'
import { router } from './router'

const queryClient = new QueryClient({
  queryCache: new QueryCache({
    // 쿠키 만료 등으로 아무 조회에서나 401이 나면 로그인 상태(/me)를 다시 확인 → RequireAuth가 /login으로 보낸다.
    // /me 자신은 제외해야 무한 루프가 생기지 않는다.
    onError: (error, query) => {
      if (isApiError(error, 401) && query.queryKey[0] !== authKeys.me[0]) {
        queryClient.invalidateQueries({ queryKey: authKeys.me })
      }
    },
  }),
  defaultOptions: {
    queries: {
      // 4xx는 다시 시도해도 결과가 같으므로 재시도하지 않는다
      retry: (failureCount, error) => !(isApiError(error) && error.status >= 400 && error.status < 500) && failureCount < 2,
      refetchOnWindowFocus: false,
    },
    mutations: {
      // 돈이 움직이는 요청은 자동 재시도 금지 — 재시도는 멱등성 키와 함께 명시적으로만
      retry: false,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)
