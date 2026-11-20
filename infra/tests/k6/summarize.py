#!/usr/bin/env python3
"""run.sh 결과 요약: k6 요약 JSON + 서버 상태 기록(vmstat·docker stats·DB 연결 수)을 표로 정리한다.
사용: python3 infra/tests/k6/summarize.py infra/evidence/<시각>_k6_<STAGE>_<라벨>   (확장자 없이)
"""
import glob, json, os, re, sys

base = sys.argv[1]
d = json.load(open(base + ".json"))
m = d["metrics"]

def ms(x):
    return f"{x:.0f}" if x is not None else "-"

def trend(key):
    v = m.get(key) or {}
    return v.get("med"), v.get("p(95)"), v.get("p(99)"), v.get("max")

def fail_rate(key):
    v = m.get(key) or {}
    return v.get("value")

def count(key):
    return (m.get(key) or {}).get("count")

steps = sorted({int(s) for k in m for s in re.findall(r"step:(\d+)", k)})
print(f"## k6 요약 — {os.path.basename(base)}")
if steps:
    has_login = any("name:login" in k for k in m)
    print("단계(RPS) | 요청 수 | 실제 RPS(유지 60초) | p50 | p95 | p99 | 최대 | 충전 p95 | " + ("로그인 p95 | " if has_login else "") + "에러율")
    print("---|---|---|---|---|---|---|---|" + ("---|" if has_login else "") + "---")
    for s in steps:
        tag = f"{{scenario:stress,step:{s}}}"
        med, p95, p99, mx = trend("http_req_duration" + tag)
        c = count("http_reqs" + tag)
        er = fail_rate("http_req_failed" + tag)
        _, cp95, _, _ = trend(f"http_req_duration{{scenario:stress,step:{s},name:charge}}")
        if not c:
            print(f"{s} | 0 | - | - | - | - | - | - | - (중단 이후)")
            continue
        lcol = ""
        if has_login:
            _, lp, _, _ = trend(f"http_req_duration{{scenario:stress,step:{s},name:login}}")
            lcol = f"{ms(lp)} | "
        print(f"{s} | {c} | {c/60:.1f} | {ms(med)} | {ms(p95)} | {ms(p99)} | {ms(mx)} | {ms(cp95)} | {lcol}{er*100:.2f}%")
    dropped = count("dropped_iterations")
    print(f"\n보내지 못한 요청(dropped_iterations): {dropped or 0} — 0이 아니면 k6(클라이언트) 쪽 VU가 모자랐다는 뜻")
else:
    med, p95, p99, mx = trend("http_req_duration{scenario:main}")
    c = count("http_reqs{scenario:main}")
    er = fail_rate("http_req_failed{scenario:main}")
    _, lp95, _, _ = trend("http_req_duration{scenario:main,name:wallets_list}")
    _, cp95, _, _ = trend("http_req_duration{scenario:main,name:charge}")
    _, gp95, _, _ = trend("http_req_duration{scenario:main,name:login}")
    login_txt = f" · 로그인 {ms(gp95)}" if gp95 is not None else ""
    print(f"측정 구간 요청 {c}건 · 에러율 {(er or 0)*100:.2f}% · p50 {ms(med)}ms · p95 {ms(p95)}ms (조회 {ms(lp95)} · 충전 {ms(cp95)}{login_txt}) · p99 {ms(p99)}ms · 최대 {ms(mx)}ms")

# ---- 서버 상태: 측정 구간(측정 전 ~ 측정 후 기록 시각)만 집계 ----
# 기록 시각은 서버 시간(UTC). server.txt의 측정 전/후 시각(+09:00)을 UTC로 바꿔 구간을 정한다.
from datetime import datetime, timedelta, timezone

def window():
    try:
        txt = open(base + ".server.txt", errors="ignore").read()
    except FileNotFoundError:
        return None, None
    ts = re.findall(r"측정 [전후] 상태 (\S+)", txt)
    if len(ts) < 2:
        return None, None
    a, b = (datetime.fromisoformat(t).astimezone(timezone.utc).replace(tzinfo=None) for t in ts[:2])
    return a, b

W0, W1 = window()
day = W0.date() if W0 else None

