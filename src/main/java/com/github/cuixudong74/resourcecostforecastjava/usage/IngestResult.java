package com.github.cuixudong74.resourcecostforecastjava.usage;

import com.github.cuixudong74.resourcecostforecastjava.domain.SkipReason;

/** 用量摄入结果：是否被接受，以及（若拒绝/跳过）原因。 */
public record IngestResult(String recordId, boolean accepted, SkipReason reason) {
}
