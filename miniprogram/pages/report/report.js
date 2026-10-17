/**
 * AI 学习报告页（F8，入口在「我的」）。
 *
 * 三种终态，互斥：
 *   1. 成功   → 分段渲染 概况/薄弱/建议 + 生成时间
 *   2. 数据不足 → 5003，提示「学几天再来」，不给重试按钮
 *      （重试也没用，数据不会因为重试而变多）
 *   3. 失败   → 5001/5002/网络，提示 + 重试按钮
 *
 * 与 AI 例句不同，报告**没有本地兜底**：ai-service 不可用时后端直接抛
 * 5001/5002（契约 §6.3），所以这里不存在 degraded 分支。
 */

const aiService = require('../../services/ai');
const { ERROR_CODES } = require('../../utils/constants');

Page({
  data: {
    // 'loading' | 'success' | 'insufficient' | 'error'
    state: 'loading',
    report: null,
    generatedAt: '',
    errorMsg: '',
  },

  onLoad() {
    this.loadReport();
  },

  async loadReport() {
    this.setData({ state: 'loading', errorMsg: '' });

    try {
      const result = await aiService.getReport();
      this.setData({
        state: 'success',
        report: (result && result.report) || null,
        generatedAt: (result && result.generatedAt) || '',
      });
    } catch (err) {
      const code = err && err.code;

      if (code === ERROR_CODES.AI_REPORT_DATA_INSUFFICIENT) {
        // 数据不足是业务判断，不是故障：不提供重试（重试也不会变好）
        this.setData({
          state: 'insufficient',
          errorMsg: '数据还太少，学几天再来生成报告',
        });
        return;
      }

      let msg = '报告生成失败，请稍后重试';
      if (code === ERROR_CODES.AI_TIMEOUT || code === 'TIMEOUT') {
        msg = '报告生成超时，请稍后重试';
      } else if (code === ERROR_CODES.AI_ERROR) {
        msg = 'AI 服务暂时不可用，请稍后重试';
      } else if (code === 'NETWORK') {
        msg = '网络不给力，请检查网络后重试';
      }
      this.setData({ state: 'error', errorMsg: msg });
    }
  },

  onRetry() {
    this.loadReport();
  },

  goStudy() {
    wx.switchTab({ url: '/pages/study/study' });
  },
});
