import type { AiStats } from '../types/monitor';
import { get } from '../utils/request';

/**
 * AI 调用监控。
 *
 * 数据是业务进程内存计数器：服务启动以来累计，重启清零。
 * 页面需注明这一点，否则会被当成 bug。
 */
export function getAiStats(): Promise<AiStats> {
  return get<AiStats>('/api/admin/monitor/ai-stats');
}
