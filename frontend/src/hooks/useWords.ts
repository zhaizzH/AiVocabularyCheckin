import { useCallback, useEffect, useState } from 'react';
import type { Word } from '../types/word';
import { listWords } from '../services/word';
import { ApiError } from '../utils/request';

export interface UseWordsParams {
  bookId: number;
  page: number;
  size: number;
  keyword: string;
}

export interface UseWordsResult {
  words: Word[];
  total: number;
  loading: boolean;
  error: string | null;
  reload: () => void;
}

/** 单词分页。服务端状态留在页面，不进全局 store。 */
export function useWords({ bookId, page, size, keyword }: UseWordsParams): UseWordsResult {
  const [words, setWords] = useState<Word[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    listWords({ bookId, page, size, keyword })
      .then((res) => {
        if (cancelled) return;
        setWords(res?.list ?? []);
        setTotal(res?.total ?? 0);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setWords([]);
        setTotal(0);
        setError(err instanceof ApiError ? err.message : '加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [bookId, page, size, keyword, tick]);

  return { words, total, loading, error, reload };
}
