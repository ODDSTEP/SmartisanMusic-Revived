#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
[1.4.24·R32 爆豆音密度] 把 20 段 5.196s 的环境噪声采样，裁成「单次爆豆」的 0.15~0.25s 片段。

问题：原采样每段 5.2 秒，内含 7 个瞬态（约 1.35 次/秒）；播放层每 2~4 秒触发一次，
两段一重叠，有效密度反而升到 ~2.1 次/秒，用户听感是「爆豆音太密、不像真实磨损」。

做法：
1. 解成 16bit 单声道 PCM；
2. 用 5ms 窗找能量最集中的瞬态峰值；
3. 以峰值为中心裁 150~250ms（峰值前 40ms、峰值后 110~210ms，按段随机）；
4. 两端各 3ms/25ms 淡入淡出，消除裁剪爆音；
5. 重新编码为 ogg（libvorbis q=4），覆盖原资源；
6. 原始素材先备份到 tools/popcorn_orig/，可随时还原。

这样 SoundPool（共享输出）与 USB 混音（独占）两条路径**同时**变成单次短爆豆，
不需要在播放层做截断，也不会出现「共享输出仍然很密」的割裂。
"""
import os
import shutil
import struct
import subprocess
import sys
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW_DIR = os.path.join(ROOT, "app/src/main/res/raw")
BACKUP_DIR = os.path.join(ROOT, "tools/popcorn_orig")
SAMPLE_RATE = 48000
TARGET_PRE_MS = 40
MIN_TAIL_MS = 110
MAX_TAIL_MS = 210


def decode(path: str) -> bytes:
    out = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "s16le", "-ac", "1",
         "-ar", str(SAMPLE_RATE), "-"],
        capture_output=True, check=True,
    )
    return out.stdout


def find_peak(pcm: bytes) -> int:
    count = len(pcm) // 2
    samples = struct.unpack("<%dh" % count, pcm)
    win = max(1, SAMPLE_RATE // 200)  # 5ms
    best_energy = -1
    best_idx = 0
    for start in range(0, count - win, win):
        energy = sum(abs(s) for s in samples[start:start + win])
        if energy > best_energy:
            best_energy = energy
            best_idx = start
    return best_idx


def trim(pcm: bytes, idx: int, pre_ms: int, tail_ms: int) -> bytes:
    count = len(pcm) // 2
    start = max(0, idx - SAMPLE_RATE * pre_ms // 1000)
    end = min(count, idx + SAMPLE_RATE * tail_ms // 1000)
    samples = list(struct.unpack("<%dh" % (end - start), pcm[start * 2:end * 2]))
    n = len(samples)
    fade_in = max(1, SAMPLE_RATE * 3 // 1000)
    fade_out = max(1, SAMPLE_RATE * 25 // 1000)
    for i in range(min(fade_in, n)):
        samples[i] = int(samples[i] * i / fade_in)
    for i in range(min(fade_out, n)):
        samples[n - 1 - i] = int(samples[n - 1 - i] * i / fade_out)
    return struct.pack("<%dh" % n, *samples)


def main() -> int:
    if not os.path.isdir(RAW_DIR):
        print("找不到 raw 目录", file=sys.stderr)
        return 1
    os.makedirs(BACKUP_DIR, exist_ok=True)
    files = sorted(f for f in os.listdir(RAW_DIR) if f.startswith("y_") and f.endswith(".ogg"))
    if not files:
        print("没有找到 y_*.ogg", file=sys.stderr)
        return 1
    print(f"共 {len(files)} 个采样")
    for i, name in enumerate(files):
        src = os.path.join(RAW_DIR, name)
        backup = os.path.join(BACKUP_DIR, name)
        if not os.path.exists(backup):
            shutil.copy2(src, backup)
        pcm = decode(backup)
        idx = find_peak(pcm)
        # 每段尾巴长度不同（110~210ms），避免 20 段听起来一模一样
        tail = MIN_TAIL_MS + (i * 37) % (MAX_TAIL_MS - MIN_TAIL_MS + 1)
        body = trim(pcm, idx, TARGET_PRE_MS, tail)
        tmp = src + ".tmp.ogg"
        subprocess.run(
            ["ffmpeg", "-v", "error", "-y", "-f", "s16le", "-ar", str(SAMPLE_RATE),
             "-ac", "1", "-i", "-", "-c:a", "libvorbis", "-q:a", "4", tmp],
            input=body, check=True,
        )
        os.replace(tmp, src)
        dur = (TARGET_PRE_MS + tail) / 1000.0
        print(f"{name}: peak@{idx / SAMPLE_RATE:.3f}s -> {dur:.3f}s "
              f"({os.path.getsize(backup)} -> {os.path.getsize(src)} bytes)")
    print("完成。原始素材已备份到 tools/popcorn_orig/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
