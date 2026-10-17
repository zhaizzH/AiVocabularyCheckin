/** 角色。联合字面量而非 string —— 后端改枚举时 TS 会报错提醒。 */
export type UserRole = 'USER' | 'ADMIN';

/** 登录响应里的 userInfo（GET 无此接口，只随登录返回）。 */
export interface UserInfo {
  userId: number;
  username: string;
  nickname: string;
  avatar: string | null;
  role: UserRole;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  userInfo: UserInfo;
}
