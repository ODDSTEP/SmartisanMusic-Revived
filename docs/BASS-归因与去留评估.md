# BASS 归因与去留评估

> 版本 1.4.25 · 2026-11-25
> 触发：用户质疑「是 BASS 本身垃圾，还是你根本用错了？人家 Salt Player 就没这么多问题」
> 方法：不看印象，把 R1~R75 全部反馈 + 日志结论逐条对到代码，判「真凶是谁」。

---

## 0. 一句话结论

**BASS 不是这批问题的元凶。**

75 条反馈里，真正由 BASS 引起的是 **2 条**：
- 1 条是**我用错**（句柄释放后继续读，R70）；
- 1 条是 **BASS 的 APE/DSD 插件真有缺陷**（解高码率 SIGSEGV，R31 崩溃链的一环）。

被**误算**到 BASS/USB 头上的是 1 条（R72 熔断连坐）。

其余全部来自别处：**Media3 AudioSink 生命周期**、**UAC 会话状态机**、**ffmpeg 管道不可 seek**、**UI/元数据/并发**。

---

## 1. BASS 在本项目里的真实职责（代码实证）

很多人（包括我之前的表述）会误以为「BASS 是播放引擎」。**不是。**

```
文件 → [解码取流层] → PCM → [Media3 ExoPlayer + AudioSink] → 声卡
                ↑
        BASS 只站在这一格
```

- 会话层（队列、进度、通知、锁屏、音频焦点、gapless）**永远是 Media3**，从未换过。
- BASS 只做四件事：

| 职责 | 承载文件 | 说明 |
|---|---|---|
| PCM 解码源 | `BassPcmDataSource`（实现 Media3 `DataSource`） | `BASS_STREAM_DECODE` 拉模式取 PCM |
| DSP | `BassEngine.BassStream` + `BassDspConfig` | BASS_FX：EQ / 压限 / 混响 / 倒放 / 变速不变调 |
| 交叉淡化 | `BassCrossfadeMixer`（BASSmix） | 双曲重叠混音 |
| BPM / 接缝分析 | `MorvaniumAnalyzer` | 智能过渡用 |

- 而且解码范围已经被我们自己砍掉一大半：`OfficialDecodeFrontend.isBassUnsafe()` 把
  **DSD / APE / ≥96kHz / 所有冷门格式**全部踢给 ffmpeg，不许进 BASS。

**所以现状是：BASS 已经被我们降级成一个「只解常见格式的可替换解码器 + 一套 DSP」。**

这句话是下面所有判断的基础。

---

## 2. 全量归因表（R1~R75 中所有涉及播放/音频/USB 的反馈）

