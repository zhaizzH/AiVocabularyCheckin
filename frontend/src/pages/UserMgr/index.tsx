import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Input,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
  App as AntdApp,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useUsers } from '../../hooks/useUsers';
import { updateUserStatus } from '../../services/user';
import { rawTime } from '../../utils/format';
import { ApiError } from '../../utils/request';
import type { AdminUser } from '../../types/user';

export default function UserMgrPage() {
  const { message } = AntdApp.useApp();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [pendingId, setPendingId] = useState<number | null>(null);

  const { users, total, loading, error, reload } = useUsers({ page, size, keyword });

  const onSearch = (value: string) => {
    setKeyword(value.trim());
    setPage(1); // 搜索必须重置页码
  };

  const onToggleStatus = async (user: AdminUser) => {
    const next = user.status === 1 ? 0 : 1;
    setPendingId(user.userId);
    try {
      await updateUserStatus(user.userId, next);
      message.success(next === 0 ? '已禁用该用户' : '已启用该用户');
      reload();
    } catch (err: unknown) {
      message.error(err instanceof ApiError ? err.message : '操作失败');
    } finally {
      setPendingId(null);
    }
  };

  const columns: ColumnsType<AdminUser> = [
    { title: 'ID', dataIndex: 'userId', width: 90 },
    {
      title: '用户名',
      dataIndex: 'username',
      width: 180,
      render: (name: string, record) => (
        <Space size={4}>
          <Typography.Text strong>{name}</Typography.Text>
          {record.role === 'ADMIN' && <Tag color="gold">管理员</Tag>}
        </Space>
      ),
    },
    {
      title: '昵称',
      dataIndex: 'nickname',
      width: 150,
      render: (n: string) => n || '—',
    },
    {
      title: '累计打卡',
      dataIndex: 'totalCheckInDays',
      width: 110,
      align: 'right',
      render: (days: number) => `${days} 天`,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (status: 0 | 1) =>
        status === 1 ? <Tag color="green">正常</Tag> : <Tag color="red">已禁用</Tag>,
    },
    {
      title: '注册时间',
      dataIndex: 'createTime',
      width: 180,
      render: (t: string) => rawTime(t),
    },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_: unknown, record: AdminUser) => {
        // 后端对 ADMIN 返 1005「不允许修改管理员账号状态」；
        // 前端 disabled 是主动预防，避免用户点了只收到一个错误 toast。
        if (record.role === 'ADMIN') {
          return (
            <Tooltip title="管理员账号不可禁用">
              <Button size="small" disabled>
                禁用
              </Button>
            </Tooltip>
          );
        }

        const isDisabling = record.status === 1;
        return (
          <Popconfirm
            title={isDisabling ? '确认禁用该用户？' : '确认启用该用户？'}
            description={
              isDisabling
                ? '禁用后该用户将立即无法访问小程序，再次登录会提示账号已禁用。'
                : '启用后该用户可以正常登录使用。'
            }
            okText={isDisabling ? '禁用' : '启用'}
            okButtonProps={{ danger: isDisabling }}
            cancelText="取消"
            onConfirm={() => onToggleStatus(record)}
          >
            <Button
              size="small"
              danger={isDisabling}
              loading={pendingId === record.userId}
            >
              {isDisabling ? '禁用' : '启用'}
            </Button>
          </Popconfirm>
        );
      },
    },
  ];

  return (
    <Card
      title="用户管理"
      extra={
        <Input.Search
          placeholder="搜索用户名"
          allowClear
          value={searchInput}
          onChange={(e) => setSearchInput(e.target.value)}
          onSearch={onSearch}
          style={{ width: 260 }}
        />
      }
    >
      {error && (
        <Alert
          type="error"
          showIcon
          message={error}
          style={{ marginBottom: 16 }}
          action={
            <Button size="small" onClick={reload}>
              重试
            </Button>
          }
        />
      )}

      <Table<AdminUser>
        rowKey="userId"
        columns={columns}
        dataSource={users}
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          pageSizeOptions: [10, 20, 50],
          showTotal: (n) => `共 ${n.toLocaleString()} 个用户`,
          onChange: (nextPage, nextSize) => {
            setPage(nextPage);
            setSize(nextSize);
          },
        }}
        locale={{ emptyText: loading ? '加载中…' : '暂无用户' }}
      />
    </Card>
  );
}
