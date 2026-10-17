/** AI 功能标识。features[] 固定这三个，但不假设顺序。 */
export type AiFeature = 'example' | 'explain' | 'report';

/** 降级原因。 */
export type DegradeReason = 'TIMEOUT' | 'ERROR' | 'CIRCUIT_OPEN';

export interface AiFeatureStat {
  feature: AiFeature;
  total: number;
  success: number;
  degraded: number;
  timeout: number;
  error: number;
  avgCostMs: number;
}

export interface DegradeEvent {
  time: string;
  feature: AiFeature;
  costMs: number;
  reason: DegradeReason;
  result: string;
}

/**
 * GET /api/admin/monitor/ai-stats。
 *
 * 数据来自业务进程内存计数器：**服务启动以来的累计值，重启清零**。
 * `successRate` 是数字百分数（如 92.9），无调用时为 100 —— 不要乘 100。
 */
export interface AiStats {
  totalCalls: number;
  successRate: number;
  degradedCount: number;
  registeredUsers: number;
  features: AiFeatureStat[];
  recentEvents: DegradeEvent[];
}
