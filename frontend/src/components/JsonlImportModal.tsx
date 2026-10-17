import { useState } from 'react';
import {
  Alert,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Table,
  Typography,
  Upload,
  App as AntdApp,
} from 'antd';
import { InboxOutlined } from '@ant-design/icons';
import type { UploadFile } from 'antd';
import { importBook } from '../services/book';
import { CATEGORY_OPTIONS } from '../utils/format';
import { ApiError } from '../utils/request';
import type { BookCategory, ImportResult } from '../types/book';

const PREVIEW_LIMIT = 20;

interface ParsedRow {
  key: number;
  word: string;
  phonetic: string;
  translation: string;
  example: string;
}

/** 与后端 WordImportServiceImpl.parseLine 完全一致的映射规则。 */

/** JSON 对象类型守卫。用守卫而非 `as` 强转——强转会绕过类型检查。 */
type JsonObject = Record<string, unknown>;

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 安全取字符串字段：非字符串/缺失一律返回空串（与后端 text() 一致）。 */
function str(obj: JsonObject, key: string): string {
  const value = obj[key];
  return typeof value === 'string' ? value : '';
}

function parseLine(raw: string, index: number): ParsedRow | null {
  const line = raw.trim();
  if (!line) return null;

  let node: unknown;
  try {
    node = JSON.parse(line);
  } catch {
    // 坏行只跳过不抛错——后端也是这么做的（契约 §8.1）
    return null;
  }
  if (!isObject(node)) return null;

  const word = str(node, 'word').trim();
  if (!word) return null; // word 唯一必填

  const phonetic = str(node, 'uk');

  // translations: [{type, translation}] → "type. translation；type. translation"
  let translation = '';
  const rawTranslations = node.translations;
  if (Array.isArray(rawTranslations)) {
    const parts: string[] = [];
    for (const item of rawTranslations) {
      if (!isObject(item)) continue;
      const text = str(item, 'translation').trim();
      if (!text) continue;
      const type = str(item, 'type').trim();
      parts.push(type ? `${type}. ${text}` : text);
    }
    translation = parts.join('；');
  }

  // sentences[0].sentence → example
  let example = '';
  const rawSentences = node.sentences;
  if (Array.isArray(rawSentences) && rawSentences.length > 0) {
    const first = rawSentences[0];
    if (isObject(first)) example = str(first, 'sentence').trim();
  }

  return { key: index, word, phonetic, translation, example };
}

interface Props {
  open: boolean;
  onClose: () => void;
  /** 导入成功后回调父页面刷新词书列表（wordCount 变了）。 */
  onImported: () => void;
}

