# -*- coding: utf-8 -*-
"""一键跑完学生端全部验证。

前置：
  1. 后端在 127.0.0.1:8080（cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=local）
  2. ai-service 在 127.0.0.1:8000（降级验收需手动停掉再跑 verify_degraded_flow.py）
  3. MySQL 可连（部分脚本要用 pymysql 造历史数据）

用法：PYTHONIOENCODING=utf-8 python scripts/verify_all.py
"""
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# 顺序有意义：先静态、再接口、再页面、最后全链路
SUITES = [
    ('静态检查', ['python', 'scripts/verify_miniprogram.py']),
    ('接口契约', ['python', 'scripts/verify_api_flow.py']),
    ('复习流程', ['python', 'scripts/verify_review_flow.py']),
    ('错题与讲解', ['python', 'scripts/verify_mistake_flow.py']),
    ('AI 报告', ['python', 'scripts/verify_report_flow.py']),
    ('数据统计', ['python', 'scripts/verify_stats_flow.py']),
    ('页面逻辑', ['node', 'scripts/verify_page_flow.js']),
    ('全链路', ['python', 'scripts/verify_e2e_flow.py']),
]

# 降级验收需要手动停 ai-service，不放进默认套件，只提示
MANUAL = ('降级验收', 'python scripts/verify_degraded_flow.py',
          '需先停掉 ai-service（taskkill /PID <pid> /F）')


def main():
    results = []
    for name, cmd in SUITES:
        print('\n' + '=' * 60)
        print('▶ ' + name)
        print('=' * 60)
        proc = subprocess.run(cmd, cwd=str(ROOT))
        results.append((name, proc.returncode == 0))

    print('\n' + '=' * 60)
    print('汇总')
    print('=' * 60)
    failed = []
    for name, ok in results:
        print(('  ✓ ' if ok else '  ✗ ') + name)
        if not ok:
            failed.append(name)

    print()
    print('  手动套件：' + MANUAL[0] + ' —— ' + MANUAL[2])
    print('    ' + MANUAL[1])

    if failed:
        print('\n✗ %d 个套件失败：%s' % (len(failed), '、'.join(failed)))
        return 1
    print('\n✓ %d 个套件全部通过' % len(results))
    return 0


if __name__ == '__main__':
    sys.exit(main())
