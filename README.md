# 资源成本估算与预算预测告警

一套面向平台资源的成本估算、预测与预算告警服务（Spring Boot / Java 17）。

## 能力总览

| 能力 | 入口 | 说明 |
| --- | --- | --- |
| 版本化计费规则 | `POST /api/rules` | 原子发布完整规则快照，历史版本永久保留 |
| 成本估算 | `POST /api/estimates` | 按用量 + 指定（或当前）规则版本估算，明细可解释 |
| 账单结算 | `POST /api/estimates/settle` | 结算后账单不可变，幂等 |
| 跨版本复算 | `POST /api/estimates/bills/{billId}/recompute` | 同一用量在新规则下复算，输出差异与解释 |
| 成本预测 | `POST /api/forecast` | 窗口 + 策略外推，种子可复现 |
| 预算与告警 | `POST /api/budgets` | 按团队/资源阈值告警，去重且带触发依据 |
| 批次任务 | `POST /api/batches` | 支持取消、超时、幂等重试与重启恢复 |

## 计费语义

### 规则版本化（RuleStore）
- 每次发布生成一个**完整不可变快照** `RuleSet{version, committedAt, rules}`，版本号单调递增。
- 发布是原子的：先写入历史版本表，再原子切换当前指针。任意并发读取只能看到
  某个**已提交的完整版本**，绝不出现字段级混合（由 `ConcurrencyTest` 验证）。
- 历史版本永久保留，`GET /api/rules/{version}` 可取任意版本，
  `GET /api/rules/diff?from=&to=` 给出版本间可解释差异。

### 估算与结算（EstimationService / CostEstimator）
- 单条用量计费：`billable = max(0, quantity - freeQuota)`，`amount = billable * unitPrice`，
  每条 `CostLineItem` 附带完整算式说明。
- 每次估算记录归属的 `ruleVersion`，可追溯。
- **结算（settle）把估算固化为不可变 `Bill`**：账单永远归属结算时的规则版本，
  后续规则变更不得改写历史账单；同一 `estimateId` 重复结算幂等返回同一账单。
- `recompute` 只是假设性对比：返回新旧版本金额差与规则差异解释，不触碰原账单。

### 缺失与迟到数据（UsageStore）
- 用量按 `recordId` 幂等去重，重复摄入返回 `DUPLICATE_RECORD`。
- 缺失账期用 `MISSING` 占位记录表示，估算时以 `MISSING_DATA` 原因跳过——
  **绝不用零值冒充缺失数据**。
- 到达时间晚于账期结束的记录标记为 `LATE`，**原始到达时间永久保留**；
  超过迟到宽限（`cost.late-grace-millis`）的迟到数据以 `LATE_BEYOND_GRACE` 跳过。
- 迟到数据到达时可补登替换 MISSING 占位。

### 成本预测（Forecaster）
- 策略：`MOVING_AVERAGE`（窗口均值）、`LINEAR_TREND`（窗口最小二乘外推）。
- 窗口长度、外推步数、随机种子均可配置。
- 缺失账期不参与拟合（不补零）。
- 预测含 ±5% 内、随历史波动缩放的种子扰动；**相同输入 + 相同种子 => 结果逐次复现**
  （`ForecastDeterminismTest` 验证）。

### 预算与告警（BudgetService）
- 预算按 `teamId + resourceId`（`resourceId=null` 表示团队全部资源）配置，
  阈值为限额比例（如 `0.8`、`1.0`）。
- 告警去重键：`budgetId | threshold | resourceId | 账期起点`——
  同一键并发/重复评估**只告警一次**（`putIfAbsent`），新账期重新生效（不漏报）。
- 每条告警的 `basis` 字段给出触发依据（估算 ID、实际金额、阈值金额、规则版本）。

### 批次任务：取消、超时与恢复（BatchProcessor / CheckpointStore）
- `submit` 幂等：同一 `jobId` 重复触发收敛到同一已提交结果，不重复计费。
- 提交采用状态机（PENDING→RUNNING→COMMITTED/CANCELLED/TIMED_OUT/FAILED）：
  只有 CAS 到 COMMITTED 的执行路径才写结果；**取消/超时后丢弃全部中间结果，
  不留部分写入**。
- 已提交批次先落盘（单行 JSON + fsync 的提交日志）再入内存；
  重启后 `recover()` 从最近已提交位置恢复，未完成批次不可见、可安全重跑。
- 崩溃残留的损坏尾行在加载时被忽略。

## 配置项（application.properties）

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `cost.late-grace-millis` | 3600000 | 迟到宽限，超时到达按 `LATE_BEYOND_GRACE` 跳过 |
| `cost.checkpoint-dir` | `build/checkpoints` | 批次提交日志目录（重启恢复依据） |
| `cost.batch-timeout-millis` | 30000 | 批次默认超时 |
| `cost.forecast-window` | 7 | 预测默认窗口（历史账期数） |
| `cost.forecast-seed` | 42 | 预测默认随机种子 |

## 测试

```bash
mvn test
```

覆盖：规则版本切换与账单不可变（`RuleVersionTest`）、并发读写一致性
（`ConcurrencyTest`）、缺失/迟到数据（`MissingLateDataTest`）、重启恢复
（`RecoveryTest`）、告警去重（`AlertDedupTest`）、预测可复现
（`ForecastDeterminismTest`）、取消与超时（`CancellationTimeoutTest`）。
