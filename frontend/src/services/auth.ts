import type { LoginRequest, LoginResponse } from '../types/auth';
import { post } from '../utils/request';

/**
 * 管理员登录。
 *
 * 与小程序共用同一接口（契约 §1.2：由 role 区分），管理端只接受 ADMIN。
 * role 的判断交给调用方（登录页）——services 不做业务判断。
 */
export function login(payload: LoginRequest): Promise<LoginResponse> {
  return post<LoginResponse>('/api/client/auth/login', payload);
}
