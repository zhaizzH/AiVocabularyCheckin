import { useState } from 'react';
import {
  Alert,
  Button,
  Card,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
  App as AntdApp,
} from 'antd';
import { DeleteOutlined, ImportOutlined, UnorderedListOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useBooks } from '../../hooks/useBooks';
import { deleteBook } from '../../services/book';
import { categoryColor, rawTime } from '../../utils/format';
import { ApiError } from '../../utils/request';
import type { AdminBook } from '../../types/book';
import JsonlImportModal from '../../components/JsonlImportModal';
import WordModal from './WordModal';

export default function WordBankPage() {
  const { message } = AntdApp.useApp();
  const { books, loading, error, reload } = useBooks();
  const [importOpen, setImportOpen] = useState(false);
  const [activeBook, setActiveBook] = useState<AdminBook | null>(null);
  const [deletingId, setDeletingId] = useState<number | null>(null);

  const onDelete = async (bookId: number) => {
    setDeletingId(bookId);
    try {
      await deleteBook(bookId);
      message.success('词书已删除');
      reload();
    } catch (err) {
      message.error(err instanceof ApiError ? err.message : '删除失败');
    } finally {
      setDeletingId(null);
    }
  };

  const columns: ColumnsType<AdminBook> = [
    {
      title: '词书名称',
      dataIndex: 'name',
      render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
    },
    {
      title: '分类',
      dataIndex: 'category',
      width: 120,
      render: (category: string) => <Tag color={categoryColor(category)}>{category}</Tag>,
    },
    {
      title: '单词数',
      dataIndex: 'wordCount',
      width: 120,
      align: 'right',
      render: (count: number) => <strong>{count.toLocaleString()}</strong>,
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      width: 180,
      render: (t: string) => rawTime(t),
    },
    {
      title: '操作',
      key: 'action',
      width: 200,
      render: (_: unknown, record: AdminBook) => (
        <Space size="small">
          <Button
            size="small"
            icon={<UnorderedListOutlined />}
            onClick={() => setActiveBook(record)}
          >
            查看单词
          </Button>
          <Popconfirm
            title="确认删除该词书？"
            description="会连带删除该书下所有单词，不可恢复。"
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => onDelete(record.bookId)}
          >
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              loading={deletingId === record.bookId}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <Card
      title="词库管理"
      extra={
        <Button
          type="primary"
          icon={<ImportOutlined />}
          onClick={() => setImportOpen(true)}
        >
          导入词库（JSONL）
        </Button>
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

      <Table<AdminBook>
        rowKey="bookId"
        columns={columns}
        dataSource={books}
        loading={loading}
        pagination={false}
        locale={{ emptyText: loading ? '加载中…' : '暂无词书，请先导入' }}
      />

      <Typography.Text type="secondary" style={{ display: 'block', marginTop: 12 }}>
        共 {books.length} 本词书，合计{' '}
        {books.reduce((sum, b) => sum + b.wordCount, 0).toLocaleString()} 个单词
      </Typography.Text>

      <JsonlImportModal
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={reload}
      />

      {activeBook && (
        <WordModal
          book={activeBook}
          open
          onClose={() => setActiveBook(null)}
          /* 单词增删后 wordCount 会变，必须刷新父页列表 */
          onChanged={reload}
        />
      )}
    </Card>
  );
}
