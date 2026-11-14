#!/usr/bin/env python3
"""웹 서버 1대 장애 시 LB 자동 전환 측정.

LB 경유로 0.2초마다 API를 호출하면서, 중간에 web 한 대의 백엔드를 멈췄다가 다시 켠다.
각 요청의 응답 코드와 응답한 web(X-Upstream 헤더)을 기록해 실패 건수와 전환 양상을 본다.

사용 (zippay 루트에서, VPN 연결 상태):
    python3 infra/scripts/failover_test.py            # 기본: 첫 번째 web을 15초에 중단, 40초에 재기동, 60초간
    STOP_AT=10 START_AT=30 DURATION=50 python3 infra/scripts/failover_test.py web02-<학번>

성공 판정: 401(로그인 안 한 /api/users/me = 백엔드가 응답) 또는 200. 그 외(502/503/504/000)는 실패.
"""
import csv, os, re, subprocess, sys, threading, time
from collections import Counter
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INV = ROOT / "ansible" / "inventory.ini"

def parse_inventory():
    groups, cur = {}, None
    for line in INV.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        m = re.match(r"\[(.+)\]", line)
        if m:
            cur = m.group(1); groups[cur] = []; continue
        if cur and "ansible_host=" in line:
            name = line.split()[0]
            ip = re.search(r"ansible_host=(\S+)", line).group(1)
            groups[cur].append((name, ip))
    return groups

g = parse_inventory()
LB_IP = g["lb"][0][1]
target_name = sys.argv[1] if len(sys.argv) > 1 else g["web"][0][0]
TARGET_IP = dict(g["web"])[target_name]
DURATION = float(os.environ.get("DURATION", 60))
STOP_AT = float(os.environ.get("STOP_AT", 15))
START_AT = float(os.environ.get("START_AT", 40))
INTERVAL = 0.2
URL = f"https://{LB_IP}/api/users/me"
COMPOSE = "sudo docker compose -f /opt/zippay/docker-compose.yml"

events = []
t0 = time.time()

def ssh(cmd, label):
    events.append((round(time.time() - t0, 2), f"{label} 시작"))
    subprocess.run(["ssh", "-o", "StrictHostKeyChecking=accept-new", f"ubuntu@{TARGET_IP}", cmd],
                   check=False, capture_output=True)
    events.append((round(time.time() - t0, 2), f"{label} 완료"))

def scheduler():
    time.sleep(STOP_AT); ssh(f"{COMPOSE} stop backend", f"{target_name} 백엔드 중단")
    time.sleep(max(0, START_AT - (time.time() - t0))); ssh(f"{COMPOSE} start backend", f"{target_name} 백엔드 재기동")

def probe():
    r = subprocess.run(["curl", "-sk", "-o", "/dev/null", "-D", "-", "--max-time", "3",
                        "-w", "\n__CODE__ %{http_code} %{time_total}", URL],
                       capture_output=True, text=True)
    code, t = "000", "0"
    m = re.search(r"__CODE__ (\d+) ([\d.]+)", r.stdout)
    if m:
        code, t = m.group(1), m.group(2)
    up = re.search(r"(?im)^x-upstream:\s*(.+?)\s*$", r.stdout)
    return code, float(t), (up.group(1) if up else "")

out = ROOT / "evidence" / f"{datetime.now():%Y%m%d_%H%M%S}_failover.csv"
out.parent.mkdir(exist_ok=True)
rows = []
threading.Thread(target=scheduler, daemon=True).start()
print(f"LB {LB_IP} · 중단 대상 {target_name}({TARGET_IP}) · {STOP_AT}s 중단 / {START_AT}s 재기동 / {DURATION}s")
while (el := time.time() - t0) < DURATION:
    code, rt, up = probe()
    rows.append((round(el, 2), code, up, rt))
    time.sleep(max(0, INTERVAL - rt))

with out.open("w", newline="") as f:
    w = csv.writer(f); w.writerow(["elapsed_s", "http_code", "upstream", "time_total_s"]); w.writerows(rows)

ok = lambda c: c in ("200", "401")
fails = [r for r in rows if not ok(r[1])]
def phase(a, b):
    return Counter(r[2].split(",")[-1].strip() or "-" for r in rows if a <= r[0] < b and ok(r[1]))
stop_done = next((e[0] for e in events if "중단 완료" in e[1]), STOP_AT)
start_done = next((e[0] for e in events if "재기동 완료" in e[1]), START_AT)
slow = max(rows, key=lambda r: r[3])
print("\n== 이벤트"); [print(f"  {t:6.2f}s  {e}") for t, e in events]
print(f"\n== 요청 {len(rows)}건 · 성공 {len(rows)-len(fails)} · 실패 {len(fails)}")
if fails:
    print(f"  실패 구간: {fails[0][0]}s ~ {fails[-1][0]}s, 코드 {Counter(r[1] for r in fails)}")
print(f"  최장 응답: {slow[3]:.3f}s ({slow[0]}s 시점, {slow[2] or '-'})")
print(f"  응답한 web — 중단 전: {dict(phase(0, stop_done))}")
print(f"               중단 중: {dict(phase(stop_done, start_done))}")
print(f"               재기동 후: {dict(phase(start_done, DURATION))}")
back = next((r[0] for r in rows if r[0] > start_done and TARGET_IP in r[2]), None)
print(f"  재기동 후 {target_name}가 다시 응답하기까지: {round(back - start_done, 2) if back else '측정 구간 안에 없음'}s")
print(f"\n기록: {out}")
