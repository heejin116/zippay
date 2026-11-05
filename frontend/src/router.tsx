import { createBrowserRouter, Navigate } from 'react-router'
import { GuestOnly, RequireAuth } from './auth/guards'
import { AppLayout, AuthLayout } from './components/Layout'
import { LoginPage } from './pages/LoginPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { SignupPage } from './pages/SignupPage'
import { WalletDetailPage } from './pages/WalletDetailPage'
import { WalletsPage } from './pages/WalletsPage'

/**
 * 라우트 트리
 *   /login, /signup           ← GuestOnly (로그인 상태면 /wallets로)
 *   /wallets, /wallets/:id    ← RequireAuth (비로그인이면 /login으로)
 * 새 화면(충전·송금 등)은 RequireAuth > AppLayout 아래 children에 추가한다.
 */
export const router = createBrowserRouter([
  { path: '/', element: <Navigate to="/wallets" replace /> },
  {
    element: <GuestOnly />,
    children: [
      {
        element: <AuthLayout />,
        children: [
          { path: '/login', element: <LoginPage /> },
          { path: '/signup', element: <SignupPage /> },
        ],
      },
    ],
  },
  {
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { path: '/wallets', element: <WalletsPage /> },
          { path: '/wallets/:walletId', element: <WalletDetailPage /> },
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
])
