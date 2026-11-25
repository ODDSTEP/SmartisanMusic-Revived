#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <pthread.h>
#include <time.h>
#include <math.h>
#include <stdio.h>
#include <android/log.h>

#define LOG_TAG "ChuiziUsb"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define MAX_URBS 8
#define MAX_PKTS 32
/* [1.4.12·S03] 每包增益平滑步长：48kHz 下一包约 4ms，0.06/包 ≈ 70ms 内完成满程渐变，
 * 足够快到听感"即时"，又足以消除包边界的波形阶跃爆音。 */
#define S03_GAIN_STEP 0.06f

/**
 * 原生 UAC 直出：通过 usbfs 的 isochronous URB 把 PCM 直接塞给 USB DAC。
 *
 * 这是"真独占"的关键——不走 AudioFlinger / USB 音频 HAL，也就没有系统混音与 SRC。
 * 前提是上层已经用 UsbDeviceConnection 强制接管（detach 内核 snd-usb-audio）并设好 alt setting。
 *
 * 发送节奏：
 *   - 全速（FS）：每 1ms 一个包
 *   - 高速（HS）：每 125µs 一个包
 *
 * 速率跟随（async 端点）：
 *   - 有反馈端点时按 UAC 规范读它（UAC1 = 3 字节 10.14，UAC2 = 4 字节 16.16），
 *     直接用它决定每包采样数——这是唯一能保证"原样透传"的做法；
 *   - 没有反馈端点（synchronous / adaptive 设备）才退回吞吐量自校准。
 *
 * 硬约束：每个 isochronous 包的 length **不能超过端点的 wMaxPacketSize**，
 * 否则内核 usb_submit_urb() 会整条 URB 返回 -EMSGSIZE，表现为"突然断流"。
 * 所以所有校准都必须以 maxPacketBytes 为上限。
 */
struct Sink {
    int fd;
    int ep;
    int rate;
    int ch;
    int bps;
    int frameBytes;
    int npkts;
    double spp;          // 每个包的采样数（可运行时校准 / 跟随反馈）
    double spp0;         // 理论值（rate × packetMs）
    double frac;         // 小数累加器
    int maxPacketBytes;  // 端点每包容量（含 high-bandwidth 倍数）
    int urbBytes;        // 每个 URB 缓冲区实际分配的大小（fill_urb 的硬边界）
    int calibrated;      // 是否完成过一次校准

    /* 反馈端点（0 = 设备不需要反馈） */
    int fbEp;
    int fbBytes;
    int fbValid;
    int fbCount;
    int fbErr;           // 连续失败的反馈 URB 数，超阈值就放弃反馈
    double fbSpp;

    pthread_t th;
    pthread_mutex_t m;
    pthread_cond_t cv;

    uint8_t *ring;
    size_t cap;
    size_t used;
    size_t head;
    size_t tail;

    volatile int closing;
    volatile int paused;
    volatile int started;
    volatile int failed;
    /* 软件音量增益（0..1）：在"输出端"逐包应用，而非在写入环形缓冲时应用。
     * 这样环形缓冲可以保持很长（吸收抖动、后台不卡顿），而音量调节即时生效
     * （增益只对"将要提交"的样本生效，已缓冲的旧样本不再有延迟）。 */
    volatile float gain;        // [1.4.12·S03] 实际施加增益（worker 内平滑后的值）
    volatile float targetGain;  // [1.4.12·S03] Kotlin 侧推送的目标增益
    /* [v22] TPDF 抖动：数字衰减会等效降低位深，量化前加 ±1 LSB 的三角分布噪声，
     * 把"截断失真"换成听不见的白噪，低音量下尤其明显。满档不加（不碰 bit-perfect）。 */
    volatile int dither;
    uint32_t rng;
    volatile int flushReq;
    /* flush 的请求/完成序号：nativeFlush 要等 worker 真正清完才返回，
     * 否则 seek 之后新写进来的数据可能被"迟到的 flush"一起清掉 */
    int flushSeq;
    int flushDone;

    /* framesPlayed 只累计"真正来自音频缓冲"的帧：
     * 暂停时补的静音、欠载补的静音都不算，否则暂停后进度条还会一直走。 */
    int64_t framesPlayed;
    int64_t silenceFrames;
    int64_t underrunPkts;
    int64_t submittedUrbs;
    int64_t errCount;
    /* 每个 iso 包的传输结果：内核在 actual_length 里告诉我们设备实际收了多少。
     * 以前只看 URB 的 status（iso 传输里它经常是 0），溢出/欠载完全不可见。 */
    int64_t sentBytes;
    int64_t shortPkts;   // actual_length < length：设备没吃下（我们发太快 / FIFO 满）
    int64_t pktErr;      // 包级 status != 0（-EOVERFLOW / -EXDEV 等）
    /* URB 完成间隔取证：正常情况一个 URB 的音频时长完成一次（HS 约 4ms）。
     * maxGap 显著大于它 = DAC 出现过"断粮"空洞——正是短暂小卡顿的直接证据。 */
    int64_t lastDoneUs;
    int64_t maxGapUs;
    int64_t gapOverCount;

    /* 看门狗：ring 满且 framesPlayed 长时间不动 = URB 再也不会完成（设备停了 /
     * 控制器卡了）。以前这种情况会永远"正在缓冲"——写入超时丢帧、进度 00:00。
     * 现在判 failed 让上层立刻重建独占，把"卡死"变成一次短暂的自动恢复。 */
    int64_t watchdogFrames;
    int64_t watchdogSinceUs;
    int64_t watchdogTrips;
    /* 1.2.4：nativeWrite 超时丢弃的次数。看门狗用它区分"用户真暂停了"
     * （ring 满、无人写入）和"暂停错位卡死"（ExoPlayer 还在灌数据、
     * 每一笔都超时被丢、进度 00:00）——后者必须判失败自动重建。 */
    int64_t writeDrops;
    int64_t watchdogDrops;
    /* 周期取证：每 5 秒把 worker 侧关键计数打包，供上层 AppLog 采样 */
    int64_t lastStatsUs;

    /* 吞吐校准：用一段时间内实际送出的音频帧数 / 实际耗时，反推真实采样率。
     * 只统计"缓冲一直有货"的窗口，避免把网络卡顿误当成设备太慢。
     * 有反馈端点时这套完全不启用。 */
    double measuredHz;
    double ivRatio;
    int calPasses;
    int64_t winStartUs;
    int64_t winFrames;
    int winBad;
    int64_t ivWinStartUs;
    int64_t ivExpSumUs;
    int ivUrbs;
    int ivBadDir;
    int ivBadCount;
    int tpBadDir;
    int tpBadCount;
    /* 传输卡死后的稳定期：这段时间内不允许校准，
     * 否则会把"3.5 秒只送出 0.24 秒音频"当成设备太慢，把包长放大好几倍。 */
    int stallSkip;
    int forbidSuspendOk;     // USBDEVFS_FORBID_SUSPEND 是否被内核接受
    int64_t urbAudioUs;      // 单个 URB 的音频时长（µs），用于判定完成间隔异常
    char calLog[8][112];
    int calLogIdx;