def in_win(t):
    return W0 is None or (W0 <= t <= W1)

def tod(hms):  # "HH:MM:SS"(UTC) → datetime (측정일 기준)
    t = datetime.strptime(hms, "%H:%M:%S").time()
    return datetime.combine(day, t) if day else None

def read_vmstat(f):
    rows, idx = [], None
    for line in open(f, errors="ignore"):
        cols = line.split()
        if "id" in cols and "us" in cols:
            idx = {c: i for i, c in enumerate(cols)}
            continue
        if idx and cols and cols[0].isdigit() and len(cols) >= len(idx) + 1:  # 헤더 끝 "UTC" 1칸 ↔ 데이터의 날짜·시각 2칸
            t = datetime.strptime(cols[-2] + " " + cols[-1], "%Y-%m-%d %H:%M:%S")
            rows.append((t, 100 - int(cols[idx["id"]]), int(cols[idx["r"]]), int(cols[idx["wa"]])))
    return rows[1:]  # 첫 줄은 부팅 이후 평균이라 뺀다

def read_docker(f):
    rows, t = [], None
    for line in open(f, errors="ignore"):
        mt = re.match(r"# (\d\d:\d\d:\d\d)", line)
        if mt:
            t = tod(mt.group(1)); continue
        mc = re.match(r"zippay-backend-1 ([\d.]+)%", line)
        if mc and t:
            rows.append((t, float(mc.group(1))))
    return rows

def read_dbconn(f):
    rows = []
    for line in open(f, errors="ignore"):
        mm = re.match(r"(\d\d:\d\d:\d\d) (\d+) active / (\d+) total", line)
        if mm:
            rows.append((tod(mm.group(1)), int(mm.group(2)), int(mm.group(3))))
    return rows

vm = {f.split(".vmstat_")[1][:-4]: [r for r in read_vmstat(f) if in_win(r[0])] for f in sorted(glob.glob(base + ".vmstat_*.log"))}
dk = {f.split(".docker_")[1][:-4]: [r for r in read_docker(f) if in_win(r[0])] for f in sorted(glob.glob(base + ".docker_*.log"))}
dbc = [r for r in read_dbconn(base + ".dbconn.log") if in_win(r[0])] if os.path.exists(base + ".dbconn.log") else []

span = f" ({W0:%H:%M:%S}~{W1:%H:%M:%S} UTC)" if W0 else ""
print(f"\n## 서버 CPU — 측정 구간만{span} · 사용률 = 100 - idle")
print("서버 | 평균 % | 최대 % | 최대 실행 대기(r) | 최대 I/O 대기(wa) %")
print("---|---|---|---|---")
for name, rows in vm.items():
    if rows:
        b = [r[1] for r in rows]
        print(f"{name} | {sum(b)/len(b):.0f} | {max(b)} | {max(r[2] for r in rows)} | {max(r[3] for r in rows)}")
for name, rows in dk.items():
    if rows:
        c = [r[1] for r in rows]
        print(f"{name} 백엔드 컨테이너 CPU: 평균 {sum(c)/len(c):.0f}% · 최대 {max(c):.0f}% (100% = 코어 1개)")
if dbc:
    print(f"DB 연결: active 최대 {max(r[1] for r in dbc)} · 전체 최대 {max(r[2] for r in dbc)}")

# ---- stress: 단계(유지 구간)별 평균 CPU ----
# 시작 시각: k6 로그의 SCENARIO_START(setup 끝). 없으면 SCENARIO_START_UTC 환경변수(HH:MM:SS)로 직접 지정
st = None
try:
    ms_ = re.search(r"SCENARIO_START (\S+?)Z", open(base + ".log", errors="ignore").read())
    if ms_:
        st = datetime.fromisoformat(ms_.group(1)).replace(microsecond=0)
except FileNotFoundError:
    pass
if st is None and os.environ.get("SCENARIO_START_UTC") and day:
    st = tod(os.environ["SCENARIO_START_UTC"])
