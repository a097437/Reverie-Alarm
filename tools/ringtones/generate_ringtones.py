"""
Reverie Alarm 內建鈴聲產生器（程式合成，無任何取樣素材，沒有版權問題）

執行：python tools/ringtones/generate_ringtones.py
輸出：app/src/main/res/raw/rt_*.wav（16-bit 單聲道 32 kHz）

設計原則
- 每個檔案都是「整數個循環」，最後一個音在檔尾前完全衰減，所以 MediaPlayer 循環播放不會有爆音或斷點
- 音色以正弦波 + 少量泛音為主，避免方波那種刺耳的高頻
- 峰值正規化到 -1 dBFS；音量漸強由 App 的「漸進式音量增強」負責
"""
import math
import os
import struct
import wave

SR = 32000
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "res", "raw")


def note(name):
    """'A5' → 頻率（A4 = 440 Hz）"""
    names = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}
    semis = names[name[0]] + (int(name[-1]) - 4) * 12
    if "#" in name:
        semis += 1
    return 440.0 * 2 ** (semis / 12)


def render(length_s, events):
    """events: (開始秒數, 頻率清單, 長度秒數, 音色函式, 音量)"""
    n = int(SR * length_s)
    buf = [0.0] * n
    for start, freqs, dur, voice, gain in events:
        s0 = int(start * SR)
        for f in freqs:
            for i, v in enumerate(voice(f, dur)):
                j = s0 + i
                if j >= n:
                    break
                buf[j] += v * gain
    return buf


def env_ad(t, attack, decay):
    """快速起音 + 指數衰減"""
    if t < attack:
        return t / attack
    return math.exp(-(t - attack) / decay)


def voice_bright(f, dur):
    """強效：正弦 + 2、3 倍泛音（明亮、有穿透力，但沒有方波的刺耳）"""
    total = dur + 0.25
    out = []
    for i in range(int(total * SR)):
        t = i / SR
        e = env_ad(t, 0.004, dur * 0.9)
        out.append(e * (math.sin(2 * math.pi * f * t)
                        + 0.35 * math.sin(2 * math.pi * 2 * f * t)
                        + 0.12 * math.sin(2 * math.pi * 3 * f * t)))
    return out


def voice_bell(f, dur):
    """清脆鐘聲：非整數倍泛音（像小鈴鐺）"""
    total = dur + 0.6
    partials = [(1.0, 1.0, 1.0), (2.76, 0.45, 0.55), (5.4, 0.2, 0.3), (8.93, 0.08, 0.2)]
    out = []
    for i in range(int(total * SR)):
        t = i / SR
        v = 0.0
        for ratio, amp, dk in partials:
            v += amp * math.sin(2 * math.pi * f * ratio * t) * math.exp(-t / (0.35 * dk + 0.05))
        out.append(v * min(1.0, t / 0.002))
    return out


def voice_pad(f, dur):
    """柔和和弦：慢起音、輕微顫音、長釋音"""
    total = dur + 0.5
    out = []
    for i in range(int(total * SR)):
        t = i / SR
        if t < 0.08:
            e = t / 0.08
        elif t < dur:
            e = 1.0 - 0.25 * (t - 0.08) / dur
        else:
            e = 0.75 * math.exp(-(t - dur) / 0.15)
        vib = 1 + 0.003 * math.sin(2 * math.pi * 5 * t)
        out.append(e * (math.sin(2 * math.pi * f * vib * t) + 0.18 * math.sin(2 * math.pi * 2 * f * t)))
    return out


def voice_beep(f, dur):
    """電子嗶聲：正弦 + 少量 3 倍泛音（圓潤的數位音），前後 5 ms 淡入淡出避免爆音"""
    out = []
    n = int(dur * SR)
    fade = int(0.005 * SR)
    for i in range(n):
        t = i / SR
        e = min(1.0, i / fade, (n - i) / fade)
        out.append(e * (math.sin(2 * math.pi * f * t) + 0.22 * math.sin(2 * math.pi * 3 * f * t)))
    return out


def voice_marimba(f, dur):
    """木琴：基音 + 4 倍泛音快速衰減"""
    total = dur + 0.5
    out = []
    for i in range(int(total * SR)):
        t = i / SR
        out.append(min(1.0, t / 0.003) * (
            math.sin(2 * math.pi * f * t) * math.exp(-t / 0.45)
            + 0.4 * math.sin(2 * math.pi * 4 * f * t) * math.exp(-t / 0.06)))
    return out


def tone_wake_strong():
    # 同網頁版 wake：A5+E6、A5+E6、C6+G6，每 1.15 秒一輪
    cycle, events = 1.15, []
    for k in range(6):
        b = k * cycle
        events += [(b, [note("A5"), note("E6")], 0.12, voice_bright, 0.5),
                   (b + 0.18, [note("A5"), note("E6")], 0.12, voice_bright, 0.5),
                   (b + 0.36, [note("C6"), note("G6")], 0.25, voice_bright, 0.5)]
    return cycle * 6, events


def tone_leave_chime():
    # 同網頁版 leave：C6+E6，0.18 秒後 G6
    cycle, events = 1.15, []
    for k in range(6):
        b = k * cycle
        events += [(b, [note("C6"), note("E6")], 0.12, voice_bell, 0.5),
                   (b + 0.18, [note("G6")], 0.25, voice_bell, 0.6)]
    return cycle * 6, events


def tone_gentle_chord():
    # 同網頁版 gentle：C5-E5-G5 → D5-F5-A5，節奏放慢更柔和
    cycle, events = 2.3, []
    for k in range(4):
        b = k * cycle
        events += [(b, [note("C5"), note("E5"), note("G5")], 0.7, voice_pad, 0.33),
                   (b + 0.9, [note("D5"), note("F5"), note("A5")], 0.7, voice_pad, 0.33)]
    return cycle * 4, events


def tone_digital_beep():
    # 經典電子鬧鐘節奏：嗶嗶嗶嗶（4 × 90 ms）＋停頓
    cycle, events = 1.0, []
    for k in range(6):
        b = k * cycle
        for j in range(4):
            events.append((b + j * 0.14, [1318.5], 0.09, voice_beep, 0.6))
    return cycle * 6, events


def tone_sunrise_rise():
    # 上行琶音 C5 E5 G5 C6 E6，輕柔但會一路往上提神
    cycle, events = 1.8, []
    seq = ["C5", "E5", "G5", "C6", "E6"]
    for k in range(4):
        b = k * cycle
        for j, n in enumerate(seq):
            events.append((b + j * 0.16, [note(n)], 0.3, voice_marimba, 0.55))
    return cycle * 4, events


TONES = {
    "rt_wake_strong": tone_wake_strong,
    "rt_leave_chime": tone_leave_chime,
    "rt_gentle_chord": tone_gentle_chord,
    "rt_digital_beep": tone_digital_beep,
    "rt_sunrise_rise": tone_sunrise_rise,
}


def write_wav(path, samples):
    peak = max(abs(x) for x in samples) or 1.0
    scale = 0.891 / peak  # -1 dBFS
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, x * scale)) * 32767)) for x in samples))


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, fn in TONES.items():
        length, events = fn()
        samples = render(length, events)
        tail = max(abs(x) for x in samples[-int(0.02 * SR):]) / (max(abs(x) for x in samples) or 1)
        path = os.path.join(OUT_DIR, name + ".wav")
        write_wav(path, samples)
        print(f"{name}.wav  {length:.2f}s  {os.path.getsize(path) // 1024} KB  檔尾殘響 {tail:.4f}")


if __name__ == "__main__":
    main()
