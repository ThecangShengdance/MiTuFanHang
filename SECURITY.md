# 安全说明

「迷途返航」不申请 `INTERNET` 权限，轨迹与设置只保存在手表应用私有目录，不上传云端。

## 报告问题

如果你发现安全问题，请优先通过 GitHub 的 **Security → Report a vulnerability** 私密报告，不要在公开 Issue 中贴出可利用细节或个人信息。

## 支持范围

- 最新 release 版本会收到修复。
- 旧版本仅在问题可复现于最新版本时处理。

## 发布密钥

正式 APK 的签名密钥不存放在仓库中。维护者需离线备份 `keystore/release.jks` 与 `keystore.properties`；丢失后无法覆盖安装升级。
