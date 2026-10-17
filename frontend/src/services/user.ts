import type { PageResult } from '../types/common';
import type { AdminUser, UserStatusResult } from '../types/user';
import { get, put } from '../utils/request';

export interface ListUsersParams {
  keyword?: string;
  page?: number;
  size?: number;
}

/**
 * 用户分页查询。
 *
 * 注意：后端忽略 `status` 参数（实测传 ?status=0 仍返回 status=1 的用户），
 * 所以这里刻意不提供状态筛选参数——避免做出点了没反应的假功能。
 */
export function listUsers(params: ListUsersParams): Promise<PageResult<AdminUser>> {
  const query = new URLSearchParams();
  if (params.keyword) query.set('keyword', params.keyword);
  query.set('page', String(params.page ?? 1));
  query.set('size', String(params.size ?? 20));
  return get<PageResult<AdminUser>>(`/api/admin/users?${query.toString()}`);
}

/** 禁用(0)/启用(1)。禁用后该用户下次携带旧 token 请求会得到 401 + 1003。 */
export function updateUserStatus(userId: number, status: 0 | 1): Promise<UserStatusResult> {
  return put<UserStatusResult>(`/api/admin/users/${userId}/status`, { status });
}
