# 隐私说明

锤子音乐是一个**纯本地**音乐播放器。它没有账号体系、不接入任何媒体分发服务，也不上传你的任何数据。

## 不联网

最终构建的 Manifest **不包含 `INTERNET` 权限**。应用运行时不访问网络，不会上传歌曲、封面、歌词、歌单、收藏、资料库或设置信息。

## 申请的权限及用途

| 权限 | 用途 |
| --- | --- |
| `READ_MEDIA_AUDIO`（Android 13+）/ `READ_EXTERNAL_STORAGE`（Android 8.1–12） | 扫描设备上的本地音频文件 |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 播放音乐时维持后台播放与媒体通知 |
| `MODIFY_AUDIO_SETTINGS` | 应用音效与系统音乐音量控制 |
| `VIBRATE` | 交互触觉反馈 |
| `WRITE_EXTERNAL_STORAGE`（Android 8.1–9，仅删除时） | 用户确认删除歌曲后，经 MediaStore 执行删除 |

应用**不申请**修改系统设置、定位、相机、麦克风、通讯录、短信、悬浮窗或无障碍权限。

## 数据存在哪里

歌单、收藏、播放统计、播放队列与进度、设置项全部保存在本机（Room 数据库 / DataStore），不会离开设备。导出的日志快照由你手动触发、仅包含排障所需的运行状态与版本信息，是否分享完全由你决定。

## 第三方素材

应用内含的锤子原版视觉资源与可选 realme 图标来自第三方，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)；这部分素材的权利归各自权利人，不随本项目许可证授权。
