from pathlib import Path

p = Path("scripts/verify_miniprogram.py")
t = p.read_text(encoding="utf-8")

bad = '    return "\n".join(lines)\n\ndef check_stats_empty_logic'
good = '    return "\\n".join(lines)\n\n\ndef check_stats_empty_logic'

assert bad in t, "未找到待修片段"
t = t.replace(bad, good, 1)
p.write_text(t, encoding="utf-8")
print("fixed")
