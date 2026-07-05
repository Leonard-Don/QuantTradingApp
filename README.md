# Quant Trading App

[![Android P0](https://github.com/Leonard-Don/QuantTradingApp/actions/workflows/android-p0.yml/badge.svg)](https://github.com/Leonard-Don/QuantTradingApp/actions/workflows/android-p0.yml)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-SDK%2035-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

Quant Trading App 是一个 Android 股票研究演示项目，基于 Kotlin、Room、Material Design 和多源公开行情样本构建。项目现在作为公开代码仓库保存，用于学习、复盘流程演示和本机研究工具参考。

## 当前定位

- 公开开源的 Android 示例项目。
- 所有账号、帖子、自选池、提醒、持仓、策略和复盘快照都保存在本机 Room 数据库。
- 仅保留本机研究演示代码和 Android 工程验证。
- 不接入券商交易通道，不提供下单、跟单、代客理财、资金托管或具体买卖指令。
- 所有模型、评分、回测和研究纪要只用于研究记录，不构成投资建议或收益承诺。

## 功能模块

### 选股

- 行情池搜索、排序和本机筛选。
- 行业、市值、估值、成交量、成交额和均线条件。
- 多因子、主力资金、龙虎榜、每日样本池等研究视角，均基于当前可用公开样本字段。
- 个股深度诊断、风险雷达、后续研究动作和本机目标价提醒。

### 复盘

- 市场概况、涨跌统计、成交额、板块轮动、资金流向和龙虎榜观察。
- 历史快照、自选池体检、持仓组合、压力测试、每日研究简报和研究计划。
- 所有结论保持研究参考口径。

### 社区

- 本机帖子、评论和研究纪要。
- 用于沉淀投资逻辑、复盘笔记、量化研究和专题研究。
- 内容仅保存在本机，不上传公共社区。

### 量化

- 历史 K 线模拟回测。
- 自定义公式解析，例如 `close > ma20 && volume > avg_volume_5`。
- 多标的权重组合模拟、模型诊断和研究模型管理。

## 技术栈

- Kotlin 1.9.22
- Android Gradle Plugin 8.6.1
- compileSdk 35 / targetSdk 35
- Material Design 3
- MVVM + ViewBinding
- Navigation Component
- Coroutines + Flow
- OkHttp / Retrofit
- Room
- R8 release 混淆与资源压缩

## 项目结构

```text
QuantTradingApp/
├── app/
│   ├── src/main/java/io/github/leonarddon/quanttrading/
│   │   ├── data/        # Room 实体、DAO、迁移和本机状态仓库
│   │   ├── model/       # 数据模型、诊断、回测和筛选策略
│   │   ├── network/     # 公开行情样本源与缓存回退
│   │   ├── receiver/    # 每日研究提醒
│   │   ├── ui/          # 选股、复盘、社区、量化、账号设置界面
│   │   └── viewmodel/   # 页面状态与业务编排
│   ├── src/test/        # JVM 单元测试
│   └── src/androidTest/ # 仪表化测试
├── docs/
│   ├── DATA_PROVIDER_STRATEGY.md
│   └── legal/
├── scripts/
│   └── verify_all.sh
└── .github/workflows/
    └── android-p0.yml
```

## 本地运行

1. 用 Android Studio 打开 `QuantTradingApp/`。
2. 同步 Gradle。
3. 运行 `app` 到模拟器或真机。

命令行验证：

```bash
cd QuantTradingApp
scripts/verify_p0.sh
```

仓库根目录的一键检查：

```bash
scripts/verify_all.sh
```

## 数据与合规边界

本项目使用公开行情样本和本机缓存做研究演示。公开数据可能延迟、缺失、限流或字段变化；历史模拟不代表未来收益。详细说明见：

- [docs/DATA_PROVIDER_STRATEGY.md](docs/DATA_PROVIDER_STRATEGY.md)
- [docs/legal/DATA_SOURCE_DISCLAIMER.md](docs/legal/DATA_SOURCE_DISCLAIMER.md)
- [docs/legal/PRIVACY_POLICY.md](docs/legal/PRIVACY_POLICY.md)
- [docs/legal/TERMS_OF_SERVICE.md](docs/legal/TERMS_OF_SERVICE.md)

## 公开仓库说明

这个仓库已清理为适合公开展示的本机研究演示项目。旧的远端账号、发布运营和素材脚手架已移除。

## 许可证

MIT License. See [LICENSE](LICENSE).
