"""Reverie Alarm 流暢度量測（透過 adb，不需要 Android Studio）

手機用 USB 接上電腦、解鎖，App 停在要測的畫面，然後：

  python tools/perf/measure.py refresh       螢幕現在實際幾 Hz、系統有沒有限制更新率（例如過熱）
  python tools/perf/measure.py idle          不碰手機 6 秒，App 畫了幾幀、螢幕有沒有維持 120Hz
  python tools/perf/measure.py month         在月曆上左右滑 6 次（App 要停在「排班日曆」的月視圖）
  python tools/perf/measure.py tabs          在月曆下方的摘要卡左右滑，切換底部分頁 4 次
  python tools/perf/measure.py analyze 檔案   分析自己存下來的 `dumpsys gfxinfo <套件> framestats` 輸出

怎麼看結果（120Hz 時每一幀只有 8.3ms）：
  - 掉幀：滑動中有幾格 vsync 沒有新畫面（越低越好，< 2% 幾乎看不出來）
  - UI 執行緒：每一幀在主執行緒花的時間；p90 超過 8.3ms 就會開始掉幀
  - 最慢幾幀的「動畫/重組」很大 → Compose 重組太多；「繪製錄製」很大 → 畫面太複雜

注意：
  - 要用 perfTest 或正式版量（debug 版沒有最佳化，慢很多）
  - 新裝的版本先操作一兩輪再量；手機很燙或插著充電時，系統可能把更新率壓到 60Hz（用 refresh 看）
"""
import os
import re
import shutil
import subprocess
import sys
import time

PKG = "com.reverie.alarm"


