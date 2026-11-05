import { Navigate, Outlet, useLocation } from 'react-router'
import { useMe } from '../api/auth'
import { isApiError } from '../api/client'
import { ErrorMessage, Spinner } from '../components/ui'

/** 로그인해야 들어갈 수 있는 라우트. 401이면 /login으로 보내고, 원래 가려던 경로를 state로 넘긴다. */
export function RequireAuth() {
  const me = useMe()
  const location = useLocation()

  if (me.isPending) return <Spinner />
  if (isApiError(me.error, 401)) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />
  }
  if (me.isError) {
    return (
      <div className="mx-auto max-w-sm py-16">
        <ErrorMessage error={me.error} />
      </div>
    )
  }
  return <Outlet />
}

/**
 * 이미 로그인한 사용자가 /login, /signup에 오면 원래 가려던 곳(없으면 지갑 화면)으로 보낸다.
 * 로그인 성공 후 이동도 여기서 처리한다 — 페이지에서 navigate()를 따로 부르면 이 리다이렉트와 경합해 from을 잃는다.
 */
export function GuestOnly() {
  const me = useMe()
  const location = useLocation()

  if (me.isPending) return <Spinner />
  if (me.isSuccess) {
    const from = (location.state as { from?: string } | null)?.from ?? '/wallets'
    return <Navigate to={from} replace />
  }
  return <Outlet />
}
