import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { authKeys, loginAndVerify } from '../api/auth'
import { Button, Card, ErrorMessage, Field } from '../components/ui'

export function LoginPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const queryClient = useQueryClient()

  const login = useMutation({
    mutationFn: loginAndVerify,
    // 확인된 /me를 캐시에 넣으면 GuestOnly가 원래 가려던 곳으로 보낸다.
    // (resetQueries를 쓰면 /me가 pending으로 돌아가 이 페이지가 스피너로 언마운트되고, 실패 메시지도 사라진다)
    onSuccess: (me) => queryClient.setQueryData(authKeys.me, me),
  })

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    login.mutate({ email, password })
  }

  return (
    <Card>
      <form onSubmit={onSubmit} className="space-y-4">
        <Field id="email" label="이메일" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field id="password" label="비밀번호" type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        <ErrorMessage error={login.error} />
        <Button type="submit" className="w-full" disabled={login.isPending}>
          {login.isPending ? '로그인 중…' : '로그인'}
        </Button>
      </form>
      <p className="mt-4 text-center text-sm text-neutral-500">
        계정이 없나요?{' '}
        <Link to="/signup" className="font-medium text-neutral-900 underline underline-offset-4">
          회원가입
        </Link>
      </p>
    </Card>
  )
}
