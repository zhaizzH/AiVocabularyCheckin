/**
 * 学习 API（契约 §3）。
 */

const { request } = require('../utils/request');

/**
 * 今日学习计划。
 *
 * words 是「今日任务集合」而非「还剩多少没做」：total 一旦确定不再变化，
 * finished 单调递增，两者都包含当天已上报过的词。因此打卡门槛判断
 * 必须用 finished >= total，不能用固定日词数。
 *
 * @returns {Promise<{date:string, total:number, finished:number, checkedIn:boolean,
 *   words:Array<{wordId:number, bookId:number, word:string, phonetic:string,
 *   translation:string, example:string, isReview:boolean, learned:boolean}>}>}
 */
function getPlan() {
  return request({ url: '/api/client/study/plan' });
}

/**
 * 学习结果上报（逐词）。不认识会自动收录错题本。
 * @param {{wordId:number, bookId:number, known:boolean}} data
 * @returns {Promise<{wordId:number, learned:boolean, addedToMistakes:boolean}>}
 */
function recordStudy(data) {
  return request({
    url: '/api/client/study/record',
    method: 'POST',
    data: data,
  });
}

/**
 * 打卡。前置：当日计划词全部已上报（finished == total）。
 * 错误码：3001 已打卡（幂等）/ 3002 无计划 / 3003 未完成。
 * @returns {Promise<{checkInDate:string, streakDays:number,
 *   todayLearned:number, todayMistakes:number}>}
 */
function checkIn() {
  return request({
    url: '/api/client/study/checkin',
    method: 'POST',
  });
}

/**
 * 连续打卡与累计数据。
 * @returns {Promise<{streakDays:number, totalLearned:number,
 *   todayReviewCount:number, lastCheckInDate:string|null}>}
 */
function getStreak() {
  return request({ url: '/api/client/study/streak' });
}

module.exports = { getPlan, recordStudy, checkIn, getStreak };
