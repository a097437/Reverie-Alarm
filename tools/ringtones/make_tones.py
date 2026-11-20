"""把授權明確的音樂做成 App 內建鬧鐘鈴聲（res/raw/rt_*.ogg）。

用法：python make_tones.py <原始音檔資料夾> <輸出資料夾>
需要：numpy、soundfile（響度計算在同資料夾的 loudness.py）

處理流程（目標：每首在「手機喇叭」上聽起來一樣大聲、同一首裡也不要忽大忽小）
1. 取片段
   - 「loop」：原曲本身可無縫循環，整首保留
   - 「cut」：跳過安靜的前奏，取 60 秒，尾端 3 秒交叉淡入接回開頭，循環時聽不到斷點
2. 去掉 120 Hz 以下的低音：手機喇叭放不出來，只會吃掉音量空間
3. 平順化：用 3 秒短期響度看整首的起伏，太小聲的地方慢慢補、太大聲的地方慢慢壓（最多 ±8 dB，變化很慢、不會忽大忽小）
4. 響度統一：用「K 加權 + 手機喇叭」的響度（見 loudness.py）把每首調到同樣的 LUFS
5. 峰值限制在 -1 dBFS（柔和的限制器，不會破音）
6. 輸出 OGG Vorbis，約 128 kbps

淡入不做在檔案裡（循環時會一直重來），由 App 播放時處理。
"""
import sys, os, io
import numpy as np, soundfile as sf
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import loudness as L

SRC, OUT = sys.argv[1], sys.argv[2]
LEN, XF = 60.0, 3.0
TARGET_LUFS = -18.0      # 手機喇叭加權後的目標響度
CEIL = 10 ** (-1.0 / 20)
KBPS = 128
HIGHPASS_HZ = 120.0

TONES = [  # (原始檔, raw 名稱, 處理方式)
    ("Morning Sky - Centurion_of_war.ogg", "rt_morning_sky", "loop"),
    ("Morning Sky (Piano) - Centurion_of_war.flac", "rt_morning_sky_piano", "loop"),
    ("First Light Particles - Yoiyami.wav", "rt_light_particles", "cut"),
    ("The Budding of Consciousness - Yoiyami.wav", "rt_budding", "cut"),
    ("Sunset Plains - Yoiyami.wav", "rt_sunset_plains", "cut"),
    ("A Small Fire Will Do - Trex0n.wav", "rt_small_fire", "loop"),
    ("Etirwer - Kistol.ogg", "rt_etirwer", "loop"),
    ("Music Box Cute Tune - Aureolus_Omicron.ogg", "rt_music_box", "cut"),
    ("Happy Lullaby - cynicmusic.mp3", "rt_happy_lullaby", "loop"),
    ("Dawn's Break - Shane Ivers.mp3", "rt_dawn_break", "cut"),
    ("Wake up in the morning sun - 東月.mp3", "rt_morning_sun", "cut"),
]


def rms_db(a):
    return 20 * np.log10(np.sqrt(np.mean(a ** 2)) + 1e-9)


def highpass(x, sr, fc=HIGHPASS_HZ, order=2):
    """頻域高通（循環濾波：片段本身就是循環的，接縫不會出問題）"""
    X = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(x.shape[0], 1 / sr)
    with np.errstate(divide='ignore'):
        H = 1 / np.sqrt(1 + (fc / np.maximum(f, 1e-6)) ** (2 * order))
    return np.fft.irfft(X * H[:, None], n=x.shape[0], axis=0)


