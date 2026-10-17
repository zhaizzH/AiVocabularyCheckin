/**
 * 日期工具。
 *
 * 后端日期一律为 'YYYY-MM-DD'，时间 'YYYY-MM-DD HH:mm:ss'（契约 §0）。
 * 不引第三方日期库：小程序里 moment/dayjs 会增加包体，而一期需求只有格式化与日历排布。
 */

/** 'YYYY-MM-DD HH:mm:ss' → 'YYYY-MM-DD'；已是日期格式则原样返回。 */
function formatDate(value) {
  if (!value) {
    return '';
  }
  return String(value).slice(0, 10);
}

/** Date → 'YYYY-MM-DD'（本地时区，不经过 UTC 转换以免差一天）。 */
function toDateString(date) {
  const y = date.getFullYear();
  const m = date.getMonth() + 1;
  const d = date.getDate();
  return y + '-' + (m < 10 ? '0' + m : m) + '-' + (d < 10 ? '0' + d : d);
}

/**
 * 'YYYY-MM-DD' → Date。
 * 按本地时区逐段构造，而不是 new Date('2026-10-16')——后者按 UTC 解析，
 * 在东八区会得到前一天 08:00，weekdayOf 就会整体偏移一天。
 */
function parseDate(dateStr) {
  const parts = String(dateStr).split('-');
  return new Date(Number(parts[0]), Number(parts[1]) - 1, Number(parts[2]));
}

/** 返回 0=周日 … 6=周六。 */
function weekdayOf(dateStr) {
  return parseDate(dateStr).getDay();
}

/** 月/日短格式，如 '10/16'，用于图表横轴。 */
function shortMonthDay(dateStr) {
  const parts = String(dateStr).split('-');
  return Number(parts[1]) + '/' + Number(parts[2]);
}

/** 热力图星期标签（第一行=周日）。 */
const WEEKDAY_LABELS = ['日', '一', '二', '三', '四', '五', '六'];

/**
 * 把后端的热力图序列转成「按列填充」的扁平单元格数组。
 *
 * 为什么是扁平数组而不是二维数组：渲染端用 CSS
 * `grid-auto-flow: column; grid-template-rows: repeat(7, 1fr)`
 * 会自动按列填充，WXML 里只需一层 wx:for，也不必硬编码列数。
 *
 * 后端返回的是**连续日期且已补零**（契约 §7.1），末点是今天。这里只做两件事：
 *   1. 首日不是周日时，前面补 null 占位，保证第一列从周日开始；
 *   2. 末尾补齐到整周，保证每列恰好 7 格（否则最后一列短一截，色块会错位）。
 *
 * @param {Array<{date:string, wordCount:number, level:number}>} days
 * @returns {Array<object|null>}
 */
function buildHeatmapCells(days) {
  const list = days || [];
  if (list.length === 0) {
    return [];
  }

  const cells = [];
  const leading = weekdayOf(list[0].date);
  for (let i = 0; i < leading; i++) {
    cells.push(null);
  }
  for (let i = 0; i < list.length; i++) {
    cells.push(list[i]);
  }
  const trailing = (7 - (cells.length % 7)) % 7;
  for (let i = 0; i < trailing; i++) {
    cells.push(null);
  }
  return cells;
}

module.exports = {
  formatDate,
  toDateString,
  parseDate,
  weekdayOf,
  shortMonthDay,
  buildHeatmapCells,
  WEEKDAY_LABELS,
};
