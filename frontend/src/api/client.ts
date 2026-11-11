/**
 * 백엔드 호출의 단일 진입점.
 * - 모든 경로는 `/api` 기준 상대경로 → dev에선 Vite 프록시, 배포에선 같은 오리진 리버스 프록시가 받는다.
 * - 인증은 HttpOnly 쿠키(access_token)라 JS가 토큰을 만지지 않는다. 브라우저가 알아서 붙인다.
 * - 실패 응답은 백엔드 ApiError({status, code, message})를 ApiError 예외로 바꿔 던진다.
 */

export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export function isApiError(e: unknown, status?: number): e is ApiError {
  return e instanceof ApiError && (status === undefined || e.status === status)
}

type RequestOptions = {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  headers?: Record<string, string>
}

export type ApiResponse<T> = {
  data: T
  status: number
  headers: Headers
}

// 본문 없는 에러(예: Spring Security가 직접 내리는 401)용 기본 메시지
const FALLBACK_MESSAGE: Record<number, string> = {
  401: '로그인이 필요합니다.',
  403: '권한이 없습니다.',
  404: '찾을 수 없습니다.',
  500: '서버 오류가 발생했습니다.',
}

function parseJson(text: string): unknown {
  if (!text) return undefined
  try {
    return JSON.parse(text)
  } catch {
    return undefined
  }
}

/** 응답 헤더까지 필요할 때 (예: STEP 5의 Idempotent-Replayed) */
export async function apiRaw<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const { method = 'GET', body, headers = {} } = options

  let res: Response
  try {
    res = await fetch(`/api${path}`, {
      method,
      headers: body === undefined ? headers : { 'Content-Type': 'application/json', ...headers },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인하세요.')
  }

  const json = parseJson(await res.text())

  // 502/503/504: dev에선 Vite 프록시, 배포에선 리버스 프록시가 백엔드에 닿지 못한 경우 → 네트워크 오류와 같게 안내
  if (res.status === 502 || res.status === 503 || res.status === 504) {
    throw new ApiError(res.status, 'BACKEND_UNAVAILABLE', '서버에 연결할 수 없습니다. 백엔드가 실행 중인지 확인하세요.')
  }

  if (!res.ok) {
    const err = json as { code?: string; message?: string } | undefined
    throw new ApiError(
      res.status,
      err?.code ?? 'UNKNOWN',
      err?.message ?? FALLBACK_MESSAGE[res.status] ?? `요청에 실패했습니다. (${res.status})`,
    )
  }

  return { data: json as T, status: res.status, headers: res.headers }
}

export async function api<T>(path: string, options?: RequestOptions): Promise<T> {
  return (await apiRaw<T>(path, options)).data
}
