# -*- coding: utf-8 -*-
"""AI 学习报告（F8）端到端验证。

报告的前置校验是「累计打卡天数 < 3 → 5003 且不发起 AI 调用」。
新账号必然命中 5003，所以成功路径必须先造出 3 天打卡记录。
后端没有「造历史打卡」的接口，只能直接写 study_record.check_in_date。
"""
import datetime
import json
import random
import string
import urllib.error
import urllib.request

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
        with urllib.request.urlopen(req, timeout=90) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode('utf-8'))


def main():
    name = 'rp_' + ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    api('POST', '/api/client/auth/register', {'username': name, 'password': 'Test123456'})
    tok = api('POST', '/api/client/auth/login',
              {'username': name, 'password': 'Test123456'})['data']['token']

    books = api('GET', '/api/client/books', None, tok)['data']
    api('POST', '/api/client/books/%d/select' % books[0]['bookId'], None, tok)

    print('=== AI 学习报告（F8）===')

    # 前置：新账号 0 打卡天数
    r = api('POST', '/api/client/ai/report', None, tok)
    check('新账号（0 天打卡）返回 5003', r['code'] == '5003', str(r))
    check('5003 文案说明数据不足',
          '数据' in r.get('message', '') or '不足' in r.get('message', ''),
          r.get('message', ''))

    # 造 3 天打卡记录（直接写库：后端无造历史接口）
    plan = api('GET', '/api/client/study/plan', None, tok)['data']
    word = plan['words'][0]

    conn = pymysql.connect(**MYSQL)
    cur = conn.cursor()
    cur.execute("SELECT id FROM user WHERE username=%s", (name,))
    uid = cur.fetchone()[0]
    for i in range(3):
        d = datetime.date.today() - datetime.timedelta(days=i)
        cur.execute(
            "INSERT INTO study_record "
            "(user_id, book_id, word_id, study_type, study_date, check_in_date, "
            " streak_days, correct, deleted, create_time, update_time) "
            "VALUES (%s,%s,%s,'STUDY',%s,%s,%s,1,0,NOW(),NOW())",
            (uid, word['bookId'], word['wordId'], d, d, i + 1),
        )
    conn.commit()
    cur.execute("SELECT COUNT(DISTINCT check_in_date) FROM study_record "
                "WHERE user_id=%s AND deleted=0 AND check_in_date IS NOT NULL", (uid,))
    days = cur.fetchone()[0]
    conn.close()
    check('已造出 3 天打卡记录', days == 3, '实际 %d 天' % days)

    # 现在应能生成报告
    r2 = api('POST', '/api/client/ai/report', None, tok)
    check('3 天数据后报告返回 code 0', r2['code'] == '0', str(r2.get('message')))
    if r2['code'] == '0':
        d = r2['data']
        rep = d.get('report') or {}
        check('报告含 overview', bool(rep.get('overview')))
        check('报告含 weakness', bool(rep.get('weakness')))
        check('报告含 suggestion', bool(rep.get('suggestion')))
        check('overview 有实质长度（非占位）',
              len(rep.get('overview', '')) > 20, '长度 %d' % len(rep.get('overview', '')))
        check('含 generatedAt', bool(d.get('generatedAt')), str(d.get('generatedAt')))
        check('generatedAt 格式为 YYYY-MM-DD HH:mm:ss',
              len(d.get('generatedAt', '')) == 19 and ' ' in d.get('generatedAt', ''),
              str(d.get('generatedAt')))
        check('degraded 为布尔（报告无降级兜底，应为 false）',
              d.get('degraded') is False, str(d.get('degraded')))

    print('\n' + '=' * 50)
    if failed:
        print('✗ %d 项通过，%d 项失败' % (passed, len(failed)))
        for f in failed:
            print('  ✗ ' + f)
        raise SystemExit(1)
    print('✓ %d 项断言全部通过' % passed)


if __name__ == '__main__':
    main()
