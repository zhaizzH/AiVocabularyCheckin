import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Col,
  Row,
  Space,
  Table,
  Tag,
  Typography,
  App as AntdApp,
} from 'antd';
import {
  ApiOutlined,
  CheckCircleOutlined,
  TeamOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import StatCard from '../../components/StatCard';
import { getAiStats } from '../../services/monitor';
import { featureLabel, rawTime, reasonColor } from '../../utils/format';
import { ApiError } from '../../utils/request';
import type { AiFeatureStat, AiStats, DegradeEvent } from '../../types/monitor';

export default function MonitorPage() {
  const { message } = AntdApp.useApp();
  const [stats, setStats] = useState<AiStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await getAiStats();
      setStats(data ?? null);
    } catch (err: unknown) {
      setStats(null);
      setError(err instanceof ApiError ? err.message : '加载失败');
      message.error(err instanceof ApiError ? err.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [message]);

  useEffect(() => {
    void load();
    // 只在挂载时拉一次；要刷新点右上角按钮
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const featureColumns: ColumnsType<AiFeatureStat> = [
    {
      title: '功能',
      dataIndex: 'feature',
      width: 140,
      render: (f: string) => featureLabel(f),
    },
    { title: '调用次数', dataIndex: 'total', align: 'right', width: 110 },
    { title: '成功', dataIndex: 'success', align: 'right', width: 90 },
    {
      title: '降级',
      dataIndex: 'degraded',
      align: 'right',
      width: 90,
      render: (n: number) => (n > 0 ? <Tag color="orange">{n}</Tag> : n),
    },
    {
      title: '超时',
      dataIndex: 'timeout',
      align: 'right',
      width: 90,
      render: (n: number) => (n > 0 ? <Tag color="orange">{n}</Tag> : n),
    },
    {
      title: '错误',
      dataIndex: 'error',
      align: 'right',
      width: 90,
      render: (n: number) => (n > 0 ? <Tag color="red">{n}</Tag> : n),
    },
    {
      title: '平均耗时',
      dataIndex: 'avgCostMs',
      align: 'right',
      width: 120,
      render: (ms: number) => `${ms} ms`,
    },
  ];

  const eventColumns: ColumnsType<DegradeEvent> = [
    { title: '时间', dataIndex: 'time', width: 180, render: (t: string) => rawTime(t) },
    {
      title: '功能',
      dataIndex: 'feature',
      width: 120,
      render: (f: string) => featureLabel(f),
    },
    { title: '耗时', dataIndex: 'costMs', width: 100, align: 'right',
      render: (ms: number) => `${ms} ms` },
    {
      title: '原因',
      dataIndex: 'reason',
      width: 120,
      render: (r: string) => <Tag color={reasonColor(r)}>{r}</Tag>,
    },
    { title: '结果', dataIndex: 'result', ellipsis: true },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title="系统监控 · AI 调用"
        extra={
          <Button onClick={() => void load()} loading={loading}>
            刷新
          </Button>
        }
      >
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 16 }}
          message="数据为服务启动以来的累计值，重启后端后清零"
        />

        {error && (
          <Alert
            type="error"
            showIcon
            message={error}
            style={{ marginBottom: 16 }}
          />
        )}

        <Row gutter={[16, 16]} style={{ marginBottom: 24 }}>
          <Col xs={24} sm={12} lg={6}>
            <StatCard
              title="AI 调用总数"
              value={stats?.totalCalls ?? 0}
              icon={<ApiOutlined />}
              color="#4a6cf7"
            />
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <StatCard
              title="成功率"
              value={stats?.successRate ?? 100}
              suffix="%"
              precision={1}
              icon={<CheckCircleOutlined />}
              color="#52c41a"
            />
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <StatCard
              title="降级次数"
              value={stats?.degradedCount ?? 0}
              icon={<WarningOutlined />}
              color="#faad14"
            />
          </Col>
          <Col xs={24} sm={12} lg={6}>
            <StatCard
              title="注册用户数"
              value={stats?.registeredUsers ?? 0}
              icon={<TeamOutlined />}
              color="#722ed1"
            />
          </Col>
        </Row>

        <Typography.Title level={5} style={{ marginTop: 0 }}>
          按功能拆分
        </Typography.Title>
        <Table<AiFeatureStat>
          rowKey="feature"
          size="small"
          columns={featureColumns}
          dataSource={stats?.features ?? []}
          loading={loading}
          pagination={false}
          style={{ marginBottom: 24 }}
          locale={{ emptyText: loading ? '加载中…' : '暂无数据' }}
        />

        <Typography.Title level={5} style={{ marginTop: 0 }}>
          降级事件（最新在前）
        </Typography.Title>
        <Table<DegradeEvent>
          rowKey={(r) => `${r.time}-${r.feature}-${r.reason}-${r.costMs}`}
          size="small"
          columns={eventColumns}
          dataSource={stats?.recentEvents ?? []}
          loading={loading}
          pagination={{ pageSize: 10, showTotal: (n) => `共 ${n} 条` }}
          locale={{ emptyText: loading ? '加载中…' : '暂无降级事件' }}
        />
      </Card>
    </Space>
  );
}
