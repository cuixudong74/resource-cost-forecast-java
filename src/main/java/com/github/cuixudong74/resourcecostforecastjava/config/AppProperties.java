package com.github.cuixudong74.resourcecostforecastjava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 应用配置项。 */
@Component
@ConfigurationProperties(prefix = "cost")
public class AppProperties {

    /** 迟到宽限（毫秒）：账期结束后超过该时间到达视为 LATE_BEYOND_GRACE */
    private long lateGraceMillis = 3_600_000L;

    /** 批次检查点目录 */
    private String checkpointDir = "build/checkpoints";

    /** 批次默认超时（毫秒） */
    private long batchTimeoutMillis = 30_000L;

    /** 预测默认窗口 */
    private int forecastWindow = 7;

    /** 预测默认种子 */
    private long forecastSeed = 42L;

    public long getLateGraceMillis() { return lateGraceMillis; }
    public void setLateGraceMillis(long lateGraceMillis) { this.lateGraceMillis = lateGraceMillis; }
    public String getCheckpointDir() { return checkpointDir; }
    public void setCheckpointDir(String checkpointDir) { this.checkpointDir = checkpointDir; }
    public long getBatchTimeoutMillis() { return batchTimeoutMillis; }
    public void setBatchTimeoutMillis(long batchTimeoutMillis) { this.batchTimeoutMillis = batchTimeoutMillis; }
    public int getForecastWindow() { return forecastWindow; }
    public void setForecastWindow(int forecastWindow) { this.forecastWindow = forecastWindow; }
    public long getForecastSeed() { return forecastSeed; }
    public void setForecastSeed(long forecastSeed) { this.forecastSeed = forecastSeed; }
}
