# QuantTradingApp Store Listing Draft

Last reviewed: 2026-05-02

## App Name

Quant 交易台

## Short Description

股票研究、复盘、选股和历史模拟工具，不提供实盘交易入口。

## Full Description

Quant 交易台是一款面向个人研究者的股票复盘与量化研究工具。应用提供行情样本观察、自选池管理、市场复盘、社区研究记录、策略模型和历史 K 线模拟回测，帮助用户整理研究流程、记录假设、复查风险。

核心功能：

- 多源公开行情样本与本机缓存回退。
- 条件选股、自选池、深度诊断和研究计划。
- 市场宽度、板块轮动、历史快照和每日研究简报。
- 本机社区笔记、评论和研究纪要。
- 历史 K 线模拟回测、自定义公式和模型诊断。
- 账号、权益同步、账号删除、通知提醒和合规说明入口。

合规说明：

- 本应用不接入券商交易通道。
- 本应用不提供下单、跟单、代客理财或资金托管功能。
- 所有诊断、简报、模型和回测仅作研究参考，不构成投资建议、收益承诺或具体买卖指令。
- 高级行情字段和付费数据能力必须在授权数据源接入后才能对外宣传。

## Screenshot Plan

Required capture set before store test upload:

1. 选股首页：展示样本行情池、筛选和数据源状态。
2. 复盘页：展示市场概况、板块轮动和历史快照入口。
3. 量化页：展示模型列表、历史模拟入口和研究免责声明。
4. VIP 页：展示订阅层级、账号权益和正式支付未配置提示。
5. 账号页：展示隐私、协议、支持、账号删除和通知入口。

Local capture command:

```bash
scripts/capture_store_screenshots.sh
```

This command captures emulator screenshots into `store_assets/screenshots/` while running the smoke path. Before upload, recapture from the signed store-test candidate and inspect on a real device.

## Generated Visual Assets

```bash
scripts/generate_store_assets.py
```

Outputs:

- `store_assets/feature_graphic_1024x500.png`
- `store_assets/icon_preview_512x512.png`
- `store_assets/promo_card_1200x630.png`
- `store_assets/ASSET_MANIFEST.md`

## Notification Permission Copy

每日研究提醒用于在本机提醒用户回到应用整理复盘、查看自选池状态和记录研究动作。拒绝通知权限不影响核心功能。

## Data Source Disclaimer

行情和 K 线来自公开或授权数据源，可能存在延迟、缺失、源站不可用或字段调整。应用会尽量展示数据源、缓存时间和异常状态。任何研究报告、模型信号和历史模拟结果均不构成投资建议。

## Production URLs To Configure

- Privacy policy URL: `QUANTTRADING_PRIVACY_POLICY_URL`
- Terms URL: `QUANTTRADING_TERMS_URL`
- Data disclaimer URL: `QUANTTRADING_DATA_DISCLAIMER_URL`
- Support email: `QUANTTRADING_SUPPORT_EMAIL`

Run `scripts/verify_paid_release_config.sh` before a paid/store release. The gate intentionally fails until these values and the production API are configured.
