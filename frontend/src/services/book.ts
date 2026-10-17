import type { AdminBook, BookCategory, BookDeleteResult, ImportResult } from '../types/book';
import { del, get, postForm } from '../utils/request';

/** 词书列表。注意返回的是**裸数组**，不是分页包装（契约 §8.8）。 */
export function listBooks(): Promise<AdminBook[]> {
  return get<AdminBook[]>('/api/admin/books');
}

/** 删除词书（逻辑删除，连带该书全部单词）。 */
export function deleteBook(bookId: number): Promise<BookDeleteResult> {
  return del<BookDeleteResult>(`/api/admin/books/${bookId}`);
}

/** JSONL 导入。multipart 三字段：file / name / category。 */
export function importBook(
  file: File,
  name: string,
  category: BookCategory,
): Promise<ImportResult> {
  const form = new FormData();
  form.append('file', file);
  form.append('name', name);
  form.append('category', category);
  return postForm<ImportResult>('/api/admin/books/import', form);
}
