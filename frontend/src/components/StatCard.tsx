import { Card, Statistic } from 'antd';
import type { ReactNode } from 'react';

interface Props {
  title: string;
  value: number | string;
  suffix?: string;
  precision?: number;
  icon: ReactNode;
  color: string;
}

/** 监控页顶部统计卡。 */
export default function StatCard({ title, value, suffix, precision, icon, color }: Props) {
  return (
    <Card size="small">
      <Statistic
        title={title}
        value={value}
        suffix={suffix}
        precision={precision}
        prefix={<span style={{ color, marginRight: 6 }}>{icon}</span>}
        valueStyle={{ fontSize: 24 }}
      />
    </Card>
  );
}
