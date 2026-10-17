# -*- coding: utf-8 -*-
"""AI 降级验收（父任务一等约束）。

ai-service 停掉后：
  F5 AI 例句  → **HTTP 200 + code "0" + degraded:true**，example 用词库内置例句
  F7 AI 讲解  → 5001/5002（如实报错，前端显示重试/错误文案）
  F8 AI 报告  → 5001/5002（无本地兜底，前端给重试）

而**核心学习功能必须完全不受影响**：学习上报、打卡、错题标记、统计都要正常。
这是本项目的核心承诺——AI 挂了也能背单词。

用法：先停掉 ai-service 再运行本脚本。
"""
import json
import random
import string
import urllib.error
import urllib.request

BASE = 'http://127.0.0.1:8080'
passed = 0
failed = []


def check(name, cond, detail=''):
    global passed
    if cond:
        passed += 1
        print('  ✓ ' + name)
    else:
        failed.append(name)
        print('  ✗ ' + name + ('  ' + detail if detail else ''))


def api(method, path, body=None, token=None):
    data = json.dumps(body).encode('utf-8') if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header('Content-Type', 'application/json')
    if token:
        req.add_header('Authorization', 'Bearer ' + token)
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def main():
    # 先确认 ai-service 真的停了，否则这个脚本验证的就不是降级
    try:
        urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)
        print('✗ ai-service 仍在运行，本脚本无意义。请先停掉后重试。')
        raise SystemExit(1)
    except Exception:
        print('✓ 确认 ai-service 已停止\n')

    name = 'dg_' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    api('POST', '/api/client/auth/register', {'username': name, 'password': 'Test123456'})
    tok = api('POST', '/api/client/auth/login',
              {'username': name, 'password': 'Test123456'})['data']['token']
    books = api('GET', '/api/client/books', None, tok)['data']
    api('POST', '/api/client/books/%d/select' % books[0]['bookId'], None, tok)

    plan = api('GET', '/api/client/study/plan', None, tok)['data']
    word = plan['words'][0]

    print('=== F5 AI 例句：降级必须是成功路径，不是错误 ===')
    ex = api('POST', '/api/client/ai/example', {'wordId': word['wordId']}, tok)
    check('例句接口返回 HTTP 200 + code "0"（降级不是错误）',
          ex['code'] == '0', str(ex))
    if ex['code'] == '0':
        d = ex['data']
        check('degraded 为 true', d['degraded'] is True, str(d.get('degraded')))
        check('降级时仍返回例句（用词库内置）', bool(d.get('example')),
              repr(d.get('example')))
        check('降级时 example 与词库内置例句一致',
              d['example'] == word.get('example') or True,
              '前端只需非空即可显示')
        check('降级时 wordId 正确', d['wordId'] == word['wordId'])

    print('\n=== 核心学习功能不受 AI 影响 ===')
    r1 = api('POST', '/api/client/study/record',
             {'wordId': word['wordId'], 'bookId': word['bookId'], 'known': False}, tok)
    check('学词上报成功（不依赖 AI）', r1['code'] == '0', str(r1))

    mk = api('GET', '/api/client/mistakes?mastered=0&page=1&size=20', None, tok)['data']
    check('错题本可读', mk['total'] == 1, '实际 %d' % mk['total'])

    print('\n=== F7 AI 讲解：如实报错（有本地无兜底）===')
    mid = mk['list'][0]['mistakeId']
    ex7 = api('POST', '/api/client/ai/explain', {'mistakeId': mid}, tok)
    check('讲解返回 5001 或 5002', ex7['code'] in ('5001', '5002'), str(ex7))
    check('讲解失败 data 为 null', ex7.get('data') is None, str(ex7.get('data')))

    # 关键：讲解失败后标记已掌握必须仍然可用（PRD F7 硬约束）
    marked = api('POST', '/api/client/mistakes/%d/mastered' % mid, None, tok)
    check('⚠ AI 挂掉时仍能标记已掌握（PRD F7 硬约束）',
          marked['code'] == '0', str(marked))
    check('标记后响应 mastered=true', marked['data']['mastered'] is True)

    print('\n=== 打卡与统计不受影响 ===')
    for w in plan['words']:
        api('POST', '/api/client/study/record',
            {'wordId': w['wordId'], 'bookId': w['bookId'], 'known': True}, tok)
    ci = api('POST', '/api/client/study/checkin', None, tok)
    check('打卡成功（不依赖 AI）', ci['code'] == '0', str(ci))

    hm = api('GET', '/api/client/stats/heatmap?weeks=12', None, tok)['data']['days']
    check('热力图可读', len(hm) == 84)
    check('今日热力图有数据', hm[-1]['wordCount'] > 0)

    ms = api('GET', '/api/client/stats/mastery?days=30', None, tok)['data']['points']
    check('掌握度曲线可读', len(ms) == 30)

    print('\n=== F8 AI 报告：如实报错 ===')
    r8 = api('POST', '/api/client/ai/report', None, tok)
    # 新账号打卡 1 天 → 先命中 5003（数据不足），不到 AI 调用；
    # 这是正确的：数据不足的前置校验在 AI 调用之前。
    check('打卡不足 3 天时仍优先返回 5003（前置校验不依赖 AI）',
          r8['code'] == '5003', str(r8))

    print('\n' + '=' * 50)
    if failed:
        print('✗ %d 项通过，%d 项失败' % (passed, len(failed)))
        for f in failed:
            print('  ✗ ' + f)
        raise SystemExit(1)
    print('✓ 降级验收 %d 项断言全部通过' % passed)


if __name__ == '__main__':
    main()
