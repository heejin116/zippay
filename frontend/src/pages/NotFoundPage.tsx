import { Link } from 'react-router'

export function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-3">
      <p className="text-sm text-neutral-500">페이지를 찾을 수 없습니다.</p>
      <Link to="/" className="text-sm font-medium underline underline-offset-4">
        처음으로
      </Link>
    </div>
  )
}