def find_adb():
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            p = os.path.join(root, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
            if os.path.exists(p):
                return p
    local = os.environ.get("LOCALAPPDATA")
    if local:
        p = os.path.join(local, "Android", "Sdk", "platform-tools", "adb.exe")
        if os.path.exists(p):
            return p
    p = shutil.which("adb")
    if p:
        return p
    sys.exit("找不到 adb：請安裝 Android SDK Platform-Tools，或設定 ANDROID_HOME")


ADB = None


def sh(*args):
    out = subprocess.run([ADB, "shell", *args], capture_output=True)
    return out.stdout.decode("utf-8", "replace")


def screen_size():
    m = re.search(r"(\d+)x(\d+)", sh("wm", "size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


# ---------------------------------------------------------------------- 分析 framestats

def parse_framestats(text):
    hdr, rows, inside = None, [], False
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("---PROFILEDATA---"):
            inside = not inside
            continue
        if not inside or not line:
            continue
        if line.startswith("Flags"):
            hdr = [h for h in line.split(",") if h]
            continue
        vals = [v for v in line.split(",") if v != ""]
        try:
            rows.append(dict(zip(hdr, [int(v) for v in vals])))
        except (ValueError, TypeError):
            pass
    return rows


def pct(a, q):
    a = sorted(a)
    return a[min(len(a) - 1, int(len(a) * q))] if a else 0.0


def analyze(text, title=""):
    rows = parse_framestats(text)
    if len(rows) < 2:
        print("沒有足夠的幀資料（App 要在畫面上，而且有動作）")
        return
    # 一格 vsync 多長：優先用系統給的 FrameInterval，否則用相鄰幀的最小間隔
    intervals = [r["FrameInterval"] for r in rows if r.get("FrameInterval", 0) > 0]
    vs = pct(intervals, 0.5) if intervals else 1e9 / 120
    hz = 1e9 / vs
    iv = [r["IntendedVsync"] for r in rows]
    span = (iv[-1] - iv[0]) / 1e9

    def ui_ms(r):
        return (r["SyncStart"] - r["IntendedVsync"]) / 1e6

    active, slots, dropped, slow_drops, at_low_hz, missed = [], 0, 0, 0, 0, 0
    for i in range(1, len(rows)):
        gap = iv[i] - iv[i - 1]
        if gap < 40e6:  # 40ms 內連續出幀＝正在動（滑動、動畫）
            # 用「這一幀當時」的 vsync 週期算：螢幕剛從閒置的 60Hz 升回 120Hz 的那幾幀，間隔 16.7ms 不算掉幀
            period = rows[i].get("FrameInterval", 0) or vs
            if period > vs * 1.4:
                at_low_hz += 1
            n = max(1, round(gap / period))
            slots += n
            dropped += n - 1
            # 空檔前一幀在主執行緒就超過一格 vsync → App 太慢造成的；
            # 否則是那一刻沒有新的手指輸入或動畫更新，本來就不用畫（例如 adb 模擬滑動的觸控事件不夠密）
            if n > 1 and ui_ms(rows[i - 1]) > period / 1e6:
                slow_drops += n - 1
            done = max(rows[i]["FrameCompleted"], rows[i].get("GpuCompleted", 0))
            if done > rows[i]["FrameDeadline"]:
                missed += 1
            active.append(rows[i])
    ui = [(r["SyncStart"] - r["IntendedVsync"]) / 1e6 for r in active]
    rt = [(r["SwapBuffers"] - r["IssueDrawCommandsStart"]) / 1e6 for r in active]
    gpu = [(r["GpuCompleted"] - r["IssueDrawCommandsStart"]) / 1e6 for r in active if r.get("GpuCompleted", 0) > 0]
    budget = vs / 1e6
    print(f"== {title}" if title else "==")
    print(f"螢幕：約 {hz:.0f}Hz（每幀 {budget:.2f}ms）；資料涵蓋 {span:.1f} 秒、{len(rows)} 幀，平均 {(len(rows) - 1) / span if span else 0:.1f} 幀/秒")
    if not active:
        print("這段時間沒有連續的動作（沒有在滑動或播動畫）")
        return
    print(f"動作中：{len(active)} 幀、{slots} 格 vsync，沒出幀的空檔 {dropped} 格（{100 * dropped / max(1, slots):.1f}%）")
    print(f"  └ App 太慢造成的：{slow_drops} 格（{100 * slow_drops / max(1, slots):.1f}%）；其餘是那一刻沒有新輸入或動畫，不用畫")
    print(f"系統判定的卡頓（錯過顯示截止時間）：{missed} 幀")
    if at_low_hz:
        print(f"其中 {at_low_hz} 幀畫的時候螢幕還在低更新率（閒置降頻後剛開始滑，系統升回高更新率前）")
    print(f"UI 執行緒：中位數 {pct(ui, .5):.1f}ms、p90 {pct(ui, .9):.1f}ms、最慢 {max(ui):.1f}ms；超過 {budget:.1f}ms 的有 {sum(1 for x in ui if x > budget)} 幀")
    print(f"RenderThread：中位數 {pct(rt, .5):.1f}ms、p90 {pct(rt, .9):.1f}ms；GPU：中位數 {pct(gpu, .5):.1f}ms、p90 {pct(gpu, .9):.1f}ms")
    slow = sorted(active, key=lambda r: r["SyncStart"] - r["IntendedVsync"], reverse=True)[:5]
    print("最慢的幾幀（毫秒）：輸入 / 動畫・重組 / 排版 / 繪製錄製")
    for r in slow:
        print("  %5.1f / %5.1f / %5.1f / %5.1f" % (
            (r["AnimationStart"] - r["HandleInputStart"]) / 1e6 if r.get("HandleInputStart") else 0.0,
            (r["PerformTraversalsStart"] - r["AnimationStart"]) / 1e6,
            (r["DrawStart"] - r["PerformTraversalsStart"]) / 1e6,
            (r["SyncQueued"] - r["DrawStart"]) / 1e6))


def framestats(save_as=None):
    """取得每一幀的資料。系統只保留最近 120 幀，所以動作一做完就要馬上取（並存檔，之後還能再分析）"""
    text = sh("dumpsys", "gfxinfo", PKG, "framestats")
    if save_as:
        import tempfile
        path = os.path.join(tempfile.gettempdir(), f"reverie_{save_as}_framestats.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"（原始資料存在 {path}，可以用 analyze 再看一次）")
    return text


def reset():
    sh("dumpsys", "gfxinfo", PKG, "reset")


# ---------------------------------------------------------------------- 指令

def cmd_refresh():
    d = sh("dumpsys", "display")
    m = re.search(r"mActiveRenderFrameRate=([\d.]+)", d)
    print(f"目前實際畫面更新率：{float(m.group(1)):.0f}Hz" if m else "讀不到目前更新率")
    modes = sorted({float(x) for x in re.findall(r"fps=([\d.]+)", d)})
    if modes:
        print("螢幕支援：" + "、".join(f"{x:.0f}Hz" for x in modes))
    if "mIgnorePreferredRefreshRate: true" in d:
        print("這支手機會忽略 preferredRefreshRate（只看 preferredDisplayModeId）")
    # 最後一筆「更新率決策紀錄」就是現在的限制
    blocks = re.split(r"\n(?=\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+Display 0:)", d)
    last = [b for b in blocks if re.match(r"\d\d-\d\d \d\d:\d\d:\d\d", b)]
    if last:
        print("系統目前的更新率限制：")
        for line in last[-1].splitlines()[1:12]:
            if "PRIORITY_" in line or "mDesiredDisplayModeSpecs" in line:
                print("  " + line.strip()[:160])
    if "THERMAL" in (last[-1] if last else ""):
        print("⚠️ 目前有溫度限制：手機降溫後才會回到高更新率")


def cmd_idle():
    print("請不要碰手機，量 6 秒…")
    reset()
    time.sleep(6)
    rows = parse_framestats(framestats())
    n = len(rows)
    print(f"6 秒內 App 畫了 {n} 幀（{n / 6:.1f} 幀/秒）")
    print("（正常約每秒 20 幀：背景星星閃爍約 17 幀，加上讓螢幕維持 120Hz 的心跳約 4 幀；遠超過這個數字代表有東西在不必要地重畫）")
    m = re.search(r"renderRate=([\d.]+)", sh("dumpsys", "SurfaceFlinger"))
    if m:
        hz = float(m.group(1))
        print(f"閒置時螢幕更新率：{hz:.0f}Hz" + ("（✅ 沒有降頻）" if hz >= 110 else "（⚠️ 降頻了：動畫開始時會頓一下）"))


def swipes(y_frac, times, pause=1.0):
    w, h = screen_size()
    y = int(h * y_frac)
    left, right = int(w * 0.18), int(w * 0.82)
    for i in range(times):
        a, b = (right, left) if i % 2 == 0 else (left, right)
        # 先往左兩次再往右兩次，最後回到原本的月份／分頁
        if times >= 4:
            a, b = (right, left) if i < times // 2 else (left, right)
        sh("input", "swipe", str(a), str(y), str(b), str(y), "200")
        time.sleep(pause)


def cmd_month():
    print("在月曆格子上左右滑 6 次（最後會回到原本的月份）…")
    reset()
    swipes(0.41, 6)
    analyze(framestats("month"), "月曆左右滑")


def cmd_tabs():
    print("在摘要卡上左右滑，切換分頁 4 次（最後回到排班日曆）…")
    reset()
    swipes(0.72, 4, pause=1.1)
    analyze(framestats("tabs"), "底部分頁左右滑")


def main():
    global ADB
    if len(sys.argv) < 2:
        print(__doc__)
        return
    if sys.argv[1] == "analyze":
        if len(sys.argv) < 3:
            sys.exit("用法：python tools/perf/measure.py analyze 檔案")
        analyze(open(sys.argv[2], encoding="utf-8", errors="ignore").read(), os.path.basename(sys.argv[2]))
        return
    ADB = find_adb()
    {"refresh": cmd_refresh, "idle": cmd_idle, "month": cmd_month, "tabs": cmd_tabs}.get(sys.argv[1], lambda: print(__doc__))()


if __name__ == "__main__":
    main()
