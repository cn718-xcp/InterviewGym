package com.interviewgym.core;

/**
 * 会话状态机：ACTIVE（作答中）→ EVALUATED（已评估，终态，不得重评）。
 */
public enum SessionStatus {
    ACTIVE,
    EVALUATED
}