    struct usbdevfs_urb *urb[MAX_URBS];
    uint8_t *buf[MAX_URBS];
    int inFlight[MAX_URBS];
    int64_t audioFrames[MAX_URBS];
    int64_t reqFrames[MAX_URBS];

    struct usbdevfs_urb *fbUrb;
    uint8_t *fbBuf;
    int fbInFlight;
};

static size_t ring_read(Sink *s, uint8_t *out, size_t n) {
    size_t got = 0;
    while (got < n && s->used > 0) {
        size_t chunk = s->cap - s->tail;
        if (chunk > n - got) chunk = n - got;
        if (chunk > s->used) chunk = s->used;
        memcpy(out + got, s->ring + s->tail, chunk);
        s->tail = (s->tail + chunk) % s->cap;
        s->used -= chunk;
        got += chunk;
    }
    return got;
}

/**
 * 在输出端逐包应用软件音量增益（增益在"即将提交给 DAC"的样本上生效，
 * 而非在写入环形缓冲时生效）。这样已缓冲的样本也能即时反映最新音量，
 * 缓冲再长也不产生调节延迟；且仅做一次量化，不损害音质。
 */
/* [v22] xorshift32：每个样本要两次随机数，rand() 有锁且慢，这里用无锁状态机。 */
static inline uint32_t xs32(uint32_t *state) {
    uint32_t x = *state;
    if (x == 0) x = 0x9E3779B9u;
    x ^= x << 13;
    x ^= x >> 17;
    x ^= x << 5;
    *state = x;
    return x;
}

/** TPDF：两个均匀分布之差，范围 [-1, 1) LSB，峰峰 2 LSB。 */
static inline float tpdf_noise(Sink *s) {
    const float k = 1.0f / 16777216.0f;   // 2^24
    float a = (float) (xs32(&s->rng) >> 8) * k;
    float b = (float) (xs32(&s->rng) >> 8) * k;
    return a + b - 1.0f;
}

static void apply_gain(Sink *s, uint8_t *b, int nbytes, float g) {
    if (g == 1.0f) return;                 // 满档：原样送出，不引入任何运算
    int bps = s->bps;
    int n = nbytes / bps;
    if (n <= 0) return;
    for (int i = 0; i < n; i++) {
        uint8_t *p = b + (size_t) i * bps;
        if (bps == 2) {
            int32_t v = (int32_t) (int16_t) ((uint16_t) p[0] | ((uint16_t) p[1] << 8));
            float n = s->dither ? tpdf_noise(s) : 0.0f;
            int32_t r = (int32_t) lrintf((float) v * g + n);
            if (r > 32767) r = 32767; else if (r < -32768) r = -32768;
            p[0] = (uint8_t) (r & 0xFF);
            p[1] = (uint8_t) ((r >> 8) & 0xFF);
        } else if (bps == 3) {
            int32_t v = ((int32_t) p[0]) | ((int32_t) p[1] << 8) | ((int32_t) p[2] << 16);
            if (v & 0x800000) v |= (int32_t) 0xFF000000;   // 符号扩展到 32 位
            float n = s->dither ? tpdf_noise(s) : 0.0f;
            int32_t r = (int32_t) lrintf((float) v * g + n);
            if (r > 8388607) r = 8388607; else if (r < -8388608) r = -8388608;
            p[0] = (uint8_t) (r & 0xFF);
            p[1] = (uint8_t) ((r >> 8) & 0xFF);
            p[2] = (uint8_t) ((r >> 16) & 0xFF);
        } else if (bps == 4) {
            int32_t v = (int32_t) ((uint32_t) p[0] | ((uint32_t) p[1] << 8) |
                                  ((uint32_t) p[2] << 16) | ((uint32_t) p[3] << 24));
            float n = s->dither ? tpdf_noise(s) : 0.0f;
            int64_t r = (int64_t) lrintf((float) v * g + n);
            if (r > 2147483647LL) r = 2147483647LL;
            else if (r < -2147483648LL) r = -2147483648LL;
            uint32_t ru = (uint32_t) r;
            p[0] = (uint8_t) (ru & 0xFF);
            p[1] = (uint8_t) ((ru >> 8) & 0xFF);
            p[2] = (uint8_t) ((ru >> 16) & 0xFF);
            p[3] = (uint8_t) ((ru >> 24) & 0xFF);
        }
        /* bps==1（8bit）极少见于 USB DAC，跳过增益避免符号/截断问题 */
    }
}

static int64_t nowUs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000LL + ts.tv_nsec / 1000;
}

/** 一个包最多能装多少采样：受"分配大小"和"端点 wMaxPacketSize"双重约束 */
static int max_samples_per_packet(Sink *s) {
    int limit = s->maxPacketBytes > 0 ? s->maxPacketBytes
                                      : (int) ((s->spp0 * 8.0 + 3.0) * s->frameBytes);
    int n = limit / s->frameBytes;
    if (n < 1) n = 1;
    return n;
}

