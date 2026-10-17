import { useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Typography,
  App as AntdApp,
} from 'antd';
import { ArrowLeftOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useWords } from '../../hooks/useWords';
import { createWord, deleteWord, updateWord } from '../../services/word';
import { dash } from '../../utils/format';
import { ApiError } from '../../utils/request';
import type { AdminBook } from '../../types/book';
import type { Word, WordUpsertRequest } from '../../types/word';

interface Props {
  book: AdminBook;
  open: boolean;
  onClose: () => void;
  /** 写操作成功后回调父页刷新词书列表（wordCount 会变）。 */
  onChanged: () => void;
}

type ViewMode = 'list' | 'edit';

/**
 * 单词管理弹窗。
 *
 * 列表与编辑表单**在同一弹窗内切换视图**，不做双层 Modal：
 * AntD 嵌套 Modal 的层级与焦点管理容易出问题，也少一次动画。
 */
export default function WordModal({ book, open, onClose, onChanged }: Props) {
  const { message } = AntdApp.useApp();
  const [mode, setMode] = useState<ViewMode>('list');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [editing, setEditing] = useState<Word | null>(null);
  const [saving, setSaving] = useState(false);
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [form] = Form.useForm<WordUpsertRequest>();

  const { words, total, loading, error, reload } = useWords({
    bookId: book.bookId,
    page,
    size,
    keyword,
  });

  // 关闭/换书时重置，避免下次打开看到上次的搜索与页码
  useEffect(() => {
    if (!open) {
      setMode('list');
      setPage(1);
      setKeyword('');
      setSearchInput('');
      setEditing(null);
    }
  }, [open]);

  const onSearch = (value: string) => {
    setKeyword(value.trim());
    setPage(1); // 必须重置页码，否则停在第 5 页会看到空表
  };

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    setMode('edit');
  };

  const openEdit = (word: Word) => {
    setEditing(word);
    form.setFieldsValue({
      word: word.word,
      phonetic: word.phonetic ?? '',
      translation: word.translation ?? '',
      example: word.example ?? '',
    });
    setMode('edit');
  };

  const backToList = () => {
    setMode('list');
    setEditing(null);
    form.resetFields();
  };

  const onSave = async () => {
    let values: WordUpsertRequest;
    try {
      values = await form.validateFields();
    } catch {
      return;
    }

    setSaving(true);
    try {
      const payload: WordUpsertRequest = {
        bookId: book.bookId,
        word: (values.word ?? '').trim(),
        phonetic: (values.phonetic ?? '').trim(),
        translation: (values.translation ?? '').trim(),
        example: (values.example ?? '').trim(),
      };

      if (editing) {
        await updateWord(editing.wordId, payload);
        message.success('修改成功');
      } else {
        await createWord(payload);
        message.success('新增成功');
      }
      reload();
      onChanged(); // wordCount 变了，父页要刷新
      backToList();
    } catch (err) {
      message.error(err instanceof ApiError ? err.message : '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const onDelete = async (wordId: number) => {
    setDeletingId(wordId);
    try {
      await deleteWord(wordId);
      message.success('已删除');
      // 删掉当页最后一条时回退一页，否则会看到空表
      if (words.length === 1 && page > 1) setPage(page - 1);
      else reload();
      onChanged();
    } catch (err) {
      message.error(err instanceof ApiError ? err.message : '删除失败');
    } finally {
      setDeletingId(null);
    }
  };

  const columns: ColumnsType<Word> = [
    {
      title: '单词',
      dataIndex: 'word',
      width: 150,
      render: (w: string) => <Typography.Text strong>{w}</Typography.Text>,
    },
    { title: '音标', dataIndex: 'phonetic', width: 150, render: (v: string | null) => dash(v) },
    { title: '释义', dataIndex: 'translation', ellipsis: true, render: (v: string | null) => dash(v) },
    { title: '例句', dataIndex: 'example', ellipsis: true, render: (v: string | null) => dash(v) },
    {
      title: '操作',
      key: 'action',
      width: 140,
      render: (_: unknown, record: Word) => (
        <Space size="small">
          <Button size="small" onClick={() => openEdit(record)}>
            编辑
          </Button>
          <Popconfirm
            title="确认删除该单词？"
            okText="删除"
            okButtonProps={{ danger: true }}
            cancelText="取消"
            onConfirm={() => onDelete(record.wordId)}
          >
            <Button
              size="small"
              danger
              icon={<DeleteOutlined />}
              loading={deletingId === record.wordId}
            />
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const title =
    mode === 'list' ? `单词管理 · ${book.name}` : editing ? '编辑单词' : '新增单词';

  return (
    <Modal
      open={open}
      title={title}
      width={920}
      onCancel={onClose}
      maskClosable={false}
      footer={
        mode === 'edit'
          ? [
              <Button key="back" onClick={backToList}>
                返回列表
              </Button>,
              <Button key="save" type="primary" loading={saving} onClick={onSave}>
                保存
              </Button>,
            ]
          : [
              <Button key="close" onClick={onClose}>
                关闭
              </Button>,
            ]
      }
    >
      {mode === 'list' && (
        <>
          <Space style={{ marginBottom: 16 }}>
            <Input.Search
              placeholder="搜索单词"
              allowClear
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              onSearch={onSearch}
              style={{ width: 280 }}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新增单词
            </Button>
          </Space>

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

          <Table<Word>
            rowKey="wordId"
            columns={columns}
            dataSource={words}
            loading={loading}
            size="small"
            pagination={{
              current: page,
              pageSize: size,
              total,
              showSizeChanger: true,
              pageSizeOptions: [10, 20, 50],
              showTotal: (n) => `共 ${n.toLocaleString()} 个单词`,
              onChange: (nextPage, nextSize) => {
                setPage(nextPage);
                setSize(nextSize);
              },
            }}
            locale={{ emptyText: loading ? '加载中…' : '暂无单词' }}
          />
        </>
      )}

      {mode === 'edit' && (
        <>
          <Button
            type="link"
            icon={<ArrowLeftOutlined />}
            onClick={backToList}
            style={{ paddingLeft: 0, marginBottom: 8 }}
          >
            返回列表
          </Button>
          <Form form={form} layout="vertical" requiredMark={false}>
            <Form.Item
              name="word"
              label="单词"
              rules={[
                { required: true, message: '请输入单词' },
                { max: 64, message: '单词最长 64 字符' },
              ]}
            >
              <Input placeholder="例如：abandon" autoFocus />
            </Form.Item>
            <Form.Item name="phonetic" label="音标" rules={[{ max: 128 }]}>
              <Input placeholder="例如：ə'bændən" />
            </Form.Item>
            <Form.Item name="translation" label="释义" rules={[{ max: 512 }]}>
              <Input.TextArea rows={2} placeholder="例如：v. 放弃，抛弃" />
            </Form.Item>
            <Form.Item name="example" label="例句" rules={[{ max: 512 }]}>
              <Input.TextArea rows={2} placeholder="例如：He abandoned his car." />
            </Form.Item>
          </Form>
        </>
      )}
    </Modal>
  );
}
