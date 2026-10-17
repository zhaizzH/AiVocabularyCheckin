import { useCallback, useEffect, useState } from 'react';
import type { AdminUser } from '../types/user';
import { listUsers } from '../services/user';
import { ApiError } from '../utils/request';

export interface UseUsersParams {
  page: number;
  size: number;
  keyword: string;
}

export interface UseUsersResult {
  users: AdminUser[];
  total: number;
  loading: boolean;
  error: string | null;
  reload: () => void;
}

export function useUsers({ page, size, keyword }: UseUsersParams): UseUsersResult {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => setTick((n) => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);

    listUsers({ page, size, keyword })
      .then((res) => {
        if (cancelled) return;
        setUsers(res?.list ?? []);
        setTotal(res?.total ?? 0);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setUsers([]);
        setTotal(0);
        setError(err instanceof ApiError ? err.message : '加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [page, size, keyword, tick]);

  return { users, total, loading, error, reload };
}
