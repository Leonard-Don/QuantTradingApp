# 天线量化 - Android App

## 项目概述

基于 Kotlin + Material Design 3 的纯股票分析工具，完全合规，无实盘交易入口。

## 功能模块

### 1. 选股模块
- 基础条件选股（行业、市值、均线、成交量）
- 高级多因子选股（VIP）
- 主力资金选股（VIP）
- 龙虎榜联动选股（VIP）
- 每日精选股票池（VIP）

### 2. 复盘模块
- 市场概况（涨跌统计、成交额、涨停数）
- 板块轮动分析
- 资金流向统计
- 龙虎榜数据

### 3. 社区模块
- 投资逻辑交流
- 技术方法分享
- 复盘笔记
- VIP专属内容

### 4. 量化模块
- 策略回测（VIP）
- 多因子模型（VIP）
- 趋势/网格/动量策略（VIP）
- 量化信号（仅模型，无买卖指令）

## 合规红线

- 不做实盘交易入口
- 不接入券商交易通道
- 不代客理财、不喊单
- 不保证收益、不荐股收费
- 不展示个股具体买卖点位

## 变现模式

| 模块 | 免费功能 | VIP功能 | 价格 |
|------|---------|---------|------|
| 选股 | 基础筛选 | 高级多因子、主力选股 | ¥68/月 |
| 复盘 | 基础数据 | 深度分析、历史回溯 | 含在VIP |
| 社区 | 浏览帖子 | 专属内容、私密圈子 | 含在VIP |
| 量化 | 查看策略 | 回测、自定义公式 | ¥168/季 |

## 技术栈

- Kotlin 1.9.22
- AGP 8.6.1 / compileSdk 35 / targetSdk 35
- Material Design 3
- MVVM + ViewBinding
- Navigation Component
- Coroutines + Flow
- OkHttp / Retrofit + Room
- R8 Release 混淆与资源压缩

## 项目结构

```
TianXianQuant/
├── app/
│   ├── src/main/java/com/tianxian/quant/
│   │   ├── MainActivity.kt
│   │   ├── MyApp.kt
│   │   ├── data/                 # Room 实体、DAO、迁移与本机状态仓库
│   │   ├── model/                # 数据模型与纯 Kotlin 策略/校验策略
│   │   ├── network/              # 腾讯公开行情接入与错误结果封装
│   │   ├── payment/              # 支付网关边界；Release 禁用本地模拟开通
│   │   ├── receiver/             # 每日研究提醒
│   │   ├── util/                 # 常量与通知辅助
│   │   ├── viewmodel/
│   │   │   ├── StockSelectViewModel.kt
│   │   │   ├── ReviewViewModel.kt
│   │   │   ├── CommunityViewModel.kt
│   │   │   └── QuantViewModel.kt
│   │   └── ui/
│   │       ├── stock/
│   │       │   ├── StockSelectFragment.kt
│   │       │   └── StockListAdapter.kt
│   │       ├── review/
│   │       │   └── ReviewFragment.kt
│   │       ├── community/
│   │       │   ├── CommunityFragment.kt
│   │       │   └── PostAdapter.kt
│   │       ├── quant/
│   │       │   ├── QuantFragment.kt
│   │       │   └── StrategyAdapter.kt
│   │       ├── auth/
│   │       │   └── AuthActivity.kt
│   │       └── vip/
│   │           └── VipActivity.kt
│   └── src/main/res/
│       ├── layout/
│       ├── menu/
│       ├── navigation/
│       ├── values/
│       └── drawable/
├── build.gradle.kts
├── scripts/
│   ├── verify_p0.sh
│   └── verify_emulator_smoke.sh
└── settings.gradle.kts
```

## 运行方式

1. 用 Android Studio 打开项目
2. 同步 Gradle
3. 运行到模拟器或真机

## 当前工程状态

- 已接入多源行情：腾讯公开 quote 作为主源，新浪 quote 作为股票/指数备用源，东方财富 K 线作为均线备用源；不可用时展示明确离线降级状态。
- 登录、社区、VIP 到期时间均为本机 Room 状态，仅适合 Demo/MVP 验证，不具备商业级账号与权益防篡改能力。
- Debug 可本地模拟支付开通；Release 中 `ALLOW_LOCAL_PAYMENT_SIMULATION=false`，不会直接开通 VIP。
- Release 已开启 R8 混淆与资源压缩，但正式上架仍需要签名、AAB、真实支付/账号服务与隐私合规材料。

## 本地验证

```bash
cd TianXianQuant
scripts/verify_p0.sh
scripts/verify_emulator_smoke.sh
```

## 下一步开发

1. 接入服务端账号与 VIP 权益校验，替换纯本机账号/VIP 状态。
2. 接入正式支付服务端与微信/支付宝回调闭环。
3. 准备 Release 签名、AAB、隐私政策、用户协议与应用商店材料。
4. 扩展 Repository/ViewModel 测试覆盖，补充真实异常路径与权益边界测试。
5. 在授权前提下扩展正式行情/板块/资金数据源。
