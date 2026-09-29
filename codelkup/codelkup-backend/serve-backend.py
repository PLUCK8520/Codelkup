# -*- coding: utf-8 -*-
"""
启动后端并保持运行（跨 Bash 命令存活）。

为什么不用 `java -jar ... &`：本沙箱会把父 shell 退出时留下的后台进程回收掉，
实测 Popen(detached) 启动的 java 也会在几十秒内消失。

可行的做法是**让 Python 自己常驻**：Python 进程作为 java 的父进程一直活着，
java 就不会被判定为孤儿进程。父 shell 退出后 Python 进程本身也可能被回收，
所以用本脚本时应当配合 run_in_background。
"""
import subprocess, os, sys, time, urllib.request

# 本脚本就放在 codelkup-backend/ 下 —— 项目根用**脚本自身位置**推算，不写死本机绝对路径：
# 写死的话别人 clone 下来直接跑不了（还得先改代码），也会把本机用户名带进版本库。
BACKEND = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(BACKEND, "backend-run.log")

env = dict(os.environ)
env.pop("SERVER__PORT", None)   # 注入变量优先级高于 yml，必须剔除

logf = open(LOG, "wb", buffering=0)
# JVM 参数（2026-09-27 开发环境优化）：以前裸跑 jar，MaxHeap 默认取物理内存的 1/4
# （本机 ≈3.9GB），dev 负载根本用不到，某天压测后可能驻留不还。
#   -Xmx512m / -Xms256m       堆上限与初始堆：dev 数据量下 512m 绰绰有余
#   -XX:MaxMetaspaceSize=256m 元空间封顶（类加载 + 代理曾无上限）
#   -XX:+UseSerialGC          小堆场景比 G1 更省内存、更适合单请求量级的 dev
#   -XX:TieredStopAtLevel=1   只到 C1 编译：dev 不要峰值吞吐，省 JIT 内存、启动更快
# ⚠️ 验证脚本不受影响：自起型脚本直接 `java -jar`（无这些参数），性能口径不变。
JVM_OPTS = [
    "-Xmx512m", "-Xms256m", "-XX:MaxMetaspaceSize=256m",
    "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
]
# 命令行附加参数原样透传给 jar（验收脚本需要把定时窗口压到秒级，例如
# --codelkup.score.initial-delay-ms=2000 --codelkup.sse.heartbeat-ms=2000）
proc = subprocess.Popen(
    ["java"] + JVM_OPTS + ["-jar", "target/codelkup-backend-0.0.1-SNAPSHOT.jar"] + sys.argv[1:],
    cwd=BACKEND, env=env,
    stdout=logf, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
)
print(f"[serve] java pid = {proc.pid}", flush=True)

# 轮询就绪
for i in range(60):
    time.sleep(2)
    if proc.poll() is not None:
        print(f"[serve] java 已退出，code={proc.returncode}", flush=True)
        sys.exit(1)
    try:
        with urllib.request.urlopen("http://localhost:8085/actuator/health", timeout=3) as r:
            if r.status == 200:
                print(f"[serve] READY after ~{i*2}s", flush=True)
                break
    except Exception:
        pass
else:
    print("[serve] 超时未就绪", flush=True)
    sys.exit(1)

# 常驻：java 活着就一直陪着
print("[serve] backend is up, holding...", flush=True)
while proc.poll() is None:
    time.sleep(5)
print(f"[serve] java exited code={proc.returncode}", flush=True)
