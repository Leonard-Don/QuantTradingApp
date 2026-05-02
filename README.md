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
- 市场温度与结构判断（宽度、成交额方向、板块扩散、自选一致性、指数联动、数据质量、风险雷达）
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
- 复盘页已增加市场温度模型，基于当前行情池样本计算宽度、成交额方向、板块扩散、自选池一致性、指数联动、行情来源/覆盖率诊断、重点样本、风险项和研究动作；市场温度和数据质量摘要会写入本机历史快照，用于展示最近趋势和上一快照对比，并同步进入每日研究简报与研究计划。所有结论保持研究参考口径，不输出买卖指令。
- Android 端登录、社区、VIP 到期时间默认保留本机 Room 演示状态；账号页提供手动权益同步、账号删除、隐私/协议和内测支持说明；构建时可通过 `-PtianxianBackendSyncEnabled=true -PtianxianApiBaseUrl=http://10.0.2.2:8080/` 开启后端账号、访问令牌刷新、权益、账号删除和 Debug 沙盒订单同步。
- QA 后端联调构建可通过 `-PtianxianRequireBackendPaymentSync=true` 要求 VIP 开通必须由服务端订单/回调/权益链路完成，后端失败时不会回退本机演示权益。
- VIP 页展示最近订阅订单状态；Android 本机缓存订单状态、金额、渠道、权益快照和来源，后端提供 `GET /v1/me/orders` 用于刷新服务端订单历史。
- 仓库已包含本地 FastAPI 后端骨架，用于账号、订单、权益、支付回调和高级数据代理接入。
- Debug 可本地模拟支付开通；Release 中 `ALLOW_LOCAL_PAYMENT_SIMULATION=false`，不会直接开通 VIP。
- Release 已开启 R8 混淆与资源压缩；正式付费/商店发布前需通过 `verifyPaidReleaseConfig` 闸门，确认生产 API、强制服务端权益、签名、隐私政策、用户协议、数据免责声明和客服邮箱均已配置。

## 本地验证

```bash
cd TianXianQuant
scripts/verify_p0.sh
scripts/verify_emulator_smoke.sh

# 仓库根目录
scripts/verify_backend.sh
scripts/verify_all.sh
scripts/build_qa_backend_debug.sh
scripts/prepare_store_candidate.sh
scripts/capture_store_screenshots.sh
scripts/verify_paid_release_config.sh

# Android 端联调本地后端时
./gradlew :app:assembleDebug \
  -PtianxianBackendSyncEnabled=true \
  -PtianxianApiBaseUrl=http://10.0.2.2:8080/

# 后端权益强制 QA 构建
TIANXIAN_API_BASE_URL=http://10.0.2.2:8080/ scripts/build_qa_backend_debug.sh

# 付费/商店发布配置闸门，未配置外部生产资源时会故意失败
TIANXIAN_PRODUCTION_API_BASE_URL=https://api.example.com/ \
TIANXIAN_PRIVACY_POLICY_URL=https://example.com/privacy \
TIANXIAN_TERMS_URL=https://example.com/terms \
TIANXIAN_DATA_DISCLAIMER_URL=https://example.com/data-disclaimer \
TIANXIAN_SUPPORT_EMAIL=support@example.com \
TIANXIAN_RELEASE_KEYSTORE=/secure/path/release.keystore \
TIANXIAN_RELEASE_STORE_PASSWORD='***' \
TIANXIAN_RELEASE_KEY_ALIAS=tianxian-upload \
TIANXIAN_RELEASE_KEY_PASSWORD='***' \
scripts/verify_paid_release_config.sh
```

## 商业化与发布资料

- `docs/COMMERCIALIZATION_GAP.md`：当前内测/上架/付费发布差距。
- `docs/SERVER_CONTRACT.md`：最小服务端账号、订单、权益和数据代理契约。
- `docs/PAYMENT_INTEGRATION.md`：微信/支付宝正式接入计划。
- `docs/DATA_PROVIDER_STRATEGY.md`：公开数据与授权数据源边界。
- `docs/RELEASE_CHECKLIST.md`：内部包、商店测试、付费上线检查表。
- `docs/RELEASE_SIGNING.md`：Release 签名、外部密钥和候选包配置说明。
- `docs/RELEASE_CANDIDATE_SUMMARY.md`：当前本地候选状态、已完成项和外部阻塞项。
- `docs/PRIVACY_POLICY_DRAFT.md` / `docs/TERMS_OF_SERVICE_DRAFT.md`：隐私政策和用户协议草案。
- `docs/STORE_LISTING_DRAFT.md`：商店短描述、长描述、截图计划、通知权限和数据源免责声明草案。
- `docs/MANUAL_QA_MATRIX.md`：真机/人工 QA 矩阵。
- `store_assets/`：由 `scripts/generate_store_assets.py` 生成的商店展示图和素材说明。
- `.github/workflows/android-p0.yml`：GitHub Actions P0 工程检查。
- `backend/Dockerfile` / `render.yaml`：后端容器化与 Render Blueprint 部署模板。

## 下一步开发

1. 用 `render.yaml` 或等价平台部署 `backend/`，设置持久化数据库和 `TIANXIAN_PAYMENT_CALLBACK_SECRET`。
2. 接入正式微信/支付宝或 Google Play Billing 商户配置，替换 sandbox 回调。
3. 发布隐私政策、用户协议、数据免责声明 URL，并用 `scripts/verify_paid_release_config.sh` 做发布闸门。
4. 按 `docs/MANUAL_QA_MATRIX.md` 完成真机人工 QA。
5. 签约授权数据源后，把 `backend/` 高级数据代理从 `not_configured` 切换为真实供应商响应。
