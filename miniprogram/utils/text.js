/**
 * 文本处理。
 *
 * 后端的 translation 是**单个字符串**，多义项以换行/分号并列，
 * 例如 "v. 放弃；抛弃\nn. 放任"。前端要「多义项分条」就必须自己拆。
 */

const ELLIPSIS = '…';

/**
 * 拆分释义为多条。
 *
 * 先按换行拆（一个换行通常是一个词性），再按中文分号「；」或英文「;」
 * 拆同词性下的多个义项。空输入返回空数组而不是 ['']，
 * 让 WXML 的 wx:if 能直接用 `list.length` 判断有无内容。
 *
 * @param {string} translation
 * @returns {string[]}
 */
function splitTranslation(translation) {
  if (!translation) {
    return [];
  }
  const lines = String(translation).split(/\r?\n/);
  const result = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line) {
      continue;
    }
    const pieces = line.split(/[；;]/);
    for (let j = 0; j < pieces.length; j++) {
      const piece = pieces[j].trim();
      if (piece) {
        result.push(piece);
      }
    }
  }
  return result;
}

/** 超长截断，用于错题条目「释义一行截断」。 */
function truncate(text, maxLength) {
  if (!text) {
    return '';
  }
  const str = String(text);
  if (str.length <= maxLength) {
    return str;
  }
  return str.slice(0, maxLength) + ELLIPSIS;
}

module.exports = { splitTranslation, truncate };