static int fill_urb(Sink *s, int idx) {
    struct usbdevfs_urb *u = s->urb[idx];
    uint8_t *p = s->buf[idx];
    size_t total = 0;
    int64_t frames = 0;
    int64_t reqFrames = 0;
    int maxN = max_samples_per_packet(s);

    u->number_of_packets = s->npkts;
    pthread_mutex_lock(&s->m);
    for (int k = 0; k < s->npkts; k++) {
        s->frac += s->spp;
        int n = (int) s->frac;
        s->frac -= n;
        /* 校准/反馈可能把 spp 往上调。超过端点容量就会被内核 -EMSGSIZE 拒掉整条 URB，
         * 所以这里按 max_samples_per_packet() 硬限幅。 */
        if (n > maxN) n = maxN;
        if (n < 0) n = 0;
        int bytes = n * s->frameBytes;

        if (total + (size_t) bytes > (size_t) s->urbBytes) {
            // 理论不可达；宁可少发一个包，也不能越界
            u->number_of_packets = k;
            break;
        }
        int got = 0;
        if (!s->paused && s->used > 0) {
            got = (int) ring_read(s, p + total, (size_t) bytes);
        }
        /* 软件音量在输出端逐包应用：已缓冲的旧样本也能即时反映最新音量，
         * 环形缓冲再长也不产生调节延迟；满档(g==1)时 apply_gain 直接返回。 */
        if (got > 0) {
            /* [1.4.12·S03] 增益平滑（gain ramp）：音量档位跳变时若直接把新增益乘到样本上，
             * 波形会在包边界出现阶跃 → DAC 上听到"咔哒/怪声"。这里让实际增益逐包逼近
             * 目标值（每包最多移动 S03_GAIN_STEP），几个包内完成、听觉上仍是"即时"，
             * 但消除了阶跃。满档与目标一致时不产生任何额外运算，bit-perfect 不变。 */
            float cur = s->gain;
            float tgt = s->targetGain;
            if (cur != tgt) {
                float gap = tgt - cur;              // 本包开始时的剩余差（含符号）
                float step = gap;
                if (step > S03_GAIN_STEP) step = S03_GAIN_STEP;
                else if (step < -S03_GAIN_STEP) step = -S03_GAIN_STEP;
                cur += step;
                // 本步越过目标（步长方向上的剩余差反号）则精确落位，避免抖动收敛不了
                if ((tgt - cur) * gap <= 0.f) cur = tgt;
                s->gain = cur;
            }
            apply_gain(s, p + total, got, s->gain);
        }
        if (got < bytes) {
            memset(p + total + got, 0, (size_t) (bytes - got));
            if (!s->paused) s->underrunPkts++;
        }
        frames += got / s->frameBytes;
        reqFrames += n;

        u->iso_frame_desc[k].length = (unsigned int) bytes;
        u->iso_frame_desc[k].actual_length = 0;
        u->iso_frame_desc[k].status = 0;
        total += (size_t) bytes;
    }
    /* 腾出了环形缓冲的空位，唤醒可能正阻塞在 nativeWrite 里的解码线程。
     * 少了这一下，写线程只能靠 10ms 的 timedwait 超时自己醒来，写入被切成
     * 一段一段，高码率下容易出现可闻的抖动。 */
    if (frames > 0) pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->m);

    /* 全 0 长度的 URB 有些 HCD 会直接 EINVAL；补一个最小包兜底 */
    if (total == 0) {
        int bytes = s->frameBytes;
        if ((size_t) bytes > (size_t) s->urbBytes) bytes = s->urbBytes;
        if (bytes < 1) bytes = 1;
        memset(p, 0, (size_t) bytes);
        u->number_of_packets = 1;
        u->iso_frame_desc[0].length = (unsigned int) bytes;
        u->iso_frame_desc[0].actual_length = 0;
        u->iso_frame_desc[0].status = 0;
        total = (size_t) bytes;
    }

    u->buffer = p;
    u->buffer_length = (int) total;
    s->audioFrames[idx] = frames;
    s->reqFrames[idx] = reqFrames;
    return 0;
}

/**
 * 按实测比值修正每个包的采样数。[ratio] = 实测速度 / 目标速度。
 *
 * 上限必须同时满足两条：
 *  1. 不超过端点 wMaxPacketSize 能装下的采样数——否则内核会整条 URB 返回 -EMSGSIZE，
 *     表现就是"突然断流"（这是历史日志里 6.5 倍速 / 断流反复出现的根因）；
 *  2. 单步限幅，避免一次误判把包长放大好几倍。
 */
static void apply_calibration(Sink *s, double ratio, const char *why) {
    if (s->calPasses >= 6 || ratio <= 0.02 || ratio > 50.0) return;
    if (s->fbValid) return;   // 有反馈端点时以反馈为准，别用吞吐量瞎猜
    double ns = s->spp / pow(ratio, 0.92);
    if (ratio < 1.0) {
        double cap = s->spp * 1.5;
        if (ns > cap) ns = cap;
    } else {
        double floor = s->spp / 6.0;
        if (ns < floor) ns = floor;
    }
    double capSamples = (double) max_samples_per_packet(s);
    double hi = s->spp0 * 8.0;
    if (hi > capSamples) hi = capSamples;
    double lo = s->spp0 * 0.25;
    if (lo < 0.5) lo = 0.5;
    if (ns < lo) ns = lo;
    if (ns > hi) ns = hi;
    snprintf(s->calLog[s->calLogIdx % 8], sizeof(s->calLog[0]),
             "#%d %s ratio=%.2f spp=%.2f->%.2f", s->calPasses, why, ratio, s->spp, ns);
    s->calLogIdx++;
    LOGI("calibrate %s", s->calLog[(s->calLogIdx - 1) % 8]);
    s->spp = ns;
    s->frac = 0;
    s->calPasses++;
    s->calibrated = 1;
    // 修正后两个窗口都重新统计
    s->winStartUs = 0;
    s->ivWinStartUs = 0;
    s->ivBadDir = 0;
    s->ivBadCount = 0;
    s->tpBadDir = 0;
    s->tpBadCount = 0;
}

/**
 * 稳定后的残余精调：把 spp 慢慢拉回理论值。
 * 只有在"当前 spp 已经和理论值相差不大"（说明没有 HS/FS 误判）时才做，
 * 免得把一次正确的 8 倍校准又拉回错误值。
 */
static void trim_to_nominal(Sink *s) {
    if (s->spp0 <= 0 || s->fbValid) return;
    double rel = s->spp / s->spp0;
    if (rel < 0.7 || rel > 1.4) return;              // 差太远：说明理论值本身不适用
    double diff = s->spp0 - s->spp;
    if (fabs(diff) < s->spp0 * 0.003) return;        // 已经足够接近
    double ns = s->spp + diff * 0.25;
    snprintf(s->calLog[s->calLogIdx % 8], sizeof(s->calLog[0]),
             "#tr trim spp=%.3f->%.3f (nominal=%.3f)", s->spp, ns, s->spp0);
    s->calLogIdx++;
    LOGI("calibrate %s", s->calLog[(s->calLogIdx - 1) % 8]);
    s->spp = ns;
    s->frac = 0;
}

static int submit_urb(Sink *s, int idx) {
    fill_urb(s, idx);
    s->urbAudioUs = (double) s->reqFrames[idx] * 1000000.0 / (s->rate > 0 ? s->rate : 48000);
    int r = ioctl(s->fd, USBDEVFS_SUBMITURB, s->urb[idx]);
    if (r < 0) {
        int e = errno;
        /* 包长超端点容量：内核会拒整条 URB。这是"参数错"不是"设备死"，
         * 退回理论值重试一次，别直接把流判死。 */
        if ((e == EMSGSIZE || e == EOVERFLOW || e == EINVAL) && s->spp > s->spp0) {
            LOGI("submit rejected (%s), spp %.2f -> %.2f", strerror(e), s->spp, s->spp0);
            s->spp = s->spp0;
            s->frac = 0;
            s->calPasses = 6;          // 别再自动校准了，回到理论值跑
            fill_urb(s, idx);
            r = ioctl(s->fd, USBDEVFS_SUBMITURB, s->urb[idx]);
            e = errno;
        }
        if (r < 0) {
            LOGE("submit failed: %s", strerror(e));
            s->failed = 1;
            return -1;
        }
    }
    s->inFlight[idx] = 1;
    s->submittedUrbs++;
    return 0;
}

static void submit_feedback(Sink *s) {
    if (s->fbEp == 0 || s->fbUrb == NULL || s->fbInFlight) return;
    s->fbUrb->number_of_packets = 1;
    s->fbUrb->iso_frame_desc[0].length = (unsigned int) s->fbBytes;
    s->fbUrb->iso_frame_desc[0].actual_length = 0;
    s->fbUrb->iso_frame_desc[0].status = 0;
    s->fbUrb->buffer = s->fbBuf;
    s->fbUrb->buffer_length = s->fbBytes;
    if (ioctl(s->fd, USBDEVFS_SUBMITURB, s->fbUrb) < 0) {
        LOGI("feedback submit failed: %s (disable fb)", strerror(errno));
        s->fbEp = 0;
        s->fbValid = 0;
        return;
    }
    s->fbInFlight = 1;
}

