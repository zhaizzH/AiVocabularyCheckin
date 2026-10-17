/**
 * 词库 API（契约 §2）。
 */

const { request } = require('../utils/request');

/**
 * 词书列表。响应是**裸数组**（契约 §2.1：词书个位数量级，不分页）。
 * @param {string} [category] '' 全部 / 'CET4' / 'CET6' / 'KAOYAN'
 * @returns {Promise<Array<{bookId:number, name:string, category:string,
 *   wordCount:number, description:string|null, selected:boolean, learnedCount:number}>>}
 */
function listBooks(category) {
  const query = category ? '?category=' + encodeURIComponent(category) : '';
  return request({ url: '/api/client/books' + query });
}

/**
 * 词书详情（含单词分页）。
 * @param {number} bookId
 * @param {number} [page]
 * @param {number} [size]
 * @returns {Promise<{bookId:number, name:string, category:string, wordCount:number,
 *   words:{list:Array, total:number, page:number, size:number}}>}
 */
function getBookDetail(bookId, page, size) {
  const p = page || 1;
  const s = size || 20;
  return request({
    url: '/api/client/books/' + bookId + '?page=' + p + '&size=' + s,
  });
}

/**
 * 加入词书。已加入时后端返回 2003，调用方应视为成功。
 * @param {number} bookId
 * @returns {Promise<{bookId:number, selected:boolean}>}
 */
function selectBook(bookId) {
  return request({
    url: '/api/client/books/' + bookId + '/select',
    method: 'POST',
  });
}

/**
 * 移出词书（历史学习记录保留）。
 * @param {number} bookId
 * @returns {Promise<{bookId:number, selected:boolean}>}
 */
function unselectBook(bookId) {
  return request({
    url: '/api/client/books/' + bookId + '/select',
    method: 'DELETE',
  });
}

module.exports = { listBooks, getBookDetail, selectBook, unselectBook };
