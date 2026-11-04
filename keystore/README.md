# 签名密钥（不提交到仓库）

仓库只保留调试用的构建逻辑，不包含正式签名密钥。`keystore.properties`、`*.jks`、`*.keystore` 已被 `.gitignore` 忽略。

## 生成正式密钥

```bash
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -storetype PKCS12 \
  -alias mitufanhang \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=MiTuFanHang, O=YourName, C=CN"
```

然后在项目根目录创建 `keystore.properties`：

```properties
storeFile=keystore/release.jks
storePassword=你的密码
keyAlias=mitufanhang
keyPassword=你的密码
```

## 构建正式包

```bash
./gradlew app:assembleRelease
```

输出在 `app/build/outputs/apk/release/app-release.apk`。

> 请离线备份密钥和密码。Android 不允许用不同签名的 APK 覆盖安装；丢失密钥后只能卸载重装，应用数据会丢失。
