import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { authApi, authKeys, loginAndVerify, type Credentials } from '../api/auth'
import { Button, Card, ErrorMessage, Field } from '../components/ui'

export function SignupPage() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const queryClient = useQueryClient()

  const signup = useMutation({
    // 가입 API는 쿠키를 주지 않으므로 바로 로그인까지 이어서 호출한다
    mutationFn: async (body: Credentials) => {
      await authApi.signup(body)
      return loginAndVerify(body)
    },
    // 확인된 /me를 캐시에 넣으면 GuestOnly가 /wallets로 보낸다
    onSuccess: (me) => queryClient.setQueryData(authKeys.me, me),
  })

  const onSubmit = (e: FormEvent) => {
    e.preventDefault()
    signup.mutate({ email, password })
  }

  return (
    <Card>
      <form onSubmit={onSubmit} className="space-y-4">
        <Field id="email" label="이메일" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        <Field
          id="password"
          label="비밀번호"
          type="password"
          autoComplete="new-password"
          required
          minLength={8}
          maxLength={64}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
        />
        <p className="text-xs text-neutral-500">8~64자, 공백 없이 영문·숫자·특수문자</p>
        <ErrorMessage error={signup.error} />
        <Button type="submit" className="w-full" disabled={signup.isPending}>
          {signup.isPending ? '가입 중…' : '회원가입'}
        </Button>
      </form>
      <p className="mt-4 text-center text-sm text-neutral-500">
        이미 계정이 있나요?{' '}
        <Link to="/login" className="font-medium text-neutral-900 underline underline-offset-4">
          로그인
        </Link>
      </p>
    </Card>
  )
}
