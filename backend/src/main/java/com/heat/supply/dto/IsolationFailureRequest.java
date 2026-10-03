package com.heat.supply.dto;

import lombok.Data;

/** 中途失败的三选一收口请求 */
@Data
public class IsolationFailureRequest {

    /** EXPAND 扩大隔离（按结果重新试算）/ ROLLBACK 按安全顺序恢复已动阀门 / HOLD 保持现场等待处置 */
    private String outcome;

    private String note;

    private String operator;
}