if steps and st:
    ramp, hold = int(os.environ.get("RAMP_S", 15)), int(os.environ.get("HOLD_S", 60))
    names = list(vm) + [f"{n} JVM" for n in dk]
    print(f"\n## 단계별 평균 CPU % (유지 구간, 시나리오 시작 {st:%H:%M:%S} UTC)")
    print("단계(RPS) | " + " | ".join(names))
    print("---|" + "---|" * len(names))
    for i, s_ in enumerate(steps):
        a = st + timedelta(seconds=i * (ramp + hold) + ramp); b = a + timedelta(seconds=hold)
        cells = []
        for n in vm:
            v = [r[1] for r in vm[n] if a <= r[0] < b]; cells.append(f"{sum(v)/len(v):.0f}" if v else "-")
        for n in dk:
            v = [r[1] for r in dk[n] if a <= r[0] < b]; cells.append(f"{sum(v)/len(v):.0f}" if v else "-")
        print(f"{s_} | " + " | ".join(cells))

# ---- lb nginx 요청별 시간: 지연이 어느 구간에서 생기는가 ----
#   rt  = lb가 요청을 받은 뒤 응답을 다 보낼 때까지, urt = lb → web 백엔드 응답 시간
#   k6 p95와 비교: k6 ≫ rt 이면 lb 앞단(클라이언트·VPN·TLS 연결), rt ≫ urt 이면 lb 자체, urt가 크면 백엔드·DB
f = base + ".nginx.log"
if os.path.exists(f) and os.path.getsize(f) > 0:
    rows = []
    for line in open(f, errors="ignore"):
        mm = re.match(r'(\S+) (\d+) "(\S+) (\S+)" rt=([\d.]+) urt=([\d., :-]+?) uct=', line)
        if not mm or not mm.group(4).startswith("/api/"):
            continue
        urt = sum(float(x) for x in re.findall(r"[\d.]+", mm.group(6)))  # 재시도면 여러 값 → 합
        t = datetime.fromisoformat(mm.group(1)).astimezone(timezone.utc).replace(tzinfo=None)
        rows.append((t, float(mm.group(5)) * 1000, urt * 1000, mm.group(4)))

    def pct(v, q):
        v = sorted(v); return v[min(len(v) - 1, int(len(v) * q))] if v else None

    def line_(label, rs):
        rt = [r[1] for r in rs]; ur = [r[2] for r in rs]
        return f"{label} | {len(rs)} | {ms(pct(rt,.5))} | {ms(pct(rt,.95))} | {ms(pct(rt,.99))} | {ms(pct(ur,.5))} | {ms(pct(ur,.95))} | {ms(pct(ur,.99))}"

    print("\n## lb에서 본 시간 (nginx, /api 요청, ms) — k6 p95와 비교")
    print("구간 | 요청 수 | rt p50 | rt p95 | rt p99 | urt p50 | urt p95 | urt p99")
    print("---|---|---|---|---|---|---|---")
    print(line_("전체", rows))
    # 서버 구간 SLO (11/20 조정): lb에서 본 시간 p95 ≤ 100ms — 네트워크·클라이언트 편차와 무관하게 서버 성능만 판정
    SERVER_P95_MS = float(os.environ.get("SERVER_P95_MS", 100))
    def verdict(rs):
        v = pct([r[1] for r in rs], .95)
        return "-" if v is None else ("✓" if v <= SERVER_P95_MS else "✗") + f" (rt p95 {v:.0f}ms / 기준 {SERVER_P95_MS:.0f}ms)"
    print(f"\n서버 구간 SLO 판정 — 전체: {verdict(rows)}")
    if steps and st:
        ramp, hold = int(os.environ.get("RAMP_S", 15)), int(os.environ.get("HOLD_S", 60))
        for i, s_ in enumerate(steps):
            a = st + timedelta(seconds=i * (ramp + hold) + ramp); b = a + timedelta(seconds=hold)
            rs = [r for r in rows if a <= r[0] < b]
            if rs:
                print(line_(f"{s_} RPS", rs) + f"   서버 SLO {verdict(rs)}")
    print("충전만: " + line_("charge", [r for r in rows if "/charges" in r[3]]).split(" | ", 1)[1])
    lg = [r for r in rows if r[3] == "/api/auth/login"]
    if lg:
        print("로그인만: " + line_("login", lg).split(" | ", 1)[1])

