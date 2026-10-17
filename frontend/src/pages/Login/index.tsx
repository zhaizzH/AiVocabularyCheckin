import { useState } from 'react';
import { Button, Card, Form, Input, Typography, App as AntdApp } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { login } from '../../services/auth';
import { useAuth } from '../../hooks/useAuth';
import { ApiError } from '../../utils/request';
import type { LoginRequest } from '../../types/auth';

/**
 * 管理员登录。
 *
 * 与小程序共用 /api/client/auth/login，靠返回的 userInfo.role 区分。
 * 非 ADMIN **不落 token**：存了等于给了半个登录态，后续每个请求都 403，
 * 用户会看到「登录成功但什么都打不开」，比直接拒绝更糟。
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const { signIn } = useAuth();
  const { message } = AntdApp.useApp();
  const [submitting, setSubmitting] = useState(false);

  const onFinish = async (values: LoginRequest) => {
    setSubmitting(true);
    try {
      const res = await login({
        username: values.username.trim(),
        password: values.password,
      });

      if (res.userInfo.role !== 'ADMIN') {
        message.error('该账号不是管理员，请使用管理员账号登录');
        return;
      }

      signIn(res.token, res.userInfo);
      message.success('登录成功');
      navigate('/', { replace: true });
    } catch (err) {
      // 覆盖 1002 密码错 / 1003 已禁用 / 网络异常
      message.error(err instanceof ApiError ? err.message : '登录失败，请稍后重试');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#001529',
      }}
    >
      <Card style={{ width: 380 }} styles={{ body: { padding: 32 } }}>
        <div style={{ textAlign: 'center', marginBottom: 28 }}>
          <Typography.Title level={4} style={{ marginBottom: 4 }}>
            AI 单词学习打卡
          </Typography.Title>
          <Typography.Text type="secondary">管理后台</Typography.Text>
        </div>

        <Form<LoginRequest>
          layout="vertical"
          onFinish={onFinish}
          requiredMark={false}
          initialValues={{ username: '', password: '' }}
        >
          <Form.Item
            name="username"
            label="用户名"
            rules={[{ required: true, message: '请输入用户名' }]}
          >
            <Input
              size="large"
              prefix={<UserOutlined />}
              placeholder="管理员用户名"
              autoComplete="username"
              autoFocus
            />
          </Form.Item>

          <Form.Item
            name="password"
            label="密码"
            rules={[{ required: true, message: '请输入密码' }]}
          >
            <Input.Password
              size="large"
              prefix={<LockOutlined />}
              placeholder="密码"
              autoComplete="current-password"
            />
          </Form.Item>

          <Form.Item style={{ marginBottom: 0, marginTop: 8 }}>
            <Button
              type="primary"
              htmlType="submit"
              size="large"
              block
              loading={submitting}
            >
              登 录
            </Button>
          </Form.Item>
        </Form>
      </Card>
    </div>
  );
}
