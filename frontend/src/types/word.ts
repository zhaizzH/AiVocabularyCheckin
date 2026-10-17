/** 单词（管理端分页元素）。phonetic/translation/example 实测可能为 null。 */
export interface Word {
  wordId: number;
  word: string;
  phonetic: string | null;
  translation: string | null;
  example: string | null;
}

/** 新增/修改单词的请求体（修改时字段均可选）。 */
export interface WordUpsertRequest {
  bookId?: number;
  word?: string;
  phonetic?: string;
  translation?: string;
  example?: string;
}

export interface WordDeleteResult {
  wordId: number;
  deleted: boolean;
}