/**
 * 反馈端点解码。
 *
 *  - UAC1：3 字节小端，10.14 无符号定点 → 每包采样数 = raw / 16384
 *  - UAC2：4 字节小端，16.16 有符号定点 → 每包采样数 = raw / 65536
 *
 * 反馈值本身有抖动，做一阶低通；同时限幅在 [0.5×spp0, 1.5×spp0] 且不超过端点容量，
 * 免得一次坏数据把流打飞。
 */
static void on_feedback(Sink *s, int actual) {
    if (actual < s->fbBytes) return;
    uint32_t raw = 0;
    for (int i = 0; i < s->fbBytes; i++) raw |= ((uint32_t) s->fbBuf[i]) << (8 * i);
    double v;
    if (s->fbBytes == 4) v = ((double) (int32_t) raw) / 65536.0;
    else v = ((double) raw) / 16384.0;
    if (v <= 0.0 || v > s->spp0 * 16.0) return;          // 明显不是合法反馈
    double capSamples = (double) max_samples_per_packet(s);
    double lo = s->spp0 * 0.5;
    double hi = s->spp0 * 1.5;
    if (hi > capSamples) hi = capSamples;
    if (lo < 0.5) lo = 0.5;
    if (!s->fbValid) s->fbSpp = v;
    else s->fbSpp = s->fbSpp * 0.9 + v * 0.1;
    if (s->fbSpp < lo) s->fbSpp = lo;
    if (s->fbSpp > hi) s->fbSpp = hi;
    s->fbValid = 1;
    s->fbCount++;
    s->spp = s->fbSpp;
}

/** 丢弃所有在飞的 URB 并等它们回到用户态。返回 0 表示干净了 */
static int drain_inflight(Sink *s) {
    for (int i = 0; i < MAX_URBS; i++) {
        if (s->inFlight[i]) ioctl(s->fd, USBDEVFS_DISCARDURB, s->urb[i]);
    }
    for (int spin = 0; spin < 200; spin++) {
        int left = 0;
        for (int i = 0; i < MAX_URBS; i++) if (s->inFlight[i]) left++;
        if (left == 0) return 0;
        struct usbdevfs_urb *d = NULL;
        if (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &d) == 0 && d != NULL) {
            if (d == s->fbUrb) {
                s->fbInFlight = 0;
            } else {
                for (int i = 0; i < MAX_URBS; i++) {
                    if (s->urb[i] == d) { s->inFlight[i] = 0; break; }
                }
            }
        } else {
            usleep(500);
        }
    }
    return -1;
}

