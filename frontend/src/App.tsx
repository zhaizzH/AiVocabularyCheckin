import { App as AntdApp } from 'antd';
import {
  createBrowserRouter,
  Navigate,
  RouterProvider,
} from 'react-router-dom';
import AdminLayout from './components/AdminLayout';
import RequireAdmin from './components/RequireAdmin';
import LoginPage from './pages/Login';
import WordBankPage from './pages/WordBank';
import UserMgrPage from './pages/UserMgr';
import MonitorPage from './pages/Monitor';

const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAdmin>
        <AdminLayout />
      </RequireAdmin>
    ),
    children: [
      { index: true, element: <Navigate to="/wordbank" replace /> },
      { path: 'wordbank', element: <WordBankPage /> },
      { path: 'users', element: <UserMgrPage /> },
      { path: 'monitor', element: <MonitorPage /> },
    ],
  },
  { path: '*', element: <Navigate to="/" replace /> },
]);

/**
 * AntdApp 提供 message/modal 的 context 版本。
 *
 * 必须用它而不是静态的 `import { message } from 'antd'`：静态方法拿不到
 * ConfigProvider 的主题与 locale，且 React 18 并发模式下会告警。
 */
export default function App() {
  return (
    <AntdApp>
      <RouterProvider router={router} />
    </AntdApp>
  );
}
