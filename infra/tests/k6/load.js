// zippay 부하 테스트 (k6) — 노션 2장 (11/20 기준선 측정 후 조정)
//   수요 50 RPS · 클라이언트 p95 ≤ 300ms · 서버 구간(lb rt) p95 ≤ 100ms(summarize.py가 판정)
//   에러율: 조회 < 1%, 충전 < 0.1%(결제) · 용량 목표: 수요 10배(500 RPS)에서 서버 SLO 유지
// 요청 구성: 조회 90% (지갑 목록), 충전 10% (돈이 움직이는 요청 약 10% 가정)
//   LOGIN_RATIO를 주면 그 비율만큼 로그인을 섞는다(조회에서 뺌). 예: 0.05 = 하루 API 30회 중 로그인 1~2회 가정
//   로그인은 비밀번호 해시(BCrypt) 검증이 CPU를 많이 쓰는 경로라 web CPU 병목 후보
// 같은 스크립트·같은 클라이언트로 VM 단계와 K8s 단계를 측정해 비교한다.
//
// 시나리오 (SCENARIO 환경변수)
//   steady (기본): 도착률 고정 RATE RPS — 목표 충족 여부 판정 (예열 30초 + 측정 DURATION)
//   stress       : 계단식으로 올려 무너지는 지점(병목)을 찾는다 — 단계마다 15초 올리고 60초 유지 (기본 50~500, 500 = 수요 10배)
//                  에러율 10% 또는 p95 2초를 넘으면 자동 중단 (서버를 끝까지 몰아붙이지 않는다)
//
// 실행은 run.sh로 (BASE_URL·결과 파일 이름·서버 상태 기록을 자동으로 맞춘다):
//   RATE=50 STAGE=vm ./infra/tests/k6/run.sh
//   SCENARIO=stress STAGE=vm ./infra/tests/k6/run.sh
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { randomItem, uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

const BASE_URL = __ENV.BASE_URL;          // 예: https://10.0.1.50 (lb 공인 IP)
const SCENARIO = __ENV.SCENARIO || 'steady';
const RATE = Number(__ENV.RATE || 50);    // steady: 초당 요청 수 (도착률 고정)
const DURATION = __ENV.DURATION || '3m';  // steady: 측정 구간 길이
const USERS = Number(__ENV.USERS || 50);  // 테스트 계정 수 (충전이 한 지갑에 몰리지 않게 분산)
const CHARGE_RATIO = 0.1;
const LOGIN_RATIO = Number(__ENV.LOGIN_RATIO || 0);   // 기본 0 = 기존 측정과 같은 구성
const CHARGE_AMOUNT = '1000';             // KRW. 지갑 보유 한도(200만 원)에 닿지 않게 작게
const PASSWORD = 'k6-load-test!';         // 테스트 전용 계정 비밀번호 (실제 사용자 아님)

// stress 단계 (RPS). 쉼표로 바꿀 수 있다: STEPS=100,200,300
const STEPS = (__ENV.STEPS || '50,100,200,300,400,500').split(',').map(Number);
const RAMP_S = 15;  // 다음 단계로 올라가는 시간(초)
const HOLD_S = 60;  // 단계 유지 시간(초) — 이 구간만 단계 수치로 집계

if (!BASE_URL) throw new Error('BASE_URL이 필요합니다 (run.sh가 terraform output으로 채운다)');
if (!['steady', 'stress'].includes(SCENARIO)) throw new Error(`SCENARIO는 steady 또는 stress: ${SCENARIO}`);

function steadyOptions() {
  return {
    scenarios: {
      // JVM·커넥션 풀 예열. 수치 판정에서는 뺀다
      warmup: {
        executor: 'constant-arrival-rate',
        rate: Math.max(1, Math.floor(RATE / 2)), timeUnit: '1s', duration: '30s',
        preAllocatedVUs: 20, maxVUs: 200,
      },
      // 측정 구간: 응답이 느려져도 초당 요청 수를 유지한다(도착률 고정 = 사용자는 기다려 주지 않는다)
      main: {
        executor: 'constant-arrival-rate',
        rate: RATE, timeUnit: '1s', duration: DURATION, startTime: '30s',
        preAllocatedVUs: 50, maxVUs: 400,
      },
    },
    thresholds: {
      'http_req_duration{scenario:main}': ['p(95)<300'],
      'http_req_failed{scenario:main}': ['rate<0.01'],
      // 측정 구간 요청이 0건이면 위 기준이 전부 통과로 보인다(11/19 첫 실행) → 실제로 요청이 나갔는지도 판정
      'http_reqs{scenario:main}': ['count>0'],
      // 요청 종류별로도 따로 본다 (병목이 조회인지 충전(DB 쓰기·락)인지 구분)
      'http_req_duration{scenario:main,name:wallets_list}': ['p(95)<300'],
      'http_req_duration{scenario:main,name:charge}': ['p(95)<300'],
      // 결제(충전)는 더 엄격하게: 1,000건 중 1건 미만
      'http_req_failed{scenario:main,name:charge}': ['rate<0.001'],
      'http_req_failed{scenario:main,name:wallets_list}': ['rate<0.01'],
      ...(LOGIN_RATIO > 0 ? {
        'http_req_duration{scenario:main,name:login}': ['p(95)<300'],
        'http_req_failed{scenario:main,name:login}': ['rate<0.01'],
      } : {}),
    },
  };
}

function stressOptions() {
  const stages = [];
  for (const rps of STEPS) {
    stages.push({ target: rps, duration: `${RAMP_S}s` }); // 올리기
    stages.push({ target: rps, duration: `${HOLD_S}s` }); // 유지
  }
  const thresholds = {
    // 서버를 끝까지 몰아붙이지 않는다: 20초 이상 이어지면 중단
    'http_req_failed{scenario:stress}': [{ threshold: 'rate<0.10', abortOnFail: true, delayAbortEval: '20s' }],
    'http_req_duration{scenario:stress}': [{ threshold: 'p(95)<2000', abortOnFail: true, delayAbortEval: '20s' }],
  };
  // 단계별 수치를 요약(JSON)에 남기려면 그 태그의 기준이 있어야 한다 → 항상 통과하는 기준을 단다(판정용 아님)
  for (const rps of STEPS) {
    thresholds[`http_req_duration{scenario:stress,step:${rps}}`] = ['p(95)>=0'];
    thresholds[`http_req_failed{scenario:stress,step:${rps}}`] = ['rate>=0'];
    thresholds[`http_reqs{scenario:stress,step:${rps}}`] = ['count>=0'];
    thresholds[`http_req_duration{scenario:stress,step:${rps},name:charge}`] = ['p(95)>=0'];
    if (LOGIN_RATIO > 0) thresholds[`http_req_duration{scenario:stress,step:${rps},name:login}`] = ['p(95)>=0'];
  }
  return {
    scenarios: {
      stress: {
        executor: 'ramping-arrival-rate',
        startRate: Math.max(1, Math.floor(STEPS[0] / 2)), timeUnit: '1s', stages,
        preAllocatedVUs: 100, maxVUs: 1000, // VU가 모자라 요청을 못 보내면 dropped_iterations로 남는다
      },
    },
    thresholds,
  };
}

export const options = Object.assign(
  {
    insecureSkipTLSVerify: true, // LB 인증서가 자체 서명
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  },
  SCENARIO === 'stress' ? stressOptions() : steadyOptions(),
);

// stress: 지금이 몇 번째 단계의 "유지" 구간인지 (올리는 구간은 'ramp')
function currentStep() {
  const elapsedS = (Date.now() - exec.scenario.startTime) / 1000;
  const i = Math.floor(elapsedS / (RAMP_S + HOLD_S));
  if (i >= STEPS.length) return 'ramp';
  return (elapsedS - i * (RAMP_S + HOLD_S)) >= RAMP_S ? String(STEPS[i]) : 'ramp';
}

// 시작 전 1회: 테스트 계정 가입 → 로그인(토큰) → KRW 지갑 개설
// 로그인은 비밀번호 해시 검증이 무거워 측정 구간에서는 하지 않는다(실사용도 로그인은 드물고, 토큰 유효 15분 안에 끝난다)
export function setup() {
  const runId = Date.now();
  const json = { headers: { 'Content-Type': 'application/json' } };
  const accounts = [];
  for (let i = 0; i < USERS; i++) {
    const email = `k6-${runId}-${i}@zippay.test`;
    const body = JSON.stringify({ email, password: PASSWORD });
    const signup = http.post(`${BASE_URL}/api/auth/signup`, body, json);
    check(signup, { 'signup 201': (r) => r.status === 201 });

    const login = http.post(`${BASE_URL}/api/auth/login`, body, json);
    const token = login.cookies.access_token && login.cookies.access_token[0].value;
    if (!token) throw new Error(`로그인 실패 (status ${login.status}) — 계정 ${i}`);
    const auth = { headers: { 'Content-Type': 'application/json', Cookie: `access_token=${token}` } };

    const wallet = http.post(`${BASE_URL}/api/wallets`, JSON.stringify({ currency: 'KRW' }), auth);
    if (wallet.status !== 201) {
      // 원인 파악용: 응답 본문(오류 코드)과 응답한 web(X-Upstream)을 남긴다
      throw new Error(`지갑 개설 실패 (계정 ${i}, status ${wallet.status}, upstream ${wallet.headers['X-Upstream']}): ${wallet.body}`);
    }
    accounts.push({ email, token, walletId: wallet.json('id') });
  }
  // summarize.py가 이 시각으로 단계별 서버 CPU를 맞춘다
  console.log(`SCENARIO_START ${new Date().toISOString()}`);
  return { accounts };
}

export default function (data) {
  const a = randomItem(data.accounts);
  const headers = { 'Content-Type': 'application/json', Cookie: `access_token=${a.token}` };
  const tags = SCENARIO === 'stress' ? { step: currentStep() } : {};

  const r = Math.random();
  if (r < LOGIN_RATIO) {
    // 로그인: 비밀번호 해시 검증(측정 대상). 받은 토큰은 쓰지 않는다(다른 요청은 setup 토큰으로)
    const res = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email: a.email, password: PASSWORD }),
      { headers: { 'Content-Type': 'application/json' }, tags: { ...tags, name: 'login' } },
    );
    check(res, { 'login 204': (x) => x.status === 204 });
  } else if (r < LOGIN_RATIO + CHARGE_RATIO) {
    // 충전: 요청마다 새 멱등성 키(재시도가 아니라 새 거래)
    const res = http.post(
      `${BASE_URL}/api/wallets/${a.walletId}/charges`,
      JSON.stringify({ amount: CHARGE_AMOUNT }),
      { headers: { ...headers, 'Idempotency-Key': uuidv4() }, tags: { ...tags, name: 'charge' } },
    );
    check(res, { 'charge 201': (r) => r.status === 201 });
  } else {
    const res = http.get(`${BASE_URL}/api/wallets`, { headers, tags: { ...tags, name: 'wallets_list' } });
    check(res, { 'wallets 200': (r) => r.status === 200 });
  }
}