static void *worker(void *arg) {
    Sink *s = (Sink *) arg;
    /* 送 URB 的线程比解码线程更该实时：设不上就设不上，不影响功能 */
    if (setpriority(PRIO_PROCESS, 0, -16) != 0) {
        LOGI("setpriority(audio) failed: %s", strerror(errno));
    }
    for (int i = 0; i < MAX_URBS; i++) {
        if (submit_urb(s, i) < 0) break;
    }
    submit_feedback(s);

    int idle = 0;
    while (!s->closing) {
        if (s->failed) break;

        if (s->flushReq) {
            s->flushReq = 0;
            drain_inflight(s);
            pthread_mutex_lock(&s->m);
            s->used = 0;
            s->head = 0;
            s->tail = 0;
            s->flushDone = s->flushSeq;
            pthread_mutex_unlock(&s->m);
            pthread_cond_broadcast(&s->cv);
            s->frac = 0;
            s->lastDoneUs = 0;
            /* 只补发真正回到用户态的那些：drain 失败（设备已经掉了）时
             * 重复提交还在队列里的 URB 会 EBUSY，直接把流判死 */
            for (int i = 0; i < MAX_URBS; i++) {
                if (s->inFlight[i]) continue;
                if (submit_urb(s, i) < 0) break;
            }
            submit_feedback(s);
            continue;
        }

        struct usbdevfs_urb *done = NULL;
        int r = ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &done);
        if (r < 0) {
            if (errno == EAGAIN) {
                /* 不用阻塞式 REAPURB：usbfs 没有超时，关流时可能永久挂住。
                 * 自适应短睡：刚空闲时 100µs，连续空闲才退到 1ms。 */
                usleep(idle < 10 ? 100 : 1000);
                if (idle < 100) idle++;
                continue;
            }
            if (errno == EINTR) continue;
            LOGE("reap failed: %s", strerror(errno));
            s->failed = 1;
            break;
        }
        idle = 0;
        if (done == NULL) {
            usleep(200);
            continue;
        }

        if (done == s->fbUrb) {
            s->fbInFlight = 0;
            if (done->status == 0) {
                s->fbErr = 0;
                on_feedback(s, done->iso_frame_desc[0].actual_length);
            } else if (++s->fbErr > 8) {
                /* 反馈端点一直报错（有些固件的 fb 端点只在特定 alt 下有效）：
                 * 放弃反馈，退回吞吐量自校准，别让一条坏端点拖住整个流 */
                LOGI("feedback disabled after %d errors (status=%d)", s->fbErr, done->status);
                s->fbEp = 0;
                s->fbValid = 0;
            }
            if (!s->closing) submit_feedback(s);
            continue;
        }

        int idx = -1;
        for (int i = 0; i < MAX_URBS; i++) {
            if (s->urb[i] == done) { idx = i; break; }
        }
        if (idx < 0) continue;

        /* 进度只跟"真正写进 DAC 的音频帧"走。
         * 以前用 actual_length：暂停时我们会补静音保住数据流，length 照样是满的，
         * 于是暂停后 framesPlayed 继续涨，界面就出现"声音停了、秒数还在跳"。
         * 现在用提交这个 URB 时从环形缓冲里真正取到的帧数，暂停/欠载天然为 0。 */
        int64_t played = s->audioFrames[idx];

        /* 包级取证：actual_length < length 说明设备没吃下（我们发太快），
         * status != 0 说明这个包被内核标了错（-EOVERFLOW 等）。 */
        int64_t sent = 0;
        for (int k = 0; k < done->number_of_packets; k++) {
            sent += (int64_t) done->iso_frame_desc[k].length;
            if (done->iso_frame_desc[k].status != 0) s->pktErr++;
            else if ((int) done->iso_frame_desc[k].actual_length <
                     (int) done->iso_frame_desc[k].length) s->shortPkts++;
        }

        pthread_mutex_lock(&s->m);
        s->framesPlayed += played;
        s->silenceFrames += (s->reqFrames[idx] - played);
        s->sentBytes += sent;
        pthread_mutex_unlock(&s->m);
        s->inFlight[idx] = 0;

        if (done->status != 0) s->errCount++;

        /* 完成间隔取证：正常 = 一个 URB 的音频时长（HS 约 4ms）。
         * 超过 2 倍说明 DAC 那一瞬间断粮了——用户听到的"咯噔"就是它。 */
        {
            int64_t now = nowUs();
            if (s->lastDoneUs != 0) {
                int64_t gap = now - s->lastDoneUs;
                if (gap > s->maxGapUs) s->maxGapUs = gap;
                int64_t expect = s->urbAudioUs > 0 ? s->urbAudioUs : 4000;
                if (gap > expect * 2) s->gapOverCount++;
            }
            s->lastDoneUs = now;
        }

        /* 1.2.4 看门狗不再受 paused 屏蔽：拖进度条/暂停错位时 ExoPlayer 仍会
         * 灌数据——ring 满、进度 00:00、每一笔写入都超时被丢。旧逻辑在
         * paused 时跳过看门狗（日志 wdog=0 证实），导致"永远正在缓冲"。
         * 现在：ring 满 && 进度 3 秒没动 &&（在播放 或 写入正在被丢弃）
         * 才判失败让上层重建；真暂停（无人写入）无害重置。 */
        {
            int64_t now = nowUs();
            size_t usedNow;
            pthread_mutex_lock(&s->m);
            usedNow = s->used;
            pthread_mutex_unlock(&s->m);
            if (usedNow == s->cap && s->framesPlayed == s->watchdogFrames) {
                if (s->watchdogSinceUs == 0) {
                    s->watchdogSinceUs = now;
                } else if (now - s->watchdogSinceUs > 3000000LL) {
                    int dropsGrowing = s->writeDrops != s->watchdogDrops;
                    if (!s->paused || dropsGrowing) {
                        LOGE("watchdog: ring full, no progress for 3s "
                             "(paused=%d drops=%lld wdrops0=%lld "
                             "frames=%lld err=%lld short=%lld pkterr=%lld under=%lld fb=%d/%d) -> fail",
                             s->paused,
                             (long long) s->writeDrops, (long long) s->watchdogDrops,
                             (long long) s->framesPlayed, (long long) s->errCount,
                             (long long) s->shortPkts, (long long) s->pktErr,
                             (long long) s->underrunPkts,
                             s->fbEp != 0 ? 1 : 0, s->fbValid);
                        s->watchdogTrips++;
                        s->watchdogSinceUs = 0;
                        s->failed = 1;
                        break;
                    }
                    /* 用户真按了暂停：重置计时，等恢复播放 */
                    s->watchdogSinceUs = 0;
                    s->watchdogDrops = s->writeDrops;
                }
            } else {
                s->watchdogFrames = s->framesPlayed;
                s->watchdogSinceUs = 0;
                s->watchdogDrops = s->writeDrops;
            }
        }

        if (!s->paused) {
            int64_t now = nowUs();

            /* ① 包节奏窗口：URB 的音频时长 / 实际完成间隔。
             * 这个值和"缓冲里有没有货"无关，所以能最快发现 8 倍速；
             * 连续两个窗口同方向异常才动手，避免启动时的完成突发造成误判。
             * 另外 ivRatio < 0.4 意味着"这段时间的墙钟里，设备只播了不到 40% 的
             * 音频时长"——那是传输卡死（锁屏/挂起），不是设备慢，必须整段丢弃。 */
            if (s->submittedUrbs >= 8) {
                if (s->ivWinStartUs == 0) {
                    s->ivWinStartUs = now;
                    s->ivExpSumUs = 0;
                    s->ivUrbs = 0;
                }
                s->ivExpSumUs += s->reqFrames[idx] * 1000000LL / (s->rate > 0 ? s->rate : 48000);
                s->ivUrbs++;
                if (now - s->ivWinStartUs >= 400000) {
                    if (s->ivUrbs >= 4 && s->calPasses < 6 && !s->fbValid) {
                        double expAvg = (double) s->ivExpSumUs / (double) s->ivUrbs;
                        double ivAvg = (double) (now - s->ivWinStartUs) / (double) s->ivUrbs;
                        if (ivAvg > 100.0 && expAvg > 100.0) {
                            double ratio = expAvg / ivAvg;
                            s->ivRatio = ratio;
                            if (ratio < 0.4) {
                                /* 卡死窗口：丢掉，并让接下来两个窗口进入稳定期 */
                                s->stallSkip = 2;
                                s->ivBadDir = 0;
                                s->ivBadCount = 0;
                                s->tpBadDir = 0;
                                s->tpBadCount = 0;
                                LOGI("stall window: iv=%.2f urbs=%d", ratio, s->ivUrbs);
                            } else if (s->stallSkip > 0) {
                                s->ivBadDir = 0;
                                s->ivBadCount = 0;
                            } else {
                                int dir = ratio > 1.10 ? 1 : (ratio < 0.90 ? -1 : 0);
                                if (dir == 0) {
                                    s->ivBadDir = 0;
                                    s->ivBadCount = 0;
                                } else if (dir == s->ivBadDir) {
                                    s->ivBadCount++;
                                } else {
                                    s->ivBadDir = dir;
                                    s->ivBadCount = 1;
                                }
                                if (s->ivBadCount >= 2) {
                                    apply_calibration(s, ratio, "iv");
                                    s->ivBadDir = 0;
                                    s->ivBadCount = 0;
                                }
                            }
                        }
                    }
                    s->ivWinStartUs = now;
                    s->ivExpSumUs = 0;
                    s->ivUrbs = 0;
                }
            }

            /* ② 吞吐长窗口（兜底）：统计 900ms 内真实送出的帧数。
             * 太快一定要修：即使缓冲见底，实测也只会低估设备速度；
             * 太慢只在缓冲始终有货时才修，否则可能是网络/解码跟不上。
             * 死区收紧到 ±3%，配合稳定期的精调把残余偏差（曾留下 -4.2%）抹掉。
             * 有反馈端点时整段跳过——反馈值才是准的。 */
            if (s->fbValid) {
                s->winStartUs = 0;
            } else if (s->winStartUs == 0) {
                s->winStartUs = now;
                s->winFrames = s->framesPlayed;
                s->winBad = 0;
            }
            if (s->winStartUs != 0) {
                pthread_mutex_lock(&s->m);
                int low = s->used < s->cap / 8;
                pthread_mutex_unlock(&s->m);
                if (low) s->winBad = 1;
                int64_t winUs = now - s->winStartUs;
                if (winUs >= 900000) {
                    double got = (double) (s->framesPlayed - s->winFrames);
                    double secs = (double) winUs / 1000000.0;
                    if (s->calPasses < 6 && got > s->rate * 0.15) {
                        double hz = got / secs;
                        s->measuredHz = hz;
                        double ratio = hz / (double) (s->rate > 0 ? s->rate : 48000);
                        /* 缓冲是满的却只送出这么点 → 传输卡死，不是设备慢 */
                        int stalled = (!s->winBad && ratio < 0.4) ||
                            (s->ivRatio > 0.01 && s->ivRatio < 0.4);
                        if (stalled) {
                            s->stallSkip = 2;
                            s->tpBadDir = 0;
                            s->tpBadCount = 0;
                            LOGI("stall window: tp ratio=%.2f bad=%d iv=%.2f",
                                 ratio, s->winBad, s->ivRatio);
                        } else if (s->stallSkip > 0) {
                            s->stallSkip--;
                            s->tpBadDir = 0;
                            s->tpBadCount = 0;
                        } else {
                            s->calibrated = 1;
                            int dir = ratio > 1.03 ? 1
                                : ((ratio < 0.97 && !s->winBad) ? -1 : 0);
                            if (dir == 0) {
                                s->tpBadDir = 0;
                                s->tpBadCount = 0;
                                trim_to_nominal(s);
                            } else if (dir == s->tpBadDir) {
                                s->tpBadCount++;
                            } else {
                                s->tpBadDir = dir;
                                s->tpBadCount = 1;
                            }
                            if (s->tpBadCount >= 2) {
                                apply_calibration(s, ratio, "tp");
                                s->tpBadDir = 0;
                                s->tpBadCount = 0;
                            }
                        }
                    }
                    s->winStartUs = now;
                    s->winFrames = s->framesPlayed;
                    s->winBad = 0;
                }
            }
        } else {
            /* 暂停期间不做校准，恢复后重新开窗 */
            s->winStartUs = 0;
            s->ivWinStartUs = 0;
            s->ivBadDir = 0;
            s->ivBadCount = 0;
            s->tpBadDir = 0;
            s->tpBadCount = 0;
        }

        if (s->closing) continue;
        if (s->failed) break;
        submit_urb(s, idx);
    }

    /* 退出：先 unlink，再确认每个 URB 都回到用户态才让 nativeClose free 缓冲。
     * DISCARDURB 是异步的，固定 sleep 不保证完成，热拔插/复位时内核仍可能往已释放的堆里写 DMA。 */
    drain_inflight(s);
    return NULL;
}

