"""量鈴聲在手機喇叭上聽起來的響度（不需要 scipy）

- K 加權（ITU-R BS.1770 的人耳響度曲線）＋ 400 Hz 高通（手機小喇叭幾乎放不出低音）
- 用頻域套濾波器（響度只看能量，相位不影響）
- 回傳整首的響度（有閘門的 LUFS）、3 秒短期響度的變化範圍（10%～95%）、前 3 秒響度
"""
import numpy as np


def _shelf_hp_mag(f, fs):
    """BS.1770 K 加權的強度響應（高頻擱架 + RLB 高通），用類比原型近似"""
    # 高頻擱架：約 +4 dB，1.5 kHz 起
    G = 10 ** (4.0 / 20)
    fc, Q = 1681.97, 0.7072
    s = 1j * f / fc
    shelf = (G * s ** 2 + np.sqrt(G) / Q * s + 1) / (s ** 2 + s / Q + 1)
    # RLB 高通：38 Hz
    fc2, Q2 = 38.135, 0.5003
    s2 = 1j * f / fc2
    hp = s2 ** 2 / (s2 ** 2 + s2 / Q2 + 1)
    return np.abs(shelf * hp)


def _phone_mag(f, cutoff=400.0, order=2):
    """手機喇叭：Butterworth 高通"""
    with np.errstate(divide='ignore'):
        r = (cutoff / np.maximum(f, 1e-6)) ** (2 * order)
    return 1 / np.sqrt(1 + r)


def weighted(x, sr, phone=True):
    """x: (n, ch) → 加權後的訊號（頻域濾波）"""
    n = x.shape[0]
    X = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(n, 1 / sr)
    H = _shelf_hp_mag(f, sr) * (_phone_mag(f) if phone else 1)
    return np.fft.irfft(X * H[:, None], n=n, axis=0)


def block_loudness(y, sr, win=0.4, hop=0.1):
    w, h = int(win * sr), int(hop * sr)
    n = max(1, (len(y) - w) // h + 1)
    ms = np.array([np.mean(y[i * h:i * h + w] ** 2, axis=0).sum() for i in range(n)])
    return -0.691 + 10 * np.log10(ms + 1e-12)


def integrated(y, sr):
    lk = block_loudness(y, sr)
    lk = lk[lk > -70]
    if len(lk) == 0:
        return -70.0
    rel = 10 * np.log10(np.mean(10 ** (lk / 10))) - 10
    lk = lk[lk > rel]
    return float(10 * np.log10(np.mean(10 ** (lk / 10))))


def short_term(y, sr):
    return block_loudness(y, sr, win=3.0, hop=1.0)


def report(x, sr, phone=True):
    y = weighted(x, sr, phone)
    st = short_term(y, sr)
    st_valid = st[st > -60]
    lra = float(np.percentile(st_valid, 95) - np.percentile(st_valid, 10)) if len(st_valid) > 3 else 0.0
    first = float(block_loudness(y[: 3 * sr], sr, win=1.0, hop=1.0).max()) if len(y) > 3 * sr else 0.0
    return dict(lufs=round(integrated(y, sr), 1), lra=round(lra, 1), first3s=round(first, 1),
                peak_db=round(float(20 * np.log10(np.abs(x).max() + 1e-9)), 1))
