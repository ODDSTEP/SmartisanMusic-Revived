# 参与贡献

感谢你愿意改进锤子音乐。本项目是一份个人兴趣驱动的非官方复刻，欢迎 Issue、文档修正和代码贡献。

## 提出问题（Issue）

提 Bug 时请尽量给出：

- 机型、Android 版本、App 版本（设置页可见 `versionName / versionCode`）；
- 复现步骤：做了什么 → 期望结果 → 实际结果；
- 涉及音频时附上设备型号、文件格式与采样率/位深；
- 完整日志（设置页可导出日志快照）。日志整包附上即可，不必自己筛选。

USB 独占、零损失、硬件音量、DSD 等行为与具体硬件强相关，请注明 DAC / 小尾巴型号。

## 提交代码（Pull Request）

1. Fork 本仓库，从 `main` 建一个特性分支；
2. 改动保持聚焦，一个 PR 做一件事；
3. 提交前跑通：

   ```bash
   ./gradlew testDebugUnitTest assembleDebug lintDebug
   ```

4. 描述清楚改了什么、为什么改、怎么验证；涉及 UI 的话尽量附图。

## 代码约定

- 业务代码使用 Kotlin，UI 使用 Jetpack Compose；
- 包边界与状态归属见 [docs/ui-architecture.md](docs/ui-architecture.md)；
- 构建配置见 `app/build.gradle.kts`，版本号与构建标识集中在那里；
- 请勿在代码中写入任何签名密钥、口令或个人路径；相关配置一律走环境变量。

## 发布签名

发布构建的签名通过 `SMARTISAN_KEYSTORE` / `SMARTISAN_STORE_PASS` / `SMARTISAN_KEY_ALIAS` / `SMARTISAN_KEY_PASS` 四个环境变量提供，仓库不含任何密钥。详见根目录 README 的"构建"一节。

## 行为准则

讨论请就事论事、尊重他人。与项目无关的争论、人身攻击、涉及第三方权利的违规素材请求将被关闭。
