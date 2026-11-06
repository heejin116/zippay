import { useMutation } from '@tanstack/react-query'
import { Link, NavLink, Outlet } from 'react-router'
import { authApi, useMe } from '../api/auth'

export function AppLayout() {
  const { data: me } = useMe()
  const logout = useMutation({
    mutationFn: authApi.logout,
    // SPA 내 이동 대신 전체 새로고침: 이전 사용자의 지갑·잔액 캐시가 메모리에 남지 않게 한 번에 비운다
    onSuccess: () => window.location.assign('/login'),
  })

  return (
    <div className="min-h-screen">
      <header className="border-b border-neutral-200 bg-white">
        <div className="mx-auto flex h-14 max-w-3xl items-center gap-6 px-4">
          <Link to="/wallets" className="text-lg font-bold tracking-tight">
            ZipPay
          </Link>
          <nav className="flex gap-4 text-sm">
            <NavLink
              to="/wallets"
              className={({ isActive }) => (isActive ? 'font-semibold text-neutral-900' : 'text-neutral-500 hover:text-neutral-900')}
            >
              지갑
            </NavLink>
          </nav>
          <div className="ml-auto flex items-center gap-3">
            {me && <span className="text-xs text-neutral-500">사용자 #{me.userId}</span>}
            <button
              type="button"
              onClick={() => logout.mutate()}
              disabled={logout.isPending}
              className="text-xs text-neutral-500 underline-offset-4 hover:text-neutral-900 hover:underline disabled:opacity-50"
            >
              로그아웃
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-3xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}

/** 로그인·회원가입처럼 헤더 없이 가운데 카드 하나만 있는 화면 */
export function AuthLayout() {
  return (
    <div className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <h1 className="mb-8 text-center text-2xl font-bold tracking-tight">ZipPay</h1>
        <Outlet />
      </div>
    </div>
  )
}