export default function JsonlImportModal({ open, onClose, onImported }: Props) {
  const { message } = AntdApp.useApp();
  const [file, setFile] = useState<File | null>(null);
  const [rows, setRows] = useState<ParsedRow[]>([]);
  const [totalLines, setTotalLines] = useState(0);
  const [badLines, setBadLines] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState<ImportResult | null>(null);
  const [form] = Form.useForm<{ name: string; category: BookCategory }>();

  const reset = () => {
    setFile(null);
    setRows([]);
    setTotalLines(0);
    setBadLines(0);
    setResult(null);
    form.resetFields();
  };

  const handleClose = () => {
    reset();
    onClose();
  };

  /** beforeUpload 返回 false → 阻止自动上传，改成前端读取预览。 */
  const handleBeforeUpload = async (f: UploadFile & File) => {
    setResult(null);
    let text: string;
    try {
      text = await f.text();
    } catch {
      message.error('文件读取失败，请重试');
      return false;
    }

    const lines = text.split('\n');
    const parsed: ParsedRow[] = [];
    let bad = 0;
    lines.forEach((line, i) => {
      if (!line.trim()) return;
      const row = parseLine(line, i);
      if (row) parsed.push(row);
      else bad += 1;
    });

    setFile(f);
    setRows(parsed);
    setTotalLines(lines.filter((l) => l.trim()).length);
    setBadLines(bad);
    return false;
  };

  const onSubmit = async () => {
    if (!file) {
      message.warning('请先选择 JSONL 文件');
      return;
    }
    let values: { name: string; category: BookCategory };
    try {
      values = await form.validateFields();
    } catch {
      return; // 校验失败，AntD 已在字段上提示
    }

    setSubmitting(true);
    try {
      const res = await importBook(file, values.name.trim(), values.category);
      setResult(res);
      message.success('导入完成');
      onImported();
    } catch (err: unknown) {
      message.error(err instanceof ApiError ? err.message : '导入失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      open={open}
      title="导入词库（JSONL）"
      width={920}
      onCancel={handleClose}
      maskClosable={false}
      footer={
        result
          ? [
              <Button key="close" type="primary" onClick={handleClose}>
                完成
              </Button>,
            ]
          : [
              <Button key="cancel" onClick={handleClose}>
                取消
              </Button>,
              <Button
                key="submit"
                type="primary"
                loading={submitting}
                disabled={!file || rows.length === 0}
                onClick={onSubmit}
              >
                确认导入
              </Button>,
            ]
      }
    >
      {!result && (
        <>
          <Upload.Dragger
            accept=".jsonl"
            maxCount={1}
            showUploadList={false}
            beforeUpload={handleBeforeUpload}
            style={{ marginBottom: 16 }}
          >
            <p className="ant-upload-drag-icon">
              <InboxOutlined />
            </p>
            <p className="ant-upload-text">点击或拖拽 JSONL 文件到此处</p>
            <p className="ant-upload-hint">
              每行一个 JSON 对象，必需字段 <code>word</code>；可选{' '}
              <code>uk</code>（音标）、<code>translations</code>、<code>sentences</code>
            </p>
          </Upload.Dragger>

          {file && (
            <Alert
              type={rows.length === 0 ? 'error' : badLines > 0 ? 'warning' : 'success'}
              showIcon
              style={{ marginBottom: 16 }}
              message={`共 ${totalLines} 行，可解析 ${rows.length} 行，跳过 ${badLines} 行`}
              description={
                rows.length === 0
                  ? '没有可解析的行，后端会返回 2004 导入文件格式错误。'
                  : `预览前 ${Math.min(PREVIEW_LIMIT, rows.length)} 行。坏行不会中断导入，只会计入「跳过」。`
              }
            />
          )}

          {rows.length > 0 && (
            <Table<ParsedRow>
              size="small"
              rowKey="key"
              dataSource={rows.slice(0, PREVIEW_LIMIT)}
              pagination={false}
              scroll={{ y: 220 }}
              style={{ marginBottom: 16 }}
              columns={[
                { title: '单词', dataIndex: 'word', width: 140 },
                { title: '音标', dataIndex: 'phonetic', width: 140 },
                { title: '释义', dataIndex: 'translation', ellipsis: true },
                { title: '例句', dataIndex: 'example', ellipsis: true },
              ]}
            />
          )}

          <Form form={form} layout="vertical" requiredMark={false}>
            <Form.Item
              name="name"
              label="词书名称"
              rules={[{ required: true, message: '请输入词书名称' }]}
            >
              <Input placeholder="例如：四级核心词汇" maxLength={50} />
            </Form.Item>
            <Form.Item
              name="category"
              label="分类"
              rules={[{ required: true, message: '请选择分类' }]}
              style={{ marginBottom: 0 }}
            >
              <Select
                placeholder="请选择分类"
                options={CATEGORY_OPTIONS.map((o: { value: BookCategory; label: string }) => ({
                  value: o.value,
                  label: o.label,
                }))}
              />
            </Form.Item>
          </Form>
        </>
      )}

      {result && (
        <Alert
          type="success"
          showIcon
          message="导入成功"
          description={
            <Typography.Text>
              成功导入 <strong>{result.success}</strong> 条，跳过{' '}
              <strong>{result.skipped}</strong> 条。词书 ID：
              {result.bookId}
            </Typography.Text>
          }
        />
      )}
    </Modal>
  );
}