extern "C" {

/**
 * 查询 USB 设备速度：USBDEVFS_GET_SPEED 直接返回内核的 enum usb_device_speed
 * （1=low 2=full 3=high 5=super）；个别内核返回的是 Mbps。
 * 上层会按 >6 与否区分这两种约定。
 */
JNIEXPORT jint JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeSpeed(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return 0;
    int s = ioctl(fd, USBDEVFS_GET_SPEED, 0);
    return s > 0 ? s : 0;
}

/**
 * 把内核驱动重新绑回接口（USBDEVFS_CONNECT）。
 *
 * force claim 会 usb_driver_release_interface() 掉 snd-usb-audio，releaseInterface
 * 并不会自动 probe 回去——ALSA card 在被 disconnect 那一刻就已经从系统里消失了。
 * 不补这一下，用户关掉独占之后这块 DAC 会"凭空不见"，必须拔插才回来。
 *
 * 权限不足时这里会失败，属于可接受降级（只影响退出后的恢复体验）。
 */
JNIEXPORT jint JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeReconnect(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return -1;
    int r = ioctl(fd, USBDEVFS_CONNECT, 0);
    if (r < 0) LOGI("reconnect (USBDEVFS_CONNECT) failed: %s", strerror(errno));
    else LOGI("kernel driver re-bound");
    return r;
}

/**
 * USBDEVFS_RESET：整设备复位并重新枚举，等效"拔了再插"。
 *
 * 关闭独占后，USBDEVFS_CONNECT 回绑在某些 ROM/固件上不会让 snd-usb-audio
 * 重新 probe 出麦克风等全部接口，用户必须重新插拔耳机。复位后设备重新
 * 枚举，所有接口（含麦克风）由内核重新认领——这是最可靠的"土办法"。
 */
JNIEXPORT jint JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeUsbReset(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) return -1;
    int r = ioctl(fd, USBDEVFS_RESET, 0);
    if (r < 0) LOGI("usb reset failed: %s", strerror(errno));
    else LOGI("usb reset ok (device re-enumerated)");
    return r;
}

JNIEXPORT jlong JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeOpen(JNIEnv *env, jobject thiz,
                                              jint fd, jint ep, jdouble samplesPerPacket,
                                              jint rate, jint channels, jint bytesPerSample,
                                              jint packetsPerUrb, jint maxPacketBytes,
                                              jint fbEp, jint fbBytes) {
    if (fd < 0 || ep <= 0 || rate <= 0 || channels <= 0 || bytesPerSample <= 0 ||
        samplesPerPacket <= 0 || packetsPerUrb <= 0) return 0;

    Sink *s = (Sink *) calloc(1, sizeof(Sink));
    if (!s) return 0;
    s->fd = fd;
    s->ep = ep;
    s->rate = rate;
    s->ch = channels;
    s->bps = bytesPerSample;
    s->frameBytes = channels * bytesPerSample;
    s->spp = samplesPerPacket;
    s->spp0 = samplesPerPacket;      // 理论值，稳定后用它精调残余偏差
    s->npkts = packetsPerUrb > MAX_PKTS ? MAX_PKTS : packetsPerUrb;
    /* 单包缓冲区 = 端点容量。校准时不再往"1ms 的包长"预留 8 倍空间，
     * 因为超过 wMaxPacketSize 的包内核会直接拒收整条 URB。 */
    if (maxPacketBytes > 0) s->maxPacketBytes = maxPacketBytes;
    else s->maxPacketBytes = (int) ((s->spp * 8.0 + 3.0) * s->frameBytes);
    if (s->maxPacketBytes < s->frameBytes) s->maxPacketBytes = s->frameBytes;
    s->fbEp = (fbEp > 0 && (fbBytes == 3 || fbBytes == 4)) ? fbEp : 0;
    s->fbBytes = s->fbEp != 0 ? fbBytes : 0;

    pthread_mutex_init(&s->m, NULL);
    pthread_cond_init(&s->cv, NULL);

    double bytesPerMs = (rate / 1000.0) * s->frameBytes;
    // 2000ms：USB 独占绕开系统 AudioTrack，没有任何系统层缓冲兜底。
    // 冷启动解码/JIT、后台节流、UAC 会话建立初期的校准都会让生产者短暂喂不进数据，
    // 缓冲太小会立刻把环形缓冲抽干→欠载（静音包）→ 用户听到"断断续续的噪声"。
    // 给足 2s 余量吸收这些抖动；代价是可闻延迟略增，对音乐播放完全可接受。
    // （早期 600ms 在独占通道下太紧，是"点确定后/切后台就出怪声"的直接诱因之一）
    size_t cap = (size_t) (bytesPerMs * 2000.0);
    size_t urbBytes = (size_t) MAX_PKTS * (size_t) s->maxPacketBytes;
    s->urbBytes = (int) urbBytes;
    if (cap < urbBytes * 10) cap = urbBytes * 10;
    if (cap < 16384) cap = 16384;
    s->cap = cap;
    s->gain = 1.0f;        // 默认满档；独占建立后由 Kotlin 侧按系统音量推送
    s->targetGain = 1.0f;  // [1.4.12·S03] 目标增益默认也满档，避免首包从 0 爬升
    s->ring = (uint8_t *) malloc(cap);
    if (!s->ring) {
        pthread_mutex_destroy(&s->m);
        pthread_cond_destroy(&s->cv);
        free(s);
        return 0;
    }

    for (int i = 0; i < MAX_URBS; i++) {
        s->urb[i] = (struct usbdevfs_urb *) calloc(
                1, sizeof(struct usbdevfs_urb) + MAX_PKTS * sizeof(struct usbdevfs_iso_packet_desc));
        s->buf[i] = (uint8_t *) malloc(urbBytes);
        if (!s->urb[i] || !s->buf[i]) {
            LOGE("alloc urb failed");
            for (int k = 0; k <= i; k++) {
                free(s->urb[k]);
                free(s->buf[k]);
            }
            free(s->ring);
            pthread_mutex_destroy(&s->m);
            pthread_cond_destroy(&s->cv);
            free(s);
            return 0;
        }
        s->urb[i]->type = USBDEVFS_URB_TYPE_ISO;
        s->urb[i]->endpoint = (unsigned char) ep;
        s->urb[i]->flags = USBDEVFS_URB_ISO_ASAP;
        s->urb[i]->number_of_packets = s->npkts;
    }

    if (s->fbEp != 0) {
        s->fbUrb = (struct usbdevfs_urb *) calloc(
                1, sizeof(struct usbdevfs_urb) + sizeof(struct usbdevfs_iso_packet_desc));
        s->fbBuf = (uint8_t *) malloc((size_t) s->fbBytes);
        if (!s->fbUrb || !s->fbBuf) {
            free(s->fbUrb);
            free(s->fbBuf);
            s->fbUrb = NULL;
            s->fbBuf = NULL;
            s->fbEp = 0;
            s->fbBytes = 0;
        } else {
            s->fbUrb->type = USBDEVFS_URB_TYPE_ISO;
            s->fbUrb->endpoint = (unsigned char) s->fbEp;
            s->fbUrb->flags = USBDEVFS_URB_ISO_ASAP;
            s->fbUrb->number_of_packets = 1;
        }
    }

    /* 播放期间禁止内核 autosuspend 这块 USB 设备（ok=1 表示内核接受了这个请求）：
     * 日志里出现过锁屏/切后台后 3.5 秒只完成 60 个 URB（正常 250/秒）的断流。 */
    if (ioctl(fd, USBDEVFS_FORBID_SUSPEND, 0) < 0) {
        LOGI("forbid suspend unsupported: %s", strerror(errno));
    } else {
        s->forbidSuspendOk = 1;
    }
    LOGI("open fd=%d ep=0x%02x rate=%d ch=%d bps=%d spp=%.4f npkts=%d maxpkt=%d fb=0x%02x/%d cap=%zu",
         fd, ep, rate, channels, bytesPerSample, samplesPerPacket, s->npkts,
         s->maxPacketBytes, s->fbEp, s->fbBytes, cap);
    return (jlong) (intptr_t) s;
}

