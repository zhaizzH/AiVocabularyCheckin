import type { UserInfo } from '../types/auth';

const TOKEN_KEY = 'vocab_admin_token';
const USER_KEY = 'vocab_admin_user';

/**
 * 登录态变更事件。
 *
 * 不用 Context/Redux：跨组件共享的只有登录态一项。utils/auth 是唯一的
 * 写入口（登录 / 登出 / 401），写完广播一次事件，useAuth 监听即可。
 * 这样 request.ts（在 React 树之外）也能触发 UI 更新。
 */
export const AUTH_CHANGED_EVENT = 'vocab-admin-auth-changed';

function notify(): void {
  window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function getUserInfo(): UserInfo | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    // 用字段守卫而非 `as`：存储可能被外部篡改，强转等于放弃校验
    if (isUserInfo(parsed)) return parsed;
    return null;
  } catch {
    // 非 JSON 时不要炸整页，当作未登录
    return null;
  }
}

function isUserInfo(value: unknown): value is UserInfo {
  if (typeof value !== 'object' || value === null) return false;
  const o = Object.fromEntries(Object.entries(value));
  return (
    typeof o.userId === 'number' &&
    typeof o.username === 'string' &&
    typeof o.nickname === 'string' &&
    (o.role === 'ADMIN' || o.role === 'USER')
  );
}

export function setAuth(token: string, userInfo: UserInfo): void {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(userInfo));
  notify();
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
  notify();
}

/** 是否已登录且是管理员（管理端所有页面都要求这个）。 */
export function isAdmin(): boolean {
  return getToken() !== null && getUserInfo()?.role === 'ADMIN';
}