| 编号 | 现象 | 真凶 | 与 BASS 的关系 |
|---|---|---|---|
| R19 | DAC 硬件音量"假成功"、切硬件音量反而更大不可调 | UAC Feature Unit 探测选错 unit（写了有回读、控制的不是当前路径） | **无关**。UAC 协议实现 |
| R19 | 渡口采样率不支持时"啵啵"杂音 | UAC alt 切换 / AudioTrack 重建瞬态 | **无关**（BASS 不碰输出设备） |
| R20 | 重启后首次关独占没真正释放 | `sessionActive=false` 跳过 reset，内核残留未清 | **无关**。状态机缺陷 |
| R31 | 开独占→开零损失→关独占 = Native 崩溃 | 关独占瞬间 USB reset 重枚举 与 STREAM_PCM 启动并发；旧数据源已 close 又被读 → in-process native use-after-free | **一半**：崩在 BASS 通道上（1.4.24 时 APE 走 libbassape），但**触发条件是生命周期竞态**，不是 BASS 自己算错 |
| R31 | 独占"假开启"（无 UAC 会话） | `rebuildOutput()` 只 `stop()+prepare()`，同格式不触发 `AudioSink.configure()` → `usbSink` 仍 null | **无关**。Media3 AudioSink 生命周期理解错误 |
| R32 | 唱盘爆豆音频率太高 | 单段采样 5.196s > 触发间隔 2~4s → 约 1.57 段重叠 | **无关**。SoundPool 播黑胶采样，素材时长问题 |
| R38 | 独占开关不建会话、切歌才 claim → 进度走但无声 | 同 R31：AudioSink 未重建；且已把共享 AudioTrack `preferredDevice` 指向未激活端点 | **无关** |
| R38 | DSD 曲目沙沙、音质差 | ffmpeg `-ar 88200` 做 4:1 抽取，低通不够陡 → 20kHz 以上量化噪声折叠 | **反向相关**：正因为 BASS 的 DSD 会崩，才改走 ffmpeg，才有了这个折叠噪声 |
| R39 | 切歌/打碟后莫名自动暂停 | 打碟流程 pause→转码→seek→play，`play()` 被中途 `stop()/prepare()` 冲掉 | **无关**。并发意图管理 |
| R41 | 爆豆音再降一半 + 轻微磨损分档 | 同 R32 | **无关** |
| R70 | 点歌必现闪退 `fid==null on BASS_ChannelGetInfo` | `by lazy` 延迟求值，首次访问发生在 `close()` 之后 → use-after-free | **纯用法错误**。任何 C API 都禁此操作 |
| R72 | 独占反复"假独占/降级"、文案与实际脱节 | BASS 崩溃被 `UsbGuard` 无差别记为"UAC 会话不稳"→ 持久化熔断 | **归因错误**。BASS 崩了连坐 USB |
| R73 | 独占/软件音量下缓冲卡死、拖进度错位、暂停久再播异常 | 冷门格式走 ffmpeg 管道流式 PCM，**管道不可 seek**，`ffmpeg -ss` 重启有时间差，Media3 在不可 seek 管道上竞态 | **无关**。ffmpeg 管道架构限制 |
| R73 | 软件音量调成 0 后无声但进度走 | `VolHandoff: readback ok gain=0.000`，音量接力读回归零 | **无关** |
| R74 | "该机 UAC2 会话不稳" | **误诊**（设备前提记错，实为原道 Tears DSP，硬件完全支持） | **归因错误** |

**统计**：与 BASS 直接相关 2 条（1 用法 + 1 插件缺陷），归因错误 2 条，其余 10 余条与 BASS 无关。

---

## 3. BASS 真正不行的地方（这三条必须认）

### 3.1 `libbassape` / `libbassdsd` 解高码率会 SIGSEGV

- 代码位置：`OfficialDecodeFrontend.isBassUnsafe()`（1.4.25 新增的闸门）。
- 触发条件：APE / DSD(dsf/dff) / 任意 ≥96kHz。
- 性质：**in-process native 崩溃，Java `try/catch` 抓不到**，直接 SIGSEGV。
- 影响：这是 R31 崩溃链的一环，也是「DSD 音质」这条线一直做不好的起点。
- 判断：**这是 BASS 侧的真实缺陷**（更准确说是它生态里的插件：`libbassape` 是社区维护，
  `libbassdsd` 体积仅 16KB，能力有限）。核心 `libbass.so` 本身没有问题。

### 3.2 闭源 native，崩了没符号

- tombstone 里只有 `pc 0000007xxx` 和 so 名，没有函数名、没有行号。
- 后果：R70 那种 bug 从发生到定位花了好几轮；R31 至今只能靠推演。
- 判断：不是 bug，是**代价**。买它的格式覆盖和 DSP，就得付这个调试成本。

### 3.3 商业授权

- BASS 非商业免费；商业发行需要购买 license（按平台/产品计）。
- 判断：如果这个 App 将来要上架收费或走应用商店合规审核，这是一笔实际成本。

---

## 4. BASS 被我用错的地方（这五条也得认）

