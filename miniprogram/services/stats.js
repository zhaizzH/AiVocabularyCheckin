/**
 * 统计 API（契约 §7）。
 *
 * 两个接口返回的都是**已补零的连续日期序列**，前端不需要自己补日期；
 * level 也由后端分档完毕，前端不要按 wordCount 自行分档（否则与后端口径不一致）。
 */

const { request } = require('../utils/request');
const { HEATMAP_WEEKS, MASTERY_DAYS } = require('../utils/constants');

/**
 * 打卡热力图。
 * @param {number} [weeks] 默认 12，后端上限 52
 * @returns {Promise<{days:Array<{date:string, wordCount:number, level:number}>}>}
 *   level: 0=未打卡 / 1=1–10 词 / 2=11–20 词 / 3=21+ 词
 */
function getHeatmap(weeks) {
  return request({
    url: '/api/client/stats/heatmap?weeks=' + (weeks || HEATMAP_WEEKS),
  });
}

/**
 * 掌握度曲线。
 *
 * 注意 masteryRate 的语义：无错题时为 **100**（表示「没有未掌握的薄弱点」），
 * 因此**不能用 masteryRate === 0 判断空态**，空态应看三个计数是否全为 0。
 *
 * @param {number} [days] 默认 30，后端上限 365
 * @returns {Promise<{points:Array<{date:string, learnedCount:number,
 *   reviewCount:number, mistakeCount:number, masteryRate:number}>}>}
 */
function getMastery(days) {
  return request({
    url: '/api/client/stats/mastery?days=' + (days || MASTERY_DAYS),
  });
}

module.exports = { getHeatmap, getMastery };
