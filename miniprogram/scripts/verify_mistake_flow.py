# -*- coding: utf-8 -*-
"""错题本 + AI 讲解（F7）端到端验证。

重点验证 PRD F7 的两条硬约束：
1. 讲解失败时「标记已掌握」仍必须可用；
2. mastered 请求参数是 0/1 数字，响应体里是 JSON 布尔（契约 §5.1 明确二者不同源）。
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
        with urllib.request.urlopen(req, timeout=90) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def main():
    name = 'mk_' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    api('POST', '/api/client/auth/register', {'username': name, 'password': 'Test123456'})
    tok = api('POST', '/api/client/auth/login',
              {'username': name, 'password': 'Test123456'})['data']['token']

    books = api('GET', '/api/client/books', None, tok)['data']
    api('POST', '/api/client/books/%d/select' % books[0]['bookId'], None, tok)

    print('=== 错题本（F7）===')

    plan = api('GET', '/api/client/study/plan', None, tok)['data']
    bad = plan['words'][:3]
    for w in bad:
        api('POST', '/api/client/study/record',
            {'wordId': w['wordId'], 'bookId': w['bookId'], 'known': False}, tok)

    lst = api('GET', '/api/client/mistakes?mastered=0&page=1&size=20', None, tok)['data']
    check('答错自动收录错题本', lst['total'] == 3, '实际 %d' % lst['total'])
    check('分页结构含 list/total/page/size',
          all(k in lst for k in ('list', 'total', 'page', 'size')))
    item = lst['list'][0]
    check('条目含单词与释义', bool(item.get('word')) and bool(item.get('translation')))
    check('条目 source 为 STUDY', item.get('source') == 'STUDY', str(item.get('source')))
    check('响应体 mastered 是 JSON 布尔（不是 0/1）',
          item.get('mastered') is False, '实际 %r' % item.get('mastered'))
    check('条目含 wrongCount', item.get('wrongCount') >= 1)

    # 同词再答错一次 → wrongCount 累加而非新增记录
    w0 = bad[0]
    api('POST', '/api/client/study/record',
        {'wordId': w0['wordId'], 'bookId': w0['bookId'], 'known': False}, tok)
    lst2 = api('GET', '/api/client/mistakes?mastered=0&page=1&size=20', None, tok)['data']
    check('同词重复答错不新增记录', lst2['total'] == 3, '实际 %d' % lst2['total'])
    same = [x for x in lst2['list'] if x['wordId'] == w0['wordId']][0]
    check('同词重复答错累加 wrongCount', same['wrongCount'] >= 2,
          '实际 %d' % same['wrongCount'])

    mid = lst['list'][0]['mistakeId']

    # AI 讲解
    ex = api('POST', '/api/client/ai/explain', {'mistakeId': mid}, tok)
    check('AI 讲解返回 code 0', ex['code'] == '0', str(ex.get('message')))
    if ex['code'] == '0':
        d = ex['data']
        check('讲解含 explanation', bool(d.get('explanation')))
        check('讲解含 advice', 'advice' in d)
        check('讲解 degraded 为布尔', isinstance(d.get('degraded'), bool),
              str(type(d.get('degraded'))))
        check('讲解内容不是内置占位（有实质长度）', len(d['explanation']) > 20,
              '长度 %d' % len(d['explanation']))

    # 讲解失败不影响标记已掌握：先标记，再验证状态
    mk = api('POST', '/api/client/mistakes/%d/mastered' % mid, None, tok)
    check('标记已掌握返回 code 0', mk['code'] == '0', str(mk))
    check('标记后响应 mastered 为 true', mk['data']['mastered'] is True, str(mk['data']))

    todo = api('GET', '/api/client/mistakes?mastered=0&page=1&size=20', None, tok)['data']
    done = api('GET', '/api/client/mistakes?mastered=1&page=1&size=20', None, tok)['data']
    check('未掌握列表减少 1 条', todo['total'] == 2, '实际 %d' % todo['total'])
    check('已掌握列表增加 1 条', done['total'] == 1, '实际 %d' % done['total'])
    check('已掌握条目 mastered 为 true', done['list'][0]['mastered'] is True)

    # 重复标记 → 4002
    again = api('POST', '/api/client/mistakes/%d/mastered' % mid, None, tok)
    check('重复标记返回 4002', again['code'] == '4002', str(again))

    # 标记不存在的错题 → 4001
    nf = api('POST', '/api/client/mistakes/99999999/mastered', None, tok)
    check('不存在错题标记返回 4001', nf['code'] == '4001', str(nf))

    # 移除
    rm = api('DELETE', '/api/client/mistakes/%d' % mid, None, tok)
    check('移除错题返回 code 0', rm['code'] == '0', str(rm))
    check('移除响应 removed 为 true', rm['data']['removed'] is True, str(rm['data']))
    done2 = api('GET', '/api/client/mistakes?mastered=1&page=1&size=20', None, tok)['data']
    check('移除后已掌握列表归零', done2['total'] == 0, '实际 %d' % done2['total'])

    # 移除不存在的 → 4001
    nf2 = api('DELETE', '/api/client/mistakes/%d' % mid, None, tok)
    check('移除不存在错题返回 4001', nf2['code'] == '4001', str(nf2))

    # 分页
    pg = api('GET', '/api/client/mistakes?mastered=0&page=1&size=1', None, tok)['data']
    check('size=1 时 list 长度为 1', len(pg['list']) == 1, '实际 %d' % len(pg['list']))
    check('size=1 时 total 仍是全量', pg['total'] == 2, '实际 %d' % pg['total'])

    print('\n' + '=' * 50)
    if failed:
        print('✗ %d 项通过，%d 项失败' % (passed, len(failed)))
        for f in failed:
            print('  ✗ ' + f)
        raise SystemExit(1)
    print('✓ %d 项断言全部通过' % passed)


if __name__ == '__main__':
    main()