| # | 错误 | 具体表现 | 正确做法 |
|---|---|---|---|
| 1 | 句柄跨线程自由访问 | `sampleRate` 用 `by lazy`，任何线程随时触发 native 读 | 构造时缓存 + 全局锁 + `closed` 短路（1.4.25 已改） |
| 2 | 释放与读取并发 | 关独占/切路由时 close 旧通道，另一条路径仍在 read → R31 | 关闭前先置 `closed` 作屏障，读方先检查 |
| 3 | 崩溃归因无限扩大 | BASS 崩一次就熔断 USB，永久降级 | 只在"崩溃时确有独占会话"才熔断（1.4.25 已改） |
| 4 | DSP 双路径并存 | BASS_FX 施加一遍，`PcmDspAudioProcessor` 再施加一遍，靠"任一效果激活就透传"互斥 | 只能有一处生效；迁移期用显式开关而不是隐式条件 |
| 5 | 在播放通道上做分析 | `BASS_FX_BPM_DecodeGet` 会**消费** channel，代码注释已承认曾经打断播放 | 分析必须开独立通道或离线做 |

**一句话**：BASS 给的是裸刀柄，我却当成带护手的剑在挥。

---

## 5. BASS 到底该怎么用（六条硬规则）

1. **只做解码，不做输出**：flags 必须含 `BASS_STREAM_DECODE`，绝不让 BASS 自己接声卡。
   （现状 ✅，`openFile/openPfd/openForCrossfade` 都带了）
2. **句柄单点封装**：全仓库只允许 `BassEngine.kt` 调 native。
   （现状 ✅，R76 构建期检查已强制）
3. **插件按格式白名单**：DSD / APE / ≥96kHz 一律不进 BASS。
   （现状 ✅，`isBassUnsafe`）
4. **不在播放通道上做分析**：BPM/FFT 要么离线，要么独立通道。
   （现状 ⚠️ `MorvaniumAnalyzer` 已加 isBassUnsafe 规避，但仍复用通道）
5. **DSP 只能有一处生效**：BASS_FX 与 PcmDsp 二者择一，用显式状态位切换。
   （现状 ⚠️ 靠"透传条件"隐式互斥，脆弱）
6. **关闭即失效**：`closed` 必须原子置位且先于任何 `BASS_*Free`；读方先查 `closed`。
   （现状 ✅）

---

## 6. 哪里该用、哪里不该用

| 场景 | 该不该用 BASS | 理由 |
|---|---|---|
| 常见格式（mp3/flac/wav/aac）PCM 解码 | **可以，但非必需** | Media3 extractor 与 ffmpeg 都能做，且不崩 |
| DSD / APE / ≥96kHz | **禁止** | 插件 SIGSEGV，实证 |
| BASS_FX 的 EQ / 压限 / 混响 | **可用，但需独占生效** | 算法成熟；但项目已有 `PcmDspAudioProcessor` 同功能 |
| 交叉淡化（BASSmix） | **目前是它最不可替代的一项** | 自研需在 AudioSink 层做混音，成本高 |
| 变速不变调 | **不必用** | Media3 内置 Sonic，且是会话层原生能力 |
| BPM / 接缝分析 | **不必用** | 离线 FFT 即可，不必占播放通道 |
| 作为播放引擎直接出声 | **绝对不要** | 会绕开会话层，队列/通知/焦点全废（1.3.7 已吃过亏） |

---

## 7. 抛开 BASS 自己重构，能不能做得更好？

**能。而且大部分替代件已经躺在项目里了。**

| 维度 | BASS 现状 | 自研 / ffmpeg + Media3 | 谁更好 |
|---|---|---|---|
| 格式覆盖 | 插件全，但 DSD/APE 崩 → 实际只剩常见格式 | ffmpeg 全覆盖（含 DSD/APE/DTS/TTA/TAK） | **自研** |
| 稳定性 | in-process native 崩溃，Java 抓不到 | ffmpeg 是**独立子进程**，崩了只影响当次解码 | **自研** |
| 调试性 | 闭源、无符号 | 全栈可读可断点 | **自研** |
| 内存拷贝 | BASS native → Java ByteArray → AudioSink（2 次） | ffmpeg pipe → AudioSink（1 次） | **自研** |
| 包体 | +800KB so（bass+fx+mix+dsd+ape+flac） | 0（ffmpeg 已在包里） | **自研** |
| 授权成本 | 商业需付费 | 无 | **自研** |
| 交叉淡化 | BASSmix 成熟 | 需自研（AudioSink 层混音或双 player 包络） | **BASS** |
| 混响算法 | Freeverb 现成 | 需移植/自研 | **BASS** |
| 变速不变调 | BASS_FX_TempoCreate | Sonic（Media3 内置） | **打平** |
| DSD 音质 | 会崩，等于没有 | ffmpeg 需自己调陡低通（R38 沙沙就是没调） | **打平（都要调）** |
| gapless | 需自己接 | Media3 原生 | **自研** |
| 低延迟 | BASS 缓冲 | AudioTrack 直通 | **打平** |