JNIEXPORT void JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeStart(JNIEnv *env, jobject thiz, jlong h) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s || s->started) return;
    s->started = 1;
    if (pthread_create(&s->th, NULL, worker, s) != 0) {
        LOGE("pthread_create failed");
        s->started = 0;
        s->failed = 1;
    }
}

JNIEXPORT jint JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeWrite(JNIEnv *env, jobject thiz, jlong h,
                                               jbyteArray data, jint off, jint size) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s || !data || s->closing || s->failed) return -1;
    if (size <= 0) return 0;

    int written = 0;
    /* 等缓冲空位的上限：暂停时 fill_urb 不再从环形缓冲取数据，
     * 环形缓冲会一直满着。这里必须限时返回，否则解码线程会卡死在 write 里，
     * 上层切歌/关闭时也拿不到会话锁（表现为换歌后一直没有声音）。 */
    int64_t waitDeadline = 0;
    pthread_mutex_lock(&s->m);
    while (written < size) {
        if (s->closing || s->failed) break;
        size_t freeSpace = s->cap - s->used;
        if (freeSpace == 0) {
            int64_t now = nowUs();
            if (waitDeadline == 0) waitDeadline = now + 200000;   // 200ms
            if (now >= waitDeadline) {
                /* 1.2.4：丢帧必须留痕——看门狗靠这个区分"真暂停"和"暂停错位卡死" */
                s->writeDrops++;
                break;
            }
            struct timespec ts;
            clock_gettime(CLOCK_REALTIME, &ts);
            ts.tv_nsec += 10 * 1000 * 1000;
            if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
            pthread_cond_timedwait(&s->cv, &s->m, &ts);
            continue;
        }
        waitDeadline = 0;
        size_t chunk = (size_t) (size - written);
        if (chunk > freeSpace) chunk = freeSpace;
        size_t toEnd = s->cap - s->head;
        if (chunk > toEnd) chunk = toEnd;
        /* 直接拷进环形缓冲，省掉 GetByteArrayElements 的整段 pin/回写 */
        env->GetByteArrayRegion(data, off + written, (jsize) chunk, (jbyte *) (s->ring + s->head));
        s->head = (s->head + chunk) % s->cap;
        s->used += chunk;
        written += (int) chunk;
    }
    pthread_mutex_unlock(&s->m);

    return written;
}

JNIEXPORT void JNICALL
Java_com_smartisan_music_playback_UsbDac_nativePause(JNIEnv *env, jobject thiz, jlong h, jboolean paused) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s) return;
    int p = paused ? 1 : 0;
    /* 1.4.4 清单 3（护栏台账门禁：收紧不变式，非放宽兜底）：
     * 进入暂停态时 worker 的 fill_urb 不再从 ring 取数据（只发静音包保活），
     * 若 ring 里残留暂停前写入的 PCM，会一直满着：nativeWrite 每笔 200ms 超时丢帧、
     * writeDrops 飙升、看门狗最终误判 session lost —— 就是 C 版实机的
     *「暂停-播放循环 / 断续 / 点其他歌一直缓冲」（11-14 日志 wdrops 2→80）。
     * 这里在暂停瞬间主动清空 ring 并唤醒写线程：暂停态 ring 恒空，worker 只发静音，
     * 既不灌满也不掉帧、不误判掉线。暂停态本就不该有 PCM 数据，清 ring 是收紧。
     * 不动 worker 生命周期、不改看门狗阈值、不新增自愈路径（避免 0 次触发的空转函数）。
     * 恢复播放由 nativePause(false) + 上层重新写数据自然衔接。
     */
    if (p && !s->paused) {
        pthread_mutex_lock(&s->m);
        s->paused = 1;
        size_t cleared = s->used;
        s->used = 0;
        s->head = 0;
        s->tail = 0;
        pthread_cond_broadcast(&s->cv);   // 唤醒可能阻塞在满 ring 上的 nativeWrite
        pthread_mutex_unlock(&s->m);
        LOGI("1.4.4 silence-spin guard: pause -> ring cleared (used %zu -> 0), worker keeps silent", cleared);
    } else {
        /* 1.4.5(G-16/G-17) 补 silence-spin guard 的对症场景：1.4.4 只在「进入暂停」清 ring，
         * 而 C 版实机是 worker「没启动」（paused=0）却被上游灌满 used=cap、played=0，
         * 那条分支 0 次触发。现在在 resume（p=0，worker 被拉起出声）时，若 ring 里
         * 还残留旧会话/满 ring 的 PCM，也确定性清一次并唤醒写线程，避免带着满 ring 启动。
         * 这是收紧（只丢弃陈旧未播数据），不动 worker 生命周期、不改看门狗阈值。 */
        if (!p && s->paused) {
            pthread_mutex_lock(&s->m);
            size_t resumed_cleared = s->used;
            s->used = 0;
            s->head = 0;
            s->tail = 0;
            s->paused = 0;
            pthread_cond_broadcast(&s->cv);
            pthread_mutex_unlock(&s->m);
            LOGI("1.4.5(G-16) resume -> stale ring cleared (used %zu -> 0), worker starts clean", resumed_cleared);
        } else {
            s->paused = p;
        }
    }
}

