/**
 * 复习 API（契约 §4）。
 */

const { request } = require('../utils/request');

/**
 * 今日待复习。不分页（单日待复习量有界，契约 §4.1）。
 * @returns {Promise<{total:number, words:Array<{wordId:number, word:string,
 *   phonetic:string, translation:string, example:string,
 *   stage:number, wrongCount:number}>}>}
 */
function getTodayReview() {
  return request({ url: '/api/client/review/today' });
}

/**
 * 提交复习结果。
 *
 * 注意请求体**不带 bookId**（契约 §4.2）：复习进度按 wordId 定位，
 * 后端从进度行反查词书。多传 bookId 会被后端忽略，但语义上不应传。
 *
 * @param {{wordId:number, correct:boolean}} data
 * @returns {Promise<{wordId:number, stage:number, nextReviewDate:string}>}
 */
function recordReview(data) {
  return request({
    url: '/api/client/review/record',
    method: 'POST',
    data: data,
  });
}

module.exports = { getTodayReview, recordReview };
