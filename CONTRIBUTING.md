# 参与贡献

感谢你改进「迷途返航」。这是一个面向 Wear OS 2+ 的纯 Java 应用，不依赖 Google Play Services，也不使用 Kotlin/Compose/AndroidX。

## 开发环境

- JDK 17
- Android SDK Platform 36.1、Build-Tools 36.0.0
- 项目自带 Gradle Wrapper（Gradle 9.6.0）

## 构建与测试

```bash
./gradlew app:assembleDebug
./gradlew app:testDebugUnitTest
./gradlew app:lintDebug
```

如果本机 Gradle 用户目录不可写（例如沙箱环境），可把 `GRADLE_USER_HOME` 指到项目内：

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew app:assembleDebug
```

## 提交前

- 不要提交 `keystore.properties`、`*.jks`、`*.keystore`、APK/AAB 或 `.local-backup/`。
- 新增 Java 代码保持 Java 17 语法；不要引入 AndroidX 或 GMS 依赖。
- UI 改动需在 454×454 圆形屏（TicWatch Pro 3）和方形布局下检查安全区域。
- 涉及定位/返航逻辑的改动请补充或更新 `app/src/test` 下的单元测试。
- PR 请说明真机型号、Wear OS 版本和验证步骤。

## 签名

仓库不包含正式签名密钥。需要出正式包时，按 [keystore/README.md](keystore/README.md) 生成自己的密钥，并在本地创建 `keystore.properties`。不要把密钥或密码提交到仓库。
