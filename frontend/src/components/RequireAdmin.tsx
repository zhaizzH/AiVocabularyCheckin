import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

/**
 * 路由守卫：管理端所有页面都要求「已登录 + role === 'ADMIN'」。
 *
 * token 从 localStorage 读（不是只在登录时 setState），所以刷新页面不会丢登录态。
 * 非管理员直接送回登录页——管理端没有任何对普通用户有意义的内容。
 */
export default function RequireAdmin({ children }: { children: ReactNode }) {
  const { isAdmin } = useAuth();
  const location = useLocation();

  if (!isAdmin) {
    // state 让登录页可以提示原因（例如「无管理员权限」）
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
