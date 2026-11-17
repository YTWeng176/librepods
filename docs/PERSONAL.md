# LibrePods Personal：2.0 自用修补版

这是 YTWeng176 维护的非官方个人测试分支，基于上游 `rewrite-v2.0.0-alpha.4`（`80aa7461df414470f379ebdfe540b2e8889fcbe2`）。原项目、协议研究、图标与许可证归各自作者所有，保留 GPL-3.0 许可证。个人版不代表上游发布或认可。

目标设备：红米 K100 Pro Max、澎湃 4、AirPods 5、无 Root。用户已确认原版 1.0.4 的电量与降噪控制正常；此分支不新增 Root 或 Shizuku 依赖。

## 安装与回退

- 包名：`me.kavishdevar.librepods.personal`；应用名：`LibrePods Personal`。
- 与官方 1.0.4 独立安装和存储，不迁移或覆盖原版数据。
- 两个应用会竞争同一条 AirPods 控制连接。体验个人版前，先强行停止原版并关闭其自启动；回退时反向操作。不要同时运行两个后台服务。
- 首次使用需重新授予个人版蓝牙、通知等权限。开盖/佩戴弹窗需要悬浮窗权限，按需开启；无障碍服务仅在需要对应增强时启用。
- 这是 Alpha 测试包。自动测试与编译不能代替真实耳机上的通话、降噪、头部手势和耗电验收。

## 变更来源

| 来源 | 采用内容 |
| --- | --- |
| [PR #875](https://github.com/librepods-org/librepods/pull/875)，`9b1f56534e15c70fc61864f20937eb4c62da50be` | AACP EOF / IO 错误退出及旧读取任务清理语义 |
| [PR #855](https://github.com/librepods-org/librepods/pull/855)，`888fac34c21c542f004946cb4523f03287e5e4e6` | 对话感知音量上限、交错音量渐变取消和状态 2 恢复 |
| [PR #852](https://github.com/librepods-org/librepods/pull/852)，`6d96cc0888d94e92a3049162f999b92faaf70859` | 简繁中文、资源化 UI 文案、EQ 标签裁切修复 |
| [PR #881](https://github.com/librepods-org/librepods/pull/881)，`b565be34fe3356241767da6a0c26eaf642a7e387` | 仅采用窗口先设置目标状态再添加/更新的裁切修复，保留默认外观 |
| [PR #821](https://github.com/librepods-org/librepods/pull/821) | 低采样率手势平滑问题，按 alpha.4 当前实现适配 |
| [Issue #911](https://github.com/librepods-org/librepods/issues/911) | 独立调查 BLE 扫描/刷新/重复广告处理的耗电路径；不声称 #875 解决所有耗电 |
| [Issue #921](https://github.com/librepods-org/librepods/issues/921) | 音频配置文件断开后的控制连接释放与正常重连 |

上述 PR 按适用语义移植；不是将其所有历史提交或整个分支合入。上游作者的测试是筛选依据，个人版的测试与构建结果须分别查阅本 fork 的 Actions 和 PR。

暂不采用 #663（仅免 Root 引导提示）、#885（未完成设备验证的特权服务）、#762（较大界面换肤）及仅 Linux 的修复。未提供系统级空间音频、Find My 网络或其他 App 的高质量麦克风接口。

## 本地构建

需要 Java 25（仓库 Gradle daemon 要求；Java/Kotlin 字节码目标仍为 21）、Android command-line tools。安装工具：

```bash
sdkmanager 'platforms;android-37.0' 'build-tools;37.0.0' 'cmake;3.22.1' 'ndk;30.0.14904198'
```

在 `android/local.properties` 写入自己的 SDK 位置与个人签名配置；该文件已被 Git 忽略：

```properties
sdk.dir=/absolute/path/to/android-sdk
RELEASE_STORE_FILE=/absolute/path/to/personal-keystore.p12
RELEASE_STORE_PASSWORD=your-local-password
RELEASE_KEY_ALIAS=librepods-personal
RELEASE_KEY_PASSWORD=your-local-password
```

不要提交密钥或密码。保存好自己的签名私钥：后续更新相同包名时需要使用同一个密钥。

```bash
./scripts/build-personal.sh
```

入口会检查签名配置、校验资源、运行 JVM 回归测试并生成签名 FOSS release APK 和 SHA256 校验文件到 `dist/`。第一次下载依赖与 NDK 会较慢。重跑同一命令会复用 Gradle 缓存。

## GitHub Actions

本分支将 Android 构建改为标准 `ubuntu-latest` runner，不依赖上游私有 runner、Play 发布或 Discord 凭据。

fork 中配置 `PERSONAL_KEYSTORE_BASE64`（PKCS12 私钥文件的 Base64）和 `PERSONAL_KEYSTORE_PASSWORD` 两个 Actions Secrets。CI 仅在个人分支代码推送或手动触发时构建，不自动发布到 Play 或上游。签名临时文件在任务结束后删除。APK、校验文件和测试报告保存在该次 Actions 工件。

## 真机验收清单

1. 停止原版，启动 Personal，确认能连接、读左右耳与充电盒电量、切换降噪/通透/自适应及关闭模式（以耳机实际能力为准）。
2. 连续摘戴、放回盒子、开关盒盖，确认暂停/恢复与弹窗；检查展开弹窗是否被裁切。
3. 低音量播放时连续说话几次，对话感知结束后音量不得逐次升高；结束说话后立即再说一次也检查。
4. 在系统蓝牙页面手动断开后，将耳机连接其他设备，等待至少一分钟并让手机收到通知；个人版不应把音频抢回。随后手动连接手机，应恢复电量和降噪控制。
5. 如果启用摘戴释放音频，摘下再戴上应恢复音频，不能被手动断开保护误伤。
6. 来电时分别测试点头/摇头；录音仅确认个人版内功能，不据此推断微信等其他 App 可调用。
7. 耳机不在附近时静置 30–60 分钟，记录系统电量页中 Personal 的 CPU/后台耗电；再测试手机与其他设备间切换。若异常升高，停止 Personal 并回退原版，保留型号、固件与脱敏日志。

硬件验收结果需由真机实测填写；构建成功不等于这些项目已通过。
