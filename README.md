# 中南林课表

一个面向中南林业科技大学校园门户的轻量 Android 课表客户端。支持统一认证、短信验证码登录、当天课程和七日课程表，界面采用原生 Android 控件实现。

> 当前版本：`1.7` · 包名：`cn.csuft.schedulelite` · 最低 Android 版本：Android 6.0（API 23）

## 功能

- 账号密码登录，按服务端配置使用 RSA/PKCS#1 传输登录参数。
- 短信验证码登录，支持短信 nonce 更新和多账号身份选择。
- 密码登录触发二次验证时，支持安全手机或安全邮箱验证码。
- 日历页只展示手机本地日期当天的课程。
- 课程表页一次获取并展示周一至周日课程。
- 七列周视图包含 1—12 节、午休和晚间区域，今天所在列高亮。
- 支持上周、下周、回到本周和手动刷新。
- 日历和课表页展示长沙校园附近的当前天气与气温，点击天气可重试；天气来自 Open-Meteo 模型数据，不请求手机定位权限。
- 不保存账号密码，不包含代码加固、混淆或额外加密。

## 界面结构

- **日历**：当天日期、课程数量、课程时间、教室和任课教师；无课时显示空课状态。
- **课程表**：七列日期、左侧节次时间轴和彩色跨节课程块。
- **登录**：账号密码、短信验证码、多账号选择及 MFA 验证。

## 数据流程

登录服务：

```text
https://cas.csuft.edu.cn/token
```

登录成功后获取 `idToken`，课表从校园门户的日程接口读取：

```text
GET https://portal.csuft.edu.cn/portal-api/v1/calendar/share/schedule/getEvents
```

请求使用 `startDate`、`endDate` 和 `X-Id-Token`。客户端按原软件逻辑向所选周前后各扩一天请求，再筛选周一至周日的数据。

## 项目结构

```text
schedule-apk/
├─ AndroidManifest.xml
├─ build.ps1
├─ res/values/styles.xml
├─ src/cn/csuft/schedulelite/
│  ├─ ApiClient.java          # 登录、验证和课表接口
│  ├─ AuthDialogs.java        # 短信与 MFA 弹窗
│  ├─ MainActivity.java       # 登录、日历和课程表页面
│  ├─ Ui.java                 # 颜色、圆角和通用界面组件
│  └─ WeekScheduleView.java   # 七日课程表网格
└─ tests/cn/csuft/schedulelite/
   └─ AuthFlowTest.java       # 离线认证与课表解析测试
```

项目没有引入 Gradle，使用 Android SDK Build Tools 直接编译，便于查看完整构建过程。

## 构建环境

- Windows PowerShell
- JDK 8 或更高版本，命令行可使用 `javac`、`jar`、`keytool`
- Android SDK Platform 35
- Android SDK Build Tools 35.0.0

当前构建脚本使用以下目录结构：

```text
workspace/
├─ schedule-apk/       # 本仓库
├─ work/
│  └─ android-sdk/
│     ├─ platforms/android-35/android.jar
│     └─ build-tools/35.0.0/
└─ outputs/            # 构建产物
```

在 `workspace` 目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\schedule-apk\build.ps1
```

脚本使用临时盘符 `R:` 规避部分 Android 构建工具对中文路径的兼容问题。首次构建会在被忽略的 `build/` 目录生成本地开发签名；发布版本应使用自己的签名密钥，并且不要把密钥提交到仓库。

## 离线测试

测试覆盖：

- 短信 nonce 更新
- 多账号身份选择
- 手机和邮箱 MFA
- 错误、过期验证码
- 原始 MFA state 延续
- 七日课表分组与课程字段解析

测试需要 JVM 版 `org.json` 和 Android SDK 的 `android.jar`。测试使用固定响应，不会连接学校服务或发送短信。

## 隐私与安全

- 密码只在登录请求期间存在，不写入本地存储。
- 登录 Token 保存在应用私有偏好设置中，退出登录时删除。
- `.gitignore` 已排除构建目录、APK、签名密钥、SDK、Cookie、Token 会话和本地接口响应。
- 提交代码前仍建议执行 `git status`，确认没有账号、手机号、Token 或私钥。

## 已知限制

- 人脸认证和第三方联合认证尚未接入。
- 课程数据取决于学校门户日程接口；接口未下发课程时，客户端会显示空周。
- 尚未配置自动化 CI，构建流程目前面向 Windows。

## 版本记录

- **1.6**：统一登录、日历和课程表视觉风格，优化课程块、时间轴、空课卡片与底部导航。
- **1.7**：显示校园附近的当前天气和气温。
- **1.5**：修复周网格不显示，改用实体 `GridLayout`，并扩展周查询边界。
- **1.4**：加入日历与课程表双模块、七列周视图和当天高亮。
- **1.1—1.3**：加入短信登录、MFA、多账号选择、门户课表接口和整周查询。

## 声明

本项目是非官方客户端，与中南林业科技大学及校园门户服务提供方不存在隶属关系。请只在获得授权的账号和环境中使用，并遵守学校相关规定。仓库暂未附加开源许可证。
