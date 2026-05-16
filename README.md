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

## 项目状态

**v1.0.0 代码侧收口** (2026-05-16) — feature development frozen on the codebase side.

天线量化的代码已经到达"等待上架"的稳定状态：所有功能模块、合规约束、付费闸门、签名管道、QA 验证脚本都已完成，剩下的事情**都是运营 / Ops 工作，不再需要写代码**。

### 代码侧已完成（不再迭代）

- **多源行情接入**：腾讯主源 + 新浪 / 东方财富备源，缓存降级展示明确标注时效。所有 quote provider 经 PR #5–#16 完成 non-finite 值清洗
- **量化回测**：东方财富日线样本，按收盘价信号、全仓进出、单边成本估算历史指标，研究参考口径
- **复盘页市场温度模型**：宽度 / 成交额方向 / 板块扩散 / 自选池一致性 / 指数联动 / 风险项 / 研究动作；历史快照对比 + 每日研究简报联动
- **后端契约**：FastAPI + SQLite (Postgres-ready)，覆盖 auth / token refresh / entitlements / order status / refunds / payment callbacks (HMAC-SHA256) / admin audit / 高级数据代理占位
- **付费闸门**：`scripts/verify_paid_release_config.sh` 检查生产 API + 强制服务端权益 + 签名 + 法律 URL + 客服邮箱；未配置外部资源时主动失败
- **签名管道**：通过 `release.env` 外部注入 keystore；`*.jks` 已加 `.gitignore`，本地秘密文件不会被提交
- **R8 混淆 + 资源压缩**：release 构建已开启；`ALLOW_LOCAL_PAYMENT_SIMULATION=false` 在 release 中强制
- **合规姿态守住**：grep 验证仓库 zero 个 broker SDK / trading entry / order-placement 接口（详见"合规红线"段）
- **完整 PIPL-compliant 法律文档**：[docs/legal/PRIVACY_POLICY.md](docs/legal/PRIVACY_POLICY.md)、[docs/legal/TERMS_OF_SERVICE.md](docs/legal/TERMS_OF_SERVICE.md)、[docs/legal/DATA_SOURCE_DISCLAIMER.md](docs/legal/DATA_SOURCE_DISCLAIMER.md)
- **GitHub Pages 自动渲染**：`.github/workflows/legal-docs-pages.yml` ready，启用 GitHub Pages 即生效
- **发布运营文档完整**：[docs/RELEASE_USER_CHECKLIST.md](docs/RELEASE_USER_CHECKLIST.md) 10 步、[docs/RELEASE_BLOCKERS.md](docs/RELEASE_BLOCKERS.md) 实测 gate 输出、[docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md) keystore 配方

### 代码侧不再接受新功能

后续维护策略：
- 仅接受 **blocking bug** / 数据源结构变化（腾讯 / 新浪 / 东方财富 schema drift）/ 安全问题修复
- 不再向 Android / Backend 添加新模块、新页面、新策略
- 测试与构建回归（`scripts/verify_all.sh` + `:app:verifyPaidReleaseConfig`）是收口验证基线

### 你（运营方）要做的事情都在 RELEASE_USER_CHECKLIST.md

剩下的 4 类工作**全部需要你的判断或秘密**，**不需要碰代码**：

1. **填法律文档占位符** — 3 份 `docs/legal/*.md` 里的 `【上架前由运营方填写】`（运营主体、地址、support 邮箱）
2. **启用 GitHub Pages** — Settings → Pages → Source = GitHub Actions，触发 `legal-docs-pages.yml` 工作流
3. **生成签名 keystore** — `keytool -genkeypair -v -keystore ~/Library/Application\ Support/tianxian/release.jks ...`，填 `release.env`
4. **部署后端到 Render** + 接入 WeChat/Alipay / Play Billing 商户 sandbox

详细步骤见 [docs/RELEASE_USER_CHECKLIST.md](docs/RELEASE_USER_CHECKLIST.md)（10 个有序步骤 + 完整的命令模板）。

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
- `docs/legal/PRIVACY_POLICY.md` / `docs/legal/TERMS_OF_SERVICE.md` / `docs/legal/DATA_SOURCE_DISCLAIMER.md`：PIPL-compliant 正式法律文档（运营方上架前填占位符即可）。
- `docs/STORE_LISTING_DRAFT.md`：商店短描述、长描述、截图计划、通知权限和数据源免责声明草案。
- `docs/MANUAL_QA_MATRIX.md`：真机/人工 QA 矩阵。
- `store_assets/`：由 `scripts/generate_store_assets.py` 生成的商店展示图和素材说明。
- `.github/workflows/android-p0.yml`：GitHub Actions P0 工程检查。
- `backend/Dockerfile` / `render.yaml`：后端容器化与 Render Blueprint 部署模板。

## 运营方上架步骤（不需要写代码）

详细 10 步流程见 [docs/RELEASE_USER_CHECKLIST.md](docs/RELEASE_USER_CHECKLIST.md)，包括：

1. 用 `render.yaml` 或等价平台部署 `backend/`，设置持久化数据库和 `TIANXIAN_PAYMENT_CALLBACK_SECRET`
2. 接入正式微信/支付宝或 Google Play Billing 商户配置，替换 sandbox 回调
3. 在 GitHub Pages 启用后填法律文档占位符，发布 privacy / terms / disclaimer URL
4. 在 Settings → Secrets 配 keystore base64 + 4 个 release env，触发 `scripts/build_release_artifacts.sh`
5. 按 `docs/MANUAL_QA_MATRIX.md` 完成真机人工 QA
6. 签约授权数据源后，把 `backend/` 高级数据代理从 `not_configured` 切换为真实供应商响应

这些都是**外部资源接入工作**，代码侧已经为每一步准备好了入口和闸门。
