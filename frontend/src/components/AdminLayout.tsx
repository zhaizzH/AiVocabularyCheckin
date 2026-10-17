import { Layout, Menu, Dropdown, Avatar, Typography, App as AntdApp } from 'antd';
import {
  BookOutlined,
  TeamOutlined,
  MonitorOutlined,
  LogoutOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';

const { Sider, Header, Content } = Layout;

const MENU_ITEMS = [
  { key: '/wordbank', icon: <BookOutlined />, label: '词库管理' },
  { key: '/users', icon: <TeamOutlined />, label: '用户管理' },
  { key: '/monitor', icon: <MonitorOutlined />, label: '系统监控' },
];

/** 侧边栏 + 顶栏框架。原型配色：Sider #001529，选中项 #4a6cf7。 */
export default function AdminLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { userInfo, signOut } = useAuth();
  const { message } = AntdApp.useApp();

  const onMenuClick = ({ key }: { key: string }) => {
    navigate(key);
  };

  const onLogout = () => {
    signOut();
    message.success('已退出登录');
    navigate('/login', { replace: true });
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={200} theme="dark" style={{ background: '#001529' }}>
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontWeight: 600,
            fontSize: 15,
            letterSpacing: 1,
          }}
        >
          AI 单词 · 管理后台
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[location.pathname]}
          items={MENU_ITEMS}
          onClick={onMenuClick}
          style={{ background: '#001529' }}
        />
      </Sider>

      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            height: 56,
            lineHeight: '56px',
            borderBottom: '1px solid #f0f0f0',
          }}
        >
          <Dropdown
            menu={{
              items: [
                { key: 'logout', icon: <LogoutOutlined />, label: '退出登录',
                  onClick: onLogout },
              ],
            }}
          >
            <div style={{ cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 8 }}>
              <Avatar size="small" icon={<UserOutlined />} />
              <Typography.Text>{userInfo?.nickname || userInfo?.username || '管理员'}</Typography.Text>
            </div>
          </Dropdown>
        </Header>

        <Content style={{ margin: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