JNIEXPORT void JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeSetDither(JNIEnv *env, jobject thiz, jlong h, jboolean enabled) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s) return;
    s->dither = enabled ? 1 : 0;
}

JNIEXPORT void JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeSetSoftwareGain(JNIEnv *env, jobject thiz, jlong h, jfloat gain) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s) return;
    float g = gain;
    if (g < 0.0f) g = 0.0f;
    else if (g > 1.0f) g = 1.0f;
    /* [1.4.12·S03] 只写目标增益，实际增益由 worker 在 fill_urb 逐包平滑逼近，
     * 消除音量档位跳变在 DAC 上产生的爆音/怪声。 */
    s->targetGain = g;
}

JNIEXPORT void JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeFlush(JNIEnv *env, jobject thiz, jlong h) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s) return;
    /* 只清环形缓冲是不够的：在飞的 8 个 URB 里还压着最多 32ms 的旧音频，
     * seek 之后会先听到一段"上一个位置"的碎片。清缓冲 + 丢弃在飞 URB 交给
     * worker 做（URB 的生命周期在它手里），本线程等到它真的做完再返回——
     * 否则 seek 之后刚写进去的新数据可能被迟到的 flush 一起清掉。 */
    pthread_mutex_lock(&s->m);
    s->flushReq = 1;
    s->flushSeq++;
    int seq = s->flushSeq;
    pthread_cond_broadcast(&s->cv);
    if (s->started) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_nsec += 100 * 1000 * 1000;                 // 最多等 100ms
        if (ts.tv_nsec >= 1000000000L) { ts.tv_sec++; ts.tv_nsec -= 1000000000L; }
        while (s->flushDone < seq && !s->closing && !s->failed) {
            if (pthread_cond_timedwait(&s->cv, &s->m, &ts) != 0) break;
        }
    }
    pthread_mutex_unlock(&s->m);
}

JNIEXPORT jlong JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeFramesPlayed(JNIEnv *env, jobject thiz, jlong h) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s) return 0;
    pthread_mutex_lock(&s->m);
    int64_t f = s->framesPlayed;
    pthread_mutex_unlock(&s->m);
    return (jlong) f;
}

JNIEXPORT jint JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeHealthy(JNIEnv *env, jobject thiz, jlong h) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s) return 0;
    return (s->failed || s->closing) ? 0 : 1;
}

/**
 * 一行文本统计，供内置排障日志采样。字段刻意用短名，避免日志过长。
 */
JNIEXPORT jstring JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeStats(JNIEnv *env, jobject thiz, jlong h) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s) return env->NewStringUTF("usb=null");
    char buf[1024];
    int inflight = 0;
    for (int i = 0; i < MAX_URBS; i++) if (s->inFlight[i]) inflight++;
    int n = snprintf(buf, sizeof(buf),
                     "frames=%lld sil=%lld rate=%d spp=%.3f/%.3f npkts=%d paused=%d cal=%d/%d hz=%.0f iv=%.2f "
                     "stall=%d fs=%d gap=%.1f/%lld under=%lld urbs=%lld err=%lld short=%lld pkterr=%lld "
                     "fb=%d/%d/%d inflight=%d wdog=%lld wdrops=%lld used=%zu cap=%zu maxpkt=%d",
                     (long long) s->framesPlayed, (long long) s->silenceFrames, s->rate,
                     s->spp, s->spp0, s->npkts, s->paused ? 1 : 0, s->calPasses, s->calibrated,
                     s->measuredHz, s->ivRatio, s->stallSkip, s->forbidSuspendOk,
                     s->maxGapUs / 1000.0, (long long) s->gapOverCount,
                     (long long) s->underrunPkts,
                     (long long) s->submittedUrbs, (long long) s->errCount,
                     (long long) s->shortPkts, (long long) s->pktErr,
                     s->fbEp != 0 ? 1 : 0, s->fbValid, s->fbCount,
                     inflight, (long long) s->watchdogTrips,
                     (long long) s->writeDrops,
                     s->used, s->cap, s->maxPacketBytes);
    if (n < 0) n = 0;
    if ((size_t) n < sizeof(buf)) {
        int cnt = s->calLogIdx < 8 ? s->calLogIdx : 8;
        for (int k = 0; k < cnt && (size_t) n + 4 < sizeof(buf); k++) {
            int idx = (s->calLogIdx - cnt + k) % 8;
            if (idx < 0) idx += 8;
            int m = snprintf(buf + n, sizeof(buf) - (size_t) n, " |%s", s->calLog[idx]);
            if (m < 0) break;
            n += m;
        }
    }
    return env->NewStringUTF(buf);
}

/** 置"关闭中"并唤醒阻塞中的 write。不释放任何东西——释放只由 nativeClose 做 */
JNIEXPORT void JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeAbort(JNIEnv *env, jobject thiz, jlong h) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s) return;
    pthread_mutex_lock(&s->m);
    s->closing = 1;
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->m);
}

JNIEXPORT void JNICALL
Java_com_smartisan_music_playback_UsbDac_nativeClose(JNIEnv *env, jobject thiz, jlong h) {
    Sink *s = (Sink *) (intptr_t) h;
    if (!s) return;
    pthread_mutex_lock(&s->m);
    s->closing = 1;
    pthread_cond_broadcast(&s->cv);
    pthread_mutex_unlock(&s->m);

    if (s->started) {
        pthread_join(s->th, NULL);
        s->started = 0;
    }
    /* worker 已经退出（URB 都回到用户态）才允许自动挂起，否则可能在传输中途被挂 */
    ioctl(s->fd, USBDEVFS_ALLOW_SUSPEND, 0);
    for (int i = 0; i < MAX_URBS; i++) {
        free(s->urb[i]);
        free(s->buf[i]);
    }
    free(s->fbUrb);
    free(s->fbBuf);
    free(s->ring);
    pthread_mutex_destroy(&s->m);
    pthread_cond_destroy(&s->cv);
    free(s);
}

} // extern "C"
