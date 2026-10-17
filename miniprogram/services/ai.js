/**
 * AI API（契约 §6）。
 *
 * 全部经业务后端中转，**前端绝不直连 ai-service:8000**——
 * AI 内容由后端注入密钥并决定是否降级，前端只负责展示。
 *
 * 三个接口超时分别为 15/30/60 秒，必须小于后端对应超时，
 * 否则前端先断而后端仍在计算，用户看到失败但后端实际成功。
 */

const { request } = require('../utils/request');
const { AI_TIMEOUT } = require('../utils/constants');

/**
 * AI 例句。
 *
 * 注意 degraded 语义：AI 不可用时后端返回 **HTTP 200 + code "0" + degraded:true**，
 * example 回退为词库内置例句、mnemonic 为空串（契约 §6.1）。
 * 因此调用方不能只看 resolve 就当作「AI 成功」，必须检查 degraded。
 *
 * @param {number} wordId
 * @returns {Promise<{wordId:number, example:string, exampleTranslation:string,
 *   mnemonic:string, degraded:boolean}>}
 */
function getExample(wordId) {
  return request({
    url: '/api/client/ai/example',
    method: 'POST',
    data: { wordId: wordId },
    timeout: AI_TIMEOUT.example,
  });
}

/**
 * AI 错题讲解。
 *
 * 与例句不同，讲解**没有内置兜底内容**：降级时后端直接返回
 * 5001（超时）/ 5002（其它异常），不会给 degraded 响应，调用方按失败处理。
 *
 * @param {number} mistakeId
 * @returns {Promise<{mistakeId:number, explanation:string, advice:string, degraded:boolean}>}
 */
function explainMistake(mistakeId) {
  return request({
    url: '/api/client/ai/explain',
    method: 'POST',
    data: { mistakeId: mistakeId },
    timeout: AI_TIMEOUT.explain,
  });
}

/**
 * AI 学习报告。无请求体（后端汇总统计后自行传给 AI）。
 *
 * 前置校验在后端：累计学习天数 < 3 → 5003，且**不发起对 ai-service 的调用**。
 *
 * @returns {Promise<{report:{overview:string, weakness:string, suggestion:string},
 *   generatedAt:string, degraded:boolean}>}
 */
function getReport() {
  return request({
    url: '/api/client/ai/report',
    method: 'POST',
    timeout: AI_TIMEOUT.report,
  });
}

module.exports = { getExample, explainMistake, getReport };
