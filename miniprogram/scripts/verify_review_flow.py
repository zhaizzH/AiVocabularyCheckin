# -*- coding: utf-8 -*-
"""复习页（F6）端到端验证。

新账号今日待复习必为 0（stage=1 → 1 天后到期），这是正确语义而非缺陷。
为了真正测到复习流程，这里直接操作数据库把 next_review_date 推到今天，
这也是验证「SM-2 调度 + 复习页消费」唯一可行的办法——后端没有「造待复习」的接口。
"""
import json
import os
import subprocess
import urllib.error
import urllib.request
import random
import string

import pymysql

BASE = 'http://127.0.0.1:8080'
MYSQL = dict(host='127.0.0.1', user='root', password='<REDACTED_DB_PASSWORD>',
             database='ai_vocab', charset='utf8mb4')

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
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def rand_name(prefix):
    tail = ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return prefix + '_' + tail


def db_cursor():
    conn = pymysql.connect(**MYSQL)
    return conn, conn.cursor()


def main():
    name = rand_name('rv')
    api('POST', '/api/client/auth/register', {'username': name, 'password': 'Test123456'})
    tok = api('POST', '/api/client/auth/login',
              {'username': name, 'password': 'Test123456'})['data']['token']

    books = api('GET', '/api/client/books', None, tok)['data']
    api('POST', '/api/client/books/%d/select' % books[0]['bookId'], None, tok)

    print('=== 复习页（F6）===')

    plan = api('GET', '/api/client/study/plan', None, tok)['data']
    for w in plan['words'][:3]:
        api('POST', '/api/client/study/record',
            {'wordId': w['wordId'], 'bookId': w['bookId'], 'known': True}, tok)

    rv = api('GET', '/api/client/review/today', None, tok)['data']
    check('学过新词后今日待复习为 0（stage=1 → 明天，正确语义）', rv['total'] == 0,
          '实际 %d' % rv['total'])

    # 造数据：把 3 个词的 next_review_date 推到今天
    conn, cur = db_cursor()
    cur.execute("SELECT id FROM user WHERE username=%s", (name,))
    uid = cur.fetchone()[0]
    cur.execute("UPDATE word_progress SET next_review_date=CURDATE(), stage=2 "
                "WHERE user_id=%s", (uid,))
    conn.commit()
    cur.execute("SELECT word_id, stage, next_review_date FROM word_progress WHERE user_id=%s", (uid,))
    rows = cur.fetchall()
    conn.close()

    rv = api('GET', '/api/client/review/today', None, tok)['data']
    check('把 next_review_date 改到今天后出现待复习词', rv['total'] == 3,
          '实际 %d' % rv['total'])
    check('待复习词携带 word/phonetic/translation', all(
        w.get('word') and w.get('translation') for w in rv['words']))
    check('待复习词携带 stage', all(w.get('stage') for w in rv['words']))
    check('待复习词携带 wrongCount（默认 0）', all(
        'wrongCount' in w for w in rv['words']))

    first = rv['words'][0]
    total_before = rv['total']

    # 答对 → stage+1，间隔变长，从今日列表消失
    r1 = api('POST', '/api/client/review/record',
             {'wordId': first['wordId'], 'correct': True}, tok)
    check('答对返回 code 0', r1['code'] == '0', str(r1))
    check('答对后 stage 递增', r1['data']['stage'] == first['stage'] + 1,
          '前 %s → 后 %s' % (first['stage'], r1['data']['stage']))
    import datetime
    d_next = datetime.date.fromisoformat(r1['data']['nextReviewDate'])
    check('答对后 nextReviewDate 在未来', d_next > datetime.date.today(),
          '实际 %s' % r1['data']['nextReviewDate'])

    rv2 = api('GET', '/api/client/review/today', None, tok)['data']
    check('答对后该词从今日待复习列表移除', rv2['total'] == total_before - 1,
          '%d → %d' % (total_before, rv2['total']))
    check('被移除的正是刚才那个词',
          first['wordId'] not in [w['wordId'] for w in rv2['words']])

    # 答错 → stage 重置为 1，且收录错题本
    second = rv2['words'][0]
    r2 = api('POST', '/api/client/review/record',
             {'wordId': second['wordId'], 'correct': False}, tok)
    check('答错返回 code 0', r2['code'] == '0', str(r2))
    check('答错后 stage 重置为 1', r2['data']['stage'] == 1,
          '实际 %s' % r2['data']['stage'])
    d_tomorrow = datetime.date.today() + datetime.timedelta(days=1)
    check('答错后 nextReviewDate 为明天（1 天间隔）',
          r2['data']['nextReviewDate'] == d_tomorrow.isoformat(),
          '实际 %s，期望 %s' % (r2['data']['nextReviewDate'], d_tomorrow.isoformat()))

    mistakes = api('GET', '/api/client/mistakes?page=1&size=20', None, tok)['data']
    check('答错自动收录错题本', mistakes['total'] == 1, '实际 %d' % mistakes['total'])
    check('错题本是刚才答错的词',
          mistakes['list'][0]['wordId'] == second['wordId'])

    # 复习记录不带 bookId（契约 §4.2）
    r3 = api('POST', '/api/client/review/record',
             {'wordId': rv2['words'][1]['wordId'], 'correct': True}, tok)
    check('复习上报不带 bookId 也能成功', r3['code'] == '0', str(r3))

    # 未学过的词复习 → 1005
    r4 = api('POST', '/api/client/review/record',
             {'wordId': 99999999, 'correct': True}, tok)
    check('对无学习记录的词复习返回 1005', r4['code'] == '1005', str(r4))

    # 全部答对后今日清空
    rest = api('GET', '/api/client/review/today', None, tok)['data']
    for w in rest['words']:
        api('POST', '/api/client/review/record', {'wordId': w['wordId'], 'correct': True}, tok)
    final = api('GET', '/api/client/review/today', None, tok)['data']
    check('全部复习完今日待复习归零', final['total'] == 0, '实际 %d' % final['total'])

    print('\n' + '=' * 50)
    if failed:
        print('✗ %d 项通过，%d 项失败' % (passed, len(failed)))
        for f in failed:
            print('  ✗ ' + f)
        raise SystemExit(1)
    print('✓ %d 项断言全部通过' % passed)


if __name__ == '__main__':
    main()
