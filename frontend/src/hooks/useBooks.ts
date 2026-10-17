import { useCallback, useEffect, useState } from 'react';
import type { AdminBook } from '../types/book';
import { listBooks } from '../services/book';
import { ApiError } from '../utils/request';

export interface UseBooksResult {
  books: AdminBook[];
  loading: boolean;
  error: string | null;
  reload: () => void;
}

/** 词书列表。服务端数据按页面请求，不放进全局状态（spec 约束）。 */
export function useBooks(): UseBooksResult {
  const [books, setBooks] = useState<AdminBook[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    listBooks()
      .then((data) => {
        // 组件已卸载或已被下一次请求取代时丢弃结果，避免竞态覆盖
        if (cancelled) return;
        setBooks(data ?? []);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof ApiError ? err.message : '加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [tick]);

  return { books, loading, error, reload };
}
