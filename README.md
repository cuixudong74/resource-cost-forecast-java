# 资源成本估算与预算预测告警

一套面向平台资源的成本估算、预测与预算告警能力，替代人工台账。

## 模块结构

```
domain/      不可变领域类型（用量、规则、估算、账单、预算、告警、预测、批次）
rules/       计费规则版本仓库、计费引擎、版本间差异解释
estimation/  用量仓库（迟到/缺失语义）与成本估算服务（含结算）
forecast/    成本预测（可配置窗口与策略，种子可复现）
budget/      预算配置与阈值告警（原子去重）
batch/       批量计算：提交日志、检查点恢复、取消与超时
api/         REST 接口
```

## 计费语义

- **计费公式**：`max(0, 用量 - 免费额度) × 单价`，按规则版本的 `scale`/`roundingMode` 舍入。
- **规则版本化**：`BillingRule` 不可变；`RuleStore.publish` 串行分配版本号并整体原子替换当前版本。
  读者任一时刻拿到的都是某个已提交的完整版本，不会出现字段级混合。
- **可解释复算**：`POST /api/rules/diff?from=&to=` 用同一份用量在两个版本下复算，
  逐指标给出单价变化、成本差与总差（`RuleDiff`）。
- **结算不可改写**：`settle` 以结算时刻的规则版本计算并冻结为 `SettledBill`；
  后续规则变更不影响已结算账单，已结算周期参与新估算时引用冻结金额。
- **归属可追溯**：每个 `CostEstimate` 记录 `ruleVersion`。
- **幂等**：`estimateId` 由 (team, resource, periods, ruleVersion) 确定性生成，重复触发收敛到同一结果。

## 缺失与迟到数据

- 缺失周期生成 `MISSING` 占位（`value=null`），**绝不用 0 冒充**。
- 迟到判定：用量到达时间 `arrivedAt` 晚于迟到截止时刻（`EstimationService.setLateCutoff`
  或预测接口的 `lateCutoff` 参数）即标记为 `LATE`。
- 估算与预测跳过缺失/迟到数据，并在结果的 `skipped` 中区分原因
  （`MISSING_DATA` / `LATE_DATA`），保留原始到达时间。

## 成本预测

`POST /api/forecasts`，配置 `ForecastConfig`：

| 字段 | 含义 |
|---|---|
| `window` | 移动平均窗口 |
| `horizon` | 外推步数 |
| `strategy` | `MOVING_AVERAGE` / `LINEAR_TREND` / `HOLT_DOUBLE_EXPONENTIAL` |
| `seed` | 随机种子（蒙特卡洛区间） |
| `simulations` | 模拟次数 |

**可复现性**：所有随机性来自 `new Random(seed)`，相同输入 + 相同种子结果逐次一致。

## 预算与告警

- `Budget` 按团队 + 资源维度配置（`resourceId=null` 表示团队级），`thresholds` 为触发比例（如 0.8、1.0）。
- `AlertService.evaluate` 在实际成本跨越阈值时产生告警；
  以 `alertKey = budgetId|period|threshold` 原子占位去重——同一阈值同一周期只告警一次，
  并发评估也不多不少。每条告警的 `reason` 携带触发依据（实际值、阈值、预算额）。

## 批量计算：恢复、取消与超时

- `JournalStore` 将批次参数（`START`）与每个已提交估算（`COMMIT`）追加到
  `${journal.dir}/journal.jsonl`（默认 `./data/journal`，可用 `journal.dir` 配置）。
- 重启后 `resume(batchId)` 从日志中最近已提交位置继续；已提交结果按 `estimateId`
  全局去重，**不重复计费、不丢失已提交结果**；未提交部分与已提交结果可区分。
- 取消（`cancel`）与超时（`timeoutMillis`）只保留已提交结果，未提交部分不产生任何写入；
  重复提交同 `batchId` 收敛到同一任务。

## REST 接口速览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/rules` | 发布新规则版本 |
| GET | `/api/rules/current`、`/api/rules/{version}`、`/api/rules` | 查询版本 |
| POST | `/api/rules/diff?from=&to=` | 版本间差异复算 |
| POST | `/api/usage` | 上报用量 |
| POST | `/api/estimates?teamId=&resourceId=&periods=&ruleVersion=` | 成本估算 |
| POST/GET | `/api/settlements` | 结算 / 查询已结算账单 |
| POST | `/api/forecasts` | 成本预测 |
| POST/GET | `/api/budgets` | 预算配置 |
| POST | `/api/alerts/evaluate` | 评估告警 |
| GET | `/api/alerts` | 告警列表 |
| POST | `/api/batches` | 提交批次（`timeoutMillis` 可选） |
| POST | `/api/batches/{id}/cancel`、`/resume` | 取消 / 恢复 |
| GET | `/api/batches/{id}` | 批次状态 |

## 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `journal.dir` | `./data/journal` | 提交日志目录（恢复检查点） |

## 测试

`mvn test` 覆盖：规则版本切换与差异解释、规则并发读写完整性、缺失/迟到数据语义、
预测可复现性、告警去重（含并发）、批次超时/取消/重启恢复与幂等收敛。
