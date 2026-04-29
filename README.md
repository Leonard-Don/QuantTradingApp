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
- 历史 K 线模拟回测（VIP）
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
│   │   ├── network/              # 多源行情、后端账号/权益同步客户端与错误结果封装
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

- 已接入多源行情：腾讯公开 quote 作为主源，新浪 quote 作为股票/指数备用源，东方财富 K 线作为均线备用源；实时源不可用时优先展示最近本机行情缓存，并明确标注缓存时间。
- 量化回测已使用东方财富日线样本、可选标的代码和日期区间，按收盘价信号、全仓进出和单边成本估算历史指标，不再用静态演示指标冒充回测。
- Android 端登录、社区、VIP 到期时间默认保留本机 Room 演示状态；构建时可通过 `-PtianxianBackendSyncEnabled=true -PtianxianApiBaseUrl=http://10.0.2.2:8080/` 开启后端账号、权益、账号删除和 Debug 沙盒订单同步。
- 仓库已包含本地 FastAPI 后端骨架，用于账号、订单、权益、支付回调和高级数据代理接入。
- Debug 可本地模拟支付开通；Release 中 `ALLOW_LOCAL_PAYMENT_SIMULATION=false`，不会直接开通 VIP。
- Release 已开启 R8 混淆与资源压缩，但正式上架仍需要签名、AAB、真实支付/账号服务与隐私合规材料。

## 本地验证

```bash
cd TianXianQuant
scripts/verify_p0.sh
scripts/verify_emulator_smoke.sh

# 仓库根目录
scripts/verify_backend.sh
scripts/verify_all.sh

# Android 端联调本地后端时
./gradlew :app:assembleDebug \
  -PtianxianBackendSyncEnabled=true \
  -PtianxianApiBaseUrl=http://10.0.2.2:8080/
```

## 商业化与发布资料

- `docs/COMMERCIALIZATION_GAP.md`：当前内测/上架/付费发布差距。
- `docs/SERVER_CONTRACT.md`：最小服务端账号、订单、权益和数据代理契约。
- `docs/PAYMENT_INTEGRATION.md`：微信/支付宝正式接入计划。
- `docs/DATA_PROVIDER_STRATEGY.md`：公开数据与授权数据源边界。
- `docs/RELEASE_CHECKLIST.md`：内部包、商店测试、付费上线检查表。
- `docs/PRIVACY_POLICY_DRAFT.md` / `docs/TERMS_OF_SERVICE_DRAFT.md`：隐私政策和用户协议草案。
- `.github/workflows/android-p0.yml`：GitHub Actions P0 工程检查。

## 下一步开发

1. 部署 `backend/`，在 QA/Release 构建中开启后端同步开关，并接入真实账号服务运维、备份和监控。
2. 接入正式微信/支付宝或 Google Play Billing 商户配置，替换 sandbox 回调。
3. 配置 Release 签名、AAB 上传、隐私政策 URL、用户协议 URL 和应用商店材料。
4. 扩展 Repository/ViewModel 测试覆盖，补充真实异常路径与权益边界测试。
5. 签约授权数据源后，把 `backend/` 高级数据代理从 `not_configured` 切换为真实供应商响应。
