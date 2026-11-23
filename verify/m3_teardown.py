"""验收数据清理：删掉本轮脚本自己创建的订单。

验收脚本每轮都会下单，留着库只会越跑越脏（P2-7）。这里按「座位 → 票 → 订单」
的顺序删，座位只释放仍属于这些订单的那些，避免影响别人已经买走的座位。
需要 mysql 客户端：verify-m3.cmd 会把路径放进 FERRY_MYSQL_BIN。
"""

import os
import subprocess
import tempfile


def _mysql_bin():
    path = os.environ.get("FERRY_MYSQL_BIN") or os.environ.get("MYSQL_BIN")
    if path:
        return path
    raise SystemExit("FERRY_MYSQL_BIN 未设置：请用 verify/verify-m3.cmd 跑，或手动指向 mysql.exe")


def teardown(order_ids):
    ids = sorted({int(i) for i in order_ids if i})
    if not ids:
        return 0
    listed = ",".join(str(i) for i in ids)
    db = os.environ.get("FERRY_DB", "ferry_system")
    statements = [
        f"USE {db};",
        f"UPDATE seat SET seat_status='可选', order_id=NULL, lock_until=NULL WHERE order_id IN ({listed});",
        # 改签票自引用原票，先解开引用再删
        f"UPDATE ticket SET changed_from_ticket_id=NULL WHERE order_id IN ({listed});",
        f"DELETE FROM ticket WHERE order_id IN ({listed});",
        f"DELETE FROM orders WHERE id IN ({listed});",
    ]
    handle, path = tempfile.mkstemp(suffix=".sql")
    with os.fdopen(handle, "w", encoding="utf-8") as f:
        f.write("\n".join(statements) + "\n")
    cmd = [
        _mysql_bin(), "--protocol=TCP", "-h", "127.0.0.1",
        "-P", os.environ.get("DB_PORT", "3307"),
        "-u", os.environ.get("DB_USER", "root"),
        "-p" + os.environ.get("DB_PASSWORD", ""),
        "--default-character-set=utf8mb4",
    ]
    with open(path, "rb") as stdin:
        done = subprocess.run(cmd, stdin=stdin, capture_output=True)
    os.unlink(path)
    if done.returncode != 0:
        print("  清理失败：" + done.stderr.decode("utf-8", "replace").strip())
        return done.returncode
    print(f"  已清理本轮创建的 {len(ids)} 个订单")
    return 0