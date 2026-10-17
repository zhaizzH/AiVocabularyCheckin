/**
 * 错题 API（契约 §5）。
 */

const { request } = require('../utils/request');

/**
 * 错题列表（分页）。
 *
 * 注意 mastered 的「请求参数」是 0/1 数字，而「响应体里的 mastered」是 JSON 布尔
 * （契约 §5.1 明确二者不同源）——不要用同一个值直接互传。
 *
 * @param {{mastered?:number, page?:number, size?:number}} options mastered: 0 未掌握 / 1 已掌握
 * @returns {Promise<{list:Array<{mistakeId:number, wordId:number, word:string,
 *   phonetic:string, translation:string, source:string, wrongCount:number,
 *   mastered:boolean, createTime:string}>, total:number, page:number, size:number}>}
 */
function listMistakes(options) {
  const opts = options || {};
  const mastered = opts.mastered === 1 ? 1 : 0;
  const page = opts.page || 1;
  const size = opts.size || 20;
  return request({
    url: '/api/client/mistakes?mastered=' + mastered + '&page=' + page + '&size=' + size,
  });
}

/**
 * 标记已掌握。4001 不存在 / 4002 已掌握。
 * @param {number} mistakeId
 * @returns {Promise<{mistakeId:number, mastered:boolean}>}
 */
function markMastered(mistakeId) {
  return request({
    url: '/api/client/mistakes/' + mistakeId + '/mastered',
    method: 'POST',
  });
}

/**
 * 移除错题（逻辑删除）。
 * @param {number} mistakeId
 * @returns {Promise<{mistakeId:number, removed:boolean}>}
 */
function removeMistake(mistakeId) {
  return request({
    url: '/api/client/mistakes/' + mistakeId,
    method: 'DELETE',
  });
}

module.exports = { listMistakes, markMastered, removeMistake };
