import type { UserRole } from './auth';

/**
 * 管理端用户（GET /api/admin/users 分页元素）。
 *
 * `status` 实测是**数字** 0/1，不是字符串也不是布尔。
 * `totalCheckInDays` 由后端算好，前端不重算。
 */
export interface AdminUser {
  userId: number;
  username: string;
  nickname: string;
  role: UserRole;
  status: 0 | 1;
  totalCheckInDays: number;
  createTime: string;
}

export interface UserStatusRequest {
  /** 0 禁用 / 1 启用 */
  status: 0 | 1;
}

export interface UserStatusResult {
  userId: number;
  status: 0 | 1;
}
