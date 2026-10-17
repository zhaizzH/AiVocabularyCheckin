import axios from 'axios';
import type { AxiosError } from 'axios';
import type { Result } from '../types/common';
import { clearAuth, getToken } from './auth';

/**
 * 全局唯一网络出口。
 *
 * 分层铁律（见 spec/frontend/directory-structure.md）：
 *   pages / components  →  services  →  utils/request  →  后端
 * 页面里禁止出现 fetch/axios/拼 URL/判 code，一律走 services。
 */

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://127.0.0.1:8080';

/** 业务/网络错误统一类型，页面只需读 `.message`。 */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly httpStatus: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

/**
 * 跳登录页。
 *
 * 用 location.replace 而非 router.navigate：request.ts 在 React 树之外拿不到
 * navigate；replace 还能防止用户按返回键回到已失效的页面。
 * 用 `!window.location.pathname.startsWith('/login')` 防重入——
 * 一个页面并发 3 个请求同时 401 时，只会跳转一次。
 */
function kickToLogin(): void {
  if (window.location.pathname.startsWith('/login')) return;
  window.location.replace('/login');
}

const http = axios.create({
  baseURL: BASE_URL,
  // 浏览器 fetch 默认无超时，不设会一直转圈。后端最慢的 AI 中转是 60s，
  // 但管理端只调普通接口，15s 足够。
  timeout: 15000,
});

http.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  // 不要在这里设 Content-Type：FormData 必须让浏览器自动带 boundary，
  // 手工设 multipart/form-data 会导致后端解析失败。
  return config;
});

/**
 * 统一处理业务错误码。
 *
 * 返回 null 表示「已处理并应当 reject」，调用方据此抛 ApiError。
 */
function handleBusinessCode(code: string, message: string, httpStatus: number): void {
  // 1006 token 失效 / 1003 账号被禁用 —— 登录态本身不可用了
  if (code === '1006' || code === '1003') {
    clearAuth();
    kickToLogin();
    throw new ApiError(code, '登录已过期，请重新登录', httpStatus);
  }
  // 1007 非管理员 —— 登录态是好的，只是没权限。
  // 刻意**不清 token**：清了会让「权限被撤」和「登录过期」无法区分，
  // 排查时看不到任何线索。
  if (code === '1007') {
    kickToLogin();
    throw new ApiError(code, '该账号无管理员权限', httpStatus);
  }
  throw new ApiError(code, message || '请求失败', httpStatus);
}

http.interceptors.response.use(
  (response) => {
    const body = response.data as Result<unknown>;
    if (body.code !== '0') {
      handleBusinessCode(body.code, body.message, response.status);
    }
    return body.data as unknown as typeof response;
  },
  (error: AxiosError<Result<unknown>>) => {
    // 超时
    if (error.code === 'ECONNABORTED' || axios.isCancel(error)) {
      throw new ApiError('CLIENT_TIMEOUT', '请求超时，请稍后重试', 0);
    }

    const status = error.response?.status ?? 0;
    const body = error.response?.data;

    // 关键：HTTP 401/403 走的是**这个**分支，不进上面的成功回调。
    // 后端在这两种状态码上仍然带 Result 包装（实测 401 → code 1006，
    // 403 → code 1007）。若只在成功分支判 code，401/403 永远不会触发跳登录。
    if (body && typeof body === 'object' && typeof body.code === 'string' && body.code !== '0') {
      handleBusinessCode(body.code, body.message, status);
    }

    // 兜底：401 但没有可解析的 body
    if (status === 401) {
      clearAuth();
      kickToLogin();
      throw new ApiError('1006', '登录已过期，请重新登录', 401);
    }

    throw new ApiError(
      'CLIENT_NETWORK',
      '网络异常，请确认后端服务已启动',
      status,
    );
  },
);

/**
 * 类型收口。
 *
 * axios 的响应拦截器在运行时把 AxiosResponse 换成了 body.data，但类型系统
 * 无法表达这一点，所以需要在这里断言。**这是全项目唯一允许 `as` 的地方**，
 * 静态检查脚本对 utils/request.ts 豁免。
 */
async function unwrap<T>(promise: Promise<unknown>): Promise<T> {
  return (await promise) as T;
}

export function get<T>(path: string): Promise<T> {
  return unwrap<T>(http.get(path));
}

export function post<T>(path: string, body?: unknown): Promise<T> {
  return unwrap<T>(http.post(path, body));
}

export function put<T>(path: string, body?: unknown): Promise<T> {
  return unwrap<T>(http.put(path, body));
}

export function del<T>(path: string): Promise<T> {
  return unwrap<T>(http.delete(path));
}

/** multipart 上传。绝不手工设 Content-Type（会丢 boundary）。 */
export function postForm<T>(path: string, form: FormData): Promise<T> {
  return unwrap<T>(http.post(path, form));
}