def level(x, sr, ratio=0.6, max_db=8.0):
    """慢速平順化：依 3 秒短期響度（手機加權）調整增益，循環計算、接縫連續"""
    y = L.weighted(x, sr, phone=True)
    hop, win = sr // 2, 3 * sr
    n = len(y)
    centers = np.arange(0, n, hop)
    yy = np.concatenate([y[-win // 2:], y, y[:win // 2]])  # 循環補邊
    st = np.array([-0.691 + 10 * np.log10(np.mean(yy[c:c + win] ** 2, axis=0).sum() + 1e-12) for c in centers])
    ref = np.median(st[st > -60]) if np.any(st > -60) else -30
    dev = st - ref
    g = np.clip(-dev * ratio, -max_db, max_db)
    g[st < ref - 25] = 0.0  # 幾乎無聲的地方不要硬拉
    k = 9  # 約 4.5 秒的循環平滑
    g = np.convolve(np.concatenate([g[-k:], g, g[:k]]), np.ones(k) / k, mode='same')[k:-k]
    gs = np.interp(np.arange(n), np.append(centers, n), np.append(g, g[0]))
    return x * (10 ** (gs / 20))[:, None], float(st[st > -60].max() - st[st > -60].min()) if np.any(st > -60) else 0.0


def limiter(x, sr):
    blk = sr // 200  # 5 ms
    n = int(np.ceil(len(x) / blk))
    env = np.pad(np.abs(x).max(axis=1), (0, n * blk - len(x))).reshape(n, blk).max(axis=1)
    env = np.maximum(env, np.concatenate([env[1:], env[-1:]]))  # 5 ms 前瞻
    target = np.minimum(1.0, CEIL / np.maximum(env, 1e-9))
    rel = np.exp(-1 / (0.25 * 200))  # 250 ms 釋放
    g = np.empty(n); cur = 1.0
    for i in range(n):
        cur = target[i] if target[i] < cur else min(target[i], 1 - (1 - cur) * rel)
        g[i] = cur
    gr_db = -20 * np.log10(g)
    out = x * np.interp(np.arange(len(x)), np.arange(n) * blk + blk / 2, g)[:, None]
    return out, float(gr_db.max()), float(np.mean(gr_db > 2.0) * 100)


def encode(y, sr, level_q):
    buf = io.BytesIO()
    with sf.SoundFile(buf, 'w', sr, y.shape[1], format='OGG', subtype='VORBIS', compression_level=level_q) as f:
        for i in range(0, len(y), 4096):  # 一次寫太多 libsndfile 的 Vorbis 編碼器會當掉
            f.write(y[i:i + 4096])
    return buf.getvalue()


def encode_kbps(y, sr):
    """libsndfile 只能設品質（VBR），用二分搜尋找出最接近 128 kbps 的品質"""
    lo, hi, best = 0.0, 1.0, None
    for _ in range(7):
        mid = (lo + hi) / 2
        data = encode(y, sr, mid)
        kbps = len(data) * 8 / 1000 / (len(y) / sr)
        if best is None or abs(kbps - KBPS) < abs(best[1] - KBPS):
            best = (data, kbps)
        if kbps > KBPS: lo = mid
        else: hi = mid
    return best


os.makedirs(OUT, exist_ok=True)
for fn, name, mode in TONES:
    x, sr = sf.read(os.path.join(SRC, fn), dtype='float64', always_2d=True)
    if mode == "cut":
        mono = x.mean(axis=1)
        sec = len(mono) // sr
        lv = np.array([rms_db(mono[i * sr:(i + 1) * sr]) for i in range(sec)])
        ref = np.median(lv[: int(sec * 0.9)])
        start = 2
        for i in range(2, int(sec - LEN - XF)):
            if lv[i:i + 5].min() > ref - 6:  # 第一個「接近正常音量且之後 5 秒也維持」的位置
                start = i; break
        seg = x[start * sr: start * sr + int((LEN + XF) * sr)]
        k = int(XF * sr)
        body = seg[:-k].copy()
        t = np.linspace(0, 1, k)[:, None]
        body[:k] = body[:k] * np.sin(t * np.pi / 2) + seg[-k:] * np.cos(t * np.pi / 2)
    else:
        start, body = 0, x
    body = highpass(body, sr)
    body, swing = level(body, sr)
    # 響度統一（手機喇叭加權）；限制器壓掉的部分再補回一次，讓最後的響度貼近目標
    gain_db = TARGET_LUFS - L.integrated(L.weighted(body, sr), sr)
    y, gr_max, gr_pct = limiter(body * 10 ** (gain_db / 20), sr)
    fix = TARGET_LUFS - L.integrated(L.weighted(y, sr), sr)
    if abs(fix) > 0.2:
        y, gr_max, gr_pct = limiter(body * 10 ** ((gain_db + fix) / 20), sr)
    y = y.astype('float32')
    data, kbps = encode_kbps(y, sr)
    open(os.path.join(OUT, name + '.ogg'), 'wb').write(data)
    r = L.report(y.astype('float64'), sr)
    print(f"{name:22s} {mode:4s} start={start:3d}s len={len(y)/sr:5.1f}s swing(before)={swing:4.1f}dB gain={gain_db:+5.1f}dB "
          f"limiter max={gr_max:4.1f}dB >2dB:{gr_pct:4.1f}%  phone={r['lufs']:6.1f} LRA={r['lra']:4.1f} {kbps:5.1f}kbps {len(data)//1024}KB")
