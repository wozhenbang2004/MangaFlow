# 漫流 · MangaFlow

一个专注于阅读的 Android 漫画客户端。使用 Kotlin 和 Jetpack Compose，支持 Android 8.0 及以上。

[下载最新版本](https://github.com/wozhenbang2004/MangaFlow/releases/latest) · [构建与发布](https://github.com/wozhenbang2004/MangaFlow/actions/workflows/android-release.yml)

## 功能

- 漫画发现、搜索、沉浸式详情与章节目录，公开阅读通过拷贝漫画匿名来源获取。
- 纵向滚动、从左至右和从右至左翻页，图片缩放，阅读底色、亮屏和页间留白设置。
- 本地书架与阅读进度，返回后继续阅读。
- 章节下载、暂停与继续、失败重试、离线阅读；后台任务由 WorkManager 管理。
- Bangumi 评分与 AniList 评分、公开评价摘要，保留来源、作者和原文链接。无法确认是同一本漫画时显示原站入口。
- 应用内检查更新、更新提醒、安装包断点续传与覆盖安装。

第三方来源的错误、限流和条目未匹配会直接显示，不使用示例漫画或虚构评论填充。当前没有账号登录与云端书架同步。

## 构建

环境：JDK 17、Android SDK 35、Build Tools 35.0.0。Gradle 8.9 Wrapper 已包含在仓库中；Android Studio 可直接打开根目录。

```bash
./gradlew :app:assembleDebug
```

调试包位于 `app/build/outputs/apk/debug/app-debug.apk`。正式包由 GitHub Actions 使用固定发布密钥签名；本地调试包的签名不同，不能覆盖正式版。

工程不编写或运行测试，不执行 lint、模拟器验证或截图检查。流水线只负责编译、打包、签名和发布；构建成功不等于功能已实机验证。

## 自动发布

推送到 `main` 或手动启动 **Android release** 后，流水线会：

1. 为本次运行分配 `0.1.<run_number>`，`versionCode` 为 `10000 + run_number`。
2. 编译 release APK，并使用同一份 PKCS12 密钥签名。
3. 生成签名更新清单 `latest.json` 和 `SHA256SUMS`。
4. 上传完整资产后发布 GitHub Release，供用户直接下载和应用匿名检查更新。

已发布的同一次运行重跑会保留原资产。发布前后核对源提交与 tag；旧构建不会覆盖新的 `main` 版本。

仓库 Actions Secrets 使用 `MANGAFLOW_SIGNING_KEYSTORE_B64` 与 `MANGAFLOW_SIGNING_PASSWORD`。首次建立签名身份时，可运行 `scripts/create-signing-key.py`，参数为仓库外的私密目录。密钥 alias 为 `mangaflow`；发布后需要持续保留同一密钥及密码。

客户端使用当前安装包的签名证书验证更新清单，再核对 APK 大小、SHA256、包名、版本和签名，最后由 Android 系统确认安装。自动检查周期为六小时，具体调度受系统后台策略影响。

## 代码结构

| 目录 | 职责 |
| --- | --- |
| `data/` | 匿名来源请求、漫画与章节解析、评分和评价匹配 |
| `storage/` | 本地书架、阅读偏好、持久下载队列 |
| `reader/` | 滚动与分页阅读、图片缩放、进度记录 |
| `ui/` | 导航、发现、详情、下载与设置 |
| `update/` | 版本检查、通知、下载校验与安装 |
| `scripts/` | 签名身份创建与发布资产生成 |

Android 源码位于 `app/src/main/java/dev/mangaflow/`。图片加载使用 Coil，缩放使用 Telephoto，玻璃材质使用 Haze。

## 数据与来源

应用不收集账户 token，不提供自建用户服务或统计 SDK。书架、阅读进度、偏好和已下载章节保存在设备上；漫画、评分、评价与应用更新请求直接访问对应公开来源。仓库不包含漫画文件、个人数据或签名私钥。

漫画和评价内容归其原作者及来源所有，详情与评价保留原站入口。请按来源的使用规则阅读和保存内容。
