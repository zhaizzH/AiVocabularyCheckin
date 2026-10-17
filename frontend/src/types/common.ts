/**
 * 后端统一响应包装。
 *
 * `data` 必须允许 null：实测失败响应（如 2004 导入格式错误、1005 校验失败）
 * 的 `data` 就是 null。写成非空会让调用方误以为可以无脑取属性。
 *
 * `code` 是**字符串**（成功为 "0"），不要用数字比较。
 */
export interface Result<T> {
  code: string;
  message: string;
  data: T | null;
}

/** 管理端分页包装：GET /api/admin/words、/api/admin/users。 */
export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
}