**替换件现状（都在仓库里）**：
- `StreamingPcmDataSource` —— ffmpeg 全格式流式 PCM，已能跑（只是被标记为 stream 引擎专用）
- `PcmDspAudioProcessor` —— 自研 EQ / 压限 / 混响 / ReplayGain / 声道平衡，已进普通播放链
- Media3 `SonicAudioProcessor` —— 变速不变调，ExoPlayer 自带
- `MorvaniumAnalyzer` —— 已有 FFT 分析底座

**缺口只有两个**：交叉淡化、倒放。

**诚实的风险**：
- ffmpeg 管道不可 seek 的问题（R73）会从"冷门格式"扩散到"所有格式"——必须先解决 seek 策略（分段重启 + 位置校正，或落 FLAC 缓存）。
- 交叉淡化自研若做在 AudioSink 层，需要处理双流混音与时钟同步，是最难的一块。

---

## 8. 建议路线（三阶段，每阶段可回退）

| 阶段 | 做什么 | 验收口径 | 回退点 |
|---|---|---|---|
| 一 | BASS 降为「兜底解码器」：默认路径全走 ffmpeg + PcmDsp；BASS 只在 ffmpeg 失败时启用 | 全部格式能播、无 native 崩溃、DSP 效果与现在一致 | 保留 BASS 开关 |
| 二 | 补交叉淡化与倒放（自研），删 BASS_FX / BASSmix 依赖 | 交叉淡化无缝、倒放正确、变速不变调正常 | 保留旧实现分支 |
| 三 | 删 `libbass*.so` + `com.un4seen.bass` binding + `isBassUnsafe` 闸门 | 包体 -800KB、全量回归通过 | 打 tag 可回滚 |

**不建议一把梭**：阶段一的收益（无 native 崩溃 + 全格式覆盖）已经能覆盖当前 90% 的痛点，
而阶段二的交叉淡化是唯一真正难的部分，值得单独做、单独验。

---

## 9. 与 Salt Player 的对照（为什么它没这些事）

| | Salt Player（椒盐） | 本项目 |
|---|---|---|
| 解码 | Media3/ExoPlayer 原生 extractor | Media3 + BASS + ffmpeg 三套 |
| USB 独占 | 系统 `AudioTrack` + `AudioDeviceInfo`，系统托管 | 自己写 UAC2 会话（claim / alt / Feature Unit） |
| 引擎 | 单一引擎 | 三引擎热切换 |
| 通知 | 系统默认模板 | 自绘 RemoteViews + 媒体按钮偏好 |
| 能力边界 | 不做 DSD 原生、不做交叉淡化、不做 UAC 直出 | 全都要 |

**它的稳，有一部分来自「不做」。** 我们的复杂度是它没有的；但复杂度不能成为崩溃的借口——
上面那张表说明了：**复杂度里真正危险的部分，恰好是 BASS 那一层**。这也是阶段一路线值得做的理由。

---

## 10. 落档

- 硬规则已落地：`app/build.gradle.kts` → `verifyBassIsolation`（挂 `preBuild`，违规即 BUILD FAILED）
- 台账：`docs/下版本提醒事项.md` → R76（已改）/ R77（本分析）/ R78（去留待拍板）/ R79~R80（分阶段任务）
- 技术文档：`锤子音乐-技术文档总集.md` §9.9
