package com.yymm.monitor.data.model

/**
 * 规则状态枚举（TECH_SPEC 2.1.1）。
 *
 * 持久化时以字符串形式存入 MatchRuleEntity.ruleStatus 字段。
 */
enum class RuleStatus {
    STANDBY,   // 备战区（待命）
    ACTIVE     // 匹配区（激活）
}
