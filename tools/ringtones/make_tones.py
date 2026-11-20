"""把授權明確的音樂做成 App 內建鬧鐘鈴聲（res/raw/rt_*.ogg）。

用法：python make_tones.py <原始音檔資料夾> <輸出資料夾>
需要：numpy、soundfile

- 「loop」：原曲本身可無縫循環，整首保留，只調音量
- 「cut」：跳過安靜的前奏，取 60 秒，尾端 3 秒交叉淡入接回開頭，循環時聽不到斷點
- 音量統一到平均 -17 dBFS，峰值限制在 -1 dBFS（柔和的限制器，不會破音）
- 輸出 OGG Vorbis，約 128 kbps
"""
import sys, os, io
import numpy as np, soundfile as sf

SRC, OUT = sys.argv[1], sys.argv[2]
LEN, XF, TARGET_DB, CEIL, KBPS = 60.0, 3.0, -17.0, 10 ** (-1.0 / 20), 128

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
    return x * np.interp(np.arange(len(x)), np.arange(n) * blk + blk / 2, g)[:, None]


def encode(y, sr, level):
    buf = io.BytesIO()
    with sf.SoundFile(buf, 'w', sr, y.shape[1], format='OGG', subtype='VORBIS', compression_level=level) as f:
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
    gain = 10 ** ((TARGET_DB - rms_db(body.mean(axis=1))) / 20)
    y = limiter(body * gain, sr).astype('float32')
    data, kbps = encode_kbps(y, sr)
    open(os.path.join(OUT, name + '.ogg'), 'wb').write(data)
    print(f"{name:22s} {mode:4s} start={start:3d}s len={len(y)/sr:5.1f}s gain={20*np.log10(gain):+5.1f}dB "
          f"peak={20*np.log10(np.abs(y).max()):5.1f} {kbps:5.1f}kbps {len(data)//1024}KB")
