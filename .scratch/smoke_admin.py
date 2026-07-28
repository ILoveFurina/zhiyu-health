# 票 32 冒烟：经 umi dev 代理(8001)走登录 + 三资源 CRUD + 角色拦截
# 凭据为虚构演示账号；创建的临时记录全部清理
import json
import sys
import urllib.request
import urllib.error

BASE = "http://localhost:8001/api"
PASS, FAIL = 0, 0


def call(method, path, body=None, token=None):
    req = urllib.request.Request(
        BASE + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **({"Authorization": "Bearer " + token} if token else {})},
    )
    try:
        with urllib.request.urlopen(req) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw else None


def check(name, cond, extra=""):
    global PASS, FAIL
    PASS, FAIL = PASS + cond, FAIL + (not cond)
    print(("PASS" if cond else "FAIL"), name, extra)


# 1. 错误密码 → 401 + detail
code, body = call("POST", "/b/auth/login", {"username": "admin", "password": "wrong"})
check("错误密码 401+detail", code == 401 and isinstance(body, dict) and "detail" in body, f"({code} {body})")

# 2. admin 登录 + /me
code, body = call("POST", "/b/auth/login", {"username": "admin", "password": "admin123456"})
check("admin 登录", code == 200 and body.get("token_type") == "bearer", f"({code})")
admin = body["access_token"]
code, me = call("GET", "/b/auth/me", token=admin)
check("admin /me", code == 200 and me["username"] == "admin" and me["role"] == "admin", f"({me})")

# 3. 无 token → 401
code, _ = call("GET", "/b/hospitals")
check("无 token 401", code == 401, f"({code})")

# 4. 医院 CRUD
code, lst = call("GET", "/b/hospitals", token=admin)
check("医院列表", code == 200 and isinstance(lst, list) and len(lst) >= 1, f"({len(lst)} 条)")
code, h = call("POST", "/b/hospitals", token=admin, body={"name": "冒烟测试医院", "level": "三级甲等", "address": "虚构地址 1 号", "longitude": 116.4, "latitude": 39.9})
check("医院新建", code in (200, 201) and h["id"], f"(id={h.get('id')})")
code, h2 = call("PUT", f"/b/hospitals/{h['id']}", token=admin, body={"name": "冒烟测试医院", "level": "二级乙等", "address": "虚构地址 2 号", "longitude": 116.5, "latitude": 39.8})
check("医院编辑", code == 200 and h2["level"] == "二级乙等", f"({h2.get('level')})")

# 5. 科室 CRUD（挂在临时医院下）
code, dl = call("GET", "/b/departments", token=admin)
check("科室列表", code == 200 and len(dl) >= 2, f"({len(dl)} 条)")
code, d = call("POST", "/b/departments", token=admin, body={"hospital_id": h["id"], "name": "冒烟测试科", "floor": "3F", "location": "东区 301"})
check("科室新建", code in (200, 201) and d["id"], f"(id={d.get('id')})")
code, d2 = call("PUT", f"/b/departments/{d['id']}", token=admin, body={"hospital_id": h["id"], "name": "冒烟测试科", "floor": "5F", "location": "西区 501"})
check("科室编辑", code == 200 and d2["floor"] == "5F", f"({d2.get('floor')})")

# 6. 医生 CRUD（挂在临时科室下）
code, ol = call("GET", "/b/doctors", token=admin)
check("医生列表", code == 200 and len(ol) >= 3, f"({len(ol)} 条)")
code, doc = call("POST", "/b/doctors", token=admin, body={"department_id": d["id"], "name": "冒烟医生", "title": "主任医师", "specialty": "虚构擅长", "photo_url": "https://example.com/x.png"})
check("医生新建", code in (200, 201) and doc["id"], f"(id={doc.get('id')})")
code, doc2 = call("PUT", f"/b/doctors/{doc['id']}", token=admin, body={"department_id": d["id"], "name": "冒烟医生", "title": "副主任医师", "specialty": "虚构擅长", "photo_url": "https://example.com/x.png"})
check("医生编辑", code == 200 and doc2["title"] == "副主任医师", f"({doc2.get('title')})")

# 7. 清理临时数据（医生 → 科室 → 医院）
for path in (f"/b/doctors/{doc['id']}", f"/b/departments/{d['id']}", f"/b/hospitals/{h['id']}"):
    code, _ = call("DELETE", path, token=admin)
    check(f"删除 {path.split('/')[-2]} 临时记录", code in (200, 204), f"({code})")

# 8. doctor 角色：登录成功但组织接口 403
code, body = call("POST", "/b/auth/login", {"username": "doctor.lin", "password": "doctor123456"})
check("doctor 登录", code == 200, f"({code})")
doctor = body["access_token"]
code, me = call("GET", "/b/auth/me", token=doctor)
check("doctor /me", code == 200 and me["role"] == "doctor" and me["doctor_id"] == 1, f"({me})")
code, body = call("GET", "/b/hospitals", token=doctor)
check("doctor 访问组织接口 403+detail", code == 403 and "detail" in (body or {}), f"({code} {body})")

print(f"\n== 冒烟结果: {PASS} 通过, {FAIL} 失败 ==")
sys.exit(1 if FAIL else 0)
