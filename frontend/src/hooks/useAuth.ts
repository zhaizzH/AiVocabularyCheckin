import { useCallback, useMemo, useSyncExternalStore } from 'react';
import type { UserInfo } from '../types/auth';
import {
  AUTH_CHANGED_EVENT,
  clearAuth,
  getToken,
  getUserInfo,
  setAuth as setAuthRaw,
} from '../utils/auth';

/**
 * 登录态订阅。
 *
 * 关键：useSyncExternalStore 要求 getSnapshot 返回**引用稳定**的值，
 * 且在未变更时必须 `===` 上一次的返回值。getUserInfo() 每次 JSON.parse
 * 都产生新对象 → React 判定「一直在变」→ 无限重渲染
 * （实测报错：Maximum update depth exceeded，整个页面白屏）。
 *
 * 解法：快照返回 **localStorage 里的原始字符串**（字符串比较天然稳定），
 * 由 hook 内部 useMemo 解析成对象。
 */
function subscribe(callback: () => void): () => void {
  window.addEventListener(AUTH_CHANGED_EVENT, callback);
  window.addEventListener('storage', callback);
  return () => {
    window.removeEventListener(AUTH_CHANGED_EVENT, callback);
    window.removeEventListener('storage', callback);
  };
}

const USER_KEY = 'vocab_admin_user';

function getUserRaw(): string | null {
  return localStorage.getItem(USER_KEY);
}

export interface UseAuthResult {
  token: string | null;
  userInfo: UserInfo | null;
  isAdmin: boolean;
  signIn: (token: string, userInfo: UserInfo) => void;
  signOut: () => void;
}

export function useAuth(): UseAuthResult {
  const token = useSyncExternalStore(subscribe, getToken, () => null);
  // 字符串快照：引用稳定，不会触发无限重渲染
  const userRaw = useSyncExternalStore(subscribe, getUserRaw, () => null);

  // 按原始字符串缓存解析结果：只在 localStorage 真的变化时才产生新对象，
  // 下游 useEffect 的依赖数组因此不会因「每次渲染新对象」而反复触发。
  const userInfo = useMemo(
    () => (userRaw === null ? null : getUserInfo()),
    [userRaw],
  );

  const signIn = useCallback((nextToken: string, info: UserInfo) => {
    setAuthRaw(nextToken, info);
  }, []);

  const signOut = useCallback(() => {
    clearAuth();
  }, []);

  return {
    token,
    userInfo,
    isAdmin: token !== null && userInfo?.role === 'ADMIN',
    signIn,
    signOut,
  };
}
