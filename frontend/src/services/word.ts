import type { PageResult } from '../types/common';
import type { Word, WordDeleteResult, WordUpsertRequest } from '../types/word';
import { del, get, post, put } from '../utils/request';

export interface ListWordsParams {
  bookId?: number;
  keyword?: string;
  page?: number;
  size?: number;
}

/**
 * 单词分页查询。
 *
 * bookId 可选：不传时后端跨全书库查询（实测 total = 22761）。
 * 返回**分页包装** { list, total, page, size }，与 books 的裸数组不同。
 */
export function listWords(params: ListWordsParams): Promise<PageResult<Word>> {
  const query = new URLSearchParams();
  if (params.bookId !== undefined) query.set('bookId', String(params.bookId));
  if (params.keyword) query.set('keyword', params.keyword);
  query.set('page', String(params.page ?? 1));
  query.set('size', String(params.size ?? 20));
  return get<PageResult<Word>>(`/api/admin/words?${query.toString()}`);
}

export function createWord(payload: WordUpsertRequest): Promise<Word> {
  return post<Word>('/api/admin/words', payload);
}

export function updateWord(wordId: number, payload: WordUpsertRequest): Promise<Word> {
  return put<Word>(`/api/admin/words/${wordId}`, payload);
}

export function deleteWord(wordId: number): Promise<WordDeleteResult> {
  return del<WordDeleteResult>(`/api/admin/words/${wordId}`);
}
