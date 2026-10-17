/** 词书分类。与后端 Constants.BOOK_CATEGORIES 一致。 */
export type BookCategory = 'CET4' | 'CET6' | 'KAOYAN';

/**
 * 管理端词书（GET /api/admin/books 的裸数组成员）。
 *
 * 注意与用户端 GET /api/client/books 的区别：管理端**没有** selected /
 * learnedCount（无用户维度），但**有** createTime。
 */
export interface AdminBook {
  bookId: number;
  name: string;
  category: string;
  wordCount: number;
  description: string | null;
  /** 字符串 "yyyy-MM-dd HH:mm:ss"，不是时间戳。 */
  createTime: string;
}

/** POST /api/admin/books/import 的响应 data。 */
export interface ImportResult {
  bookId: number;
  success: number;
  skipped: number;
}

/** 删除词书/单词的统一响应形状。 */
export interface DeleteResult {
  deleted: boolean;
}

export interface BookDeleteResult extends DeleteResult {
  bookId: number;
}
