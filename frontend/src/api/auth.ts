import { useQuery } from '@tanstack/react-query'
import { api, ApiError, isApiError } from './client'

export type Me = {
  userId: number
  role: string
}

export type Credentials = {
  email: string
  password: string
}

export const authKeys = {
  me: ['me'] as const,
}

export const authApi = {
  signup: (body: Credentials) => api<void>('/auth/signup', { method: 'POST', body }),
  // 성공 시 204 + Set-Cookie(access_token, HttpOnly). 응답 본문은 없다.
  login: (body: Credentials) => api<void>('/auth/login', { method: 'POST', body }),
  me: () => api<Me>('/users/me'),
  // 204 + 쿠키 삭제(Set-Cookie Max-Age=0)
  logout: () => api<void>('/auth/logout', { method: 'POST' }),
}

/**
 * 로그인 → 쿠키가 실제로 붙는지 /me로 확인.
 * 로그인은 204로 성공했는데 /me가 401이면 브라우저가 쿠키를 저장·전송하지 않은 것이다
 * (예: Secure 쿠키를 받아주지 않는 브라우저, localhost가 아닌 주소로 접속).
 * 이 경우를 조용히 넘기지 않고 에러로 드러낸다.
 */
export async function loginAndVerify(body: Credentials): Promise<Me> {
  await authApi.login(body)
  try {
    return await authApi.me()
  } catch (e) {
    if (isApiError(e, 401)) {
      throw new ApiError(
        401,
        'COOKIE_NOT_SENT',
        '로그인은 성공했지만 인증 쿠키가 전송되지 않았습니다. 크롬에서 http://localhost:5173 으로 접속했는지 확인하세요.',
      )
    }
    throw e
  }
}

/** 로그인 여부의 단일 출처. 성공 = 로그인, 401 = 비로그인 */
export function useMe() {
  return useQuery({
    queryKey: authKeys.me,
    queryFn: authApi.me,
    staleTime: 60_000,
  })
}
