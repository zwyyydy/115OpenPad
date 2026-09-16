# -*- coding: utf-8 -*-
"""115 开放平台小文件上传协议实测脚本
用法: python test_upload.py <access_token> <测试文件路径>
"""
import base64
import hashlib
import hmac
import json
import sys
import urllib.request
import urllib.parse
import urllib.error
import email.utils

TOKEN = sys.argv[1]
FILE = sys.argv[2]
BASE = "https://proapi.115.com"

def http(url, method="GET", form=None, headers=None, data=None):
    if form is not None:
        data = urllib.parse.urlencode(form).encode()
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("User-Agent", "115Pad/0.1 (Android)")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

def jload(b):
    try:
        return json.loads(b.decode("utf-8", "replace"))
    except Exception:
        return {"_raw": b[:300].decode("utf-8", "replace")}

raw = open(FILE, "rb").read()
size = len(raw)
sha1 = hashlib.sha1(raw).hexdigest().upper()
pre_raw = raw[:131072]
preid = hashlib.sha1(pre_raw).hexdigest().upper()
print("== 文件: %s 字节=%d sha1=%s preid=%s" % (FILE, size, sha1, preid))

# 1. get_token
st, body = http(BASE + "/open/upload/get_token")
print("== get_token http=%s resp=%s" % (st, body[:400].decode("utf-8", "replace")))
tok = jload(body)
cred = tok.get("data") or {}
if isinstance(cred, list):
    cred = cred[0] if cred else {}
endpoint = cred.get("endpoint", "")
ak, sk, stoken = cred.get("AccessKeyId", ""), cred.get("AccessKeySecret", ""), cred.get("SecurityToken", "")
print("== STS endpoint=%s AK=%s... expiration=%s" % (endpoint, ak[:8], cred.get("Expiration")))

# 2. init（含二次认证循环）
form = {"file_name": FILE.split("/")[-1].split("\\")[-1], "file_size": size,
        "target": "U_1_0", "fileid": sha1, "preid": preid}
for round in range(3):
    st, body = http(BASE + "/open/upload/init", "POST", form)
    print("== init http=%s resp=%s" % (st, body[:600].decode("utf-8", "replace")))
    r = jload(body)
    if not r.get("state"):
        print("!! init 失败"); sys.exit(1)
    d = r.get("data") or {}
    if isinstance(d, list):
        d = d[0] if d else {}
    if d.get("status") == 2:
        print("!! 秒传成功 file_id=%s" % d.get("file_id")); sys.exit(0)
    sign_key, sign_check = d.get("sign_key"), d.get("sign_check")
    if r.get("code") in (701, 702) or d.get("sign_check"):
        a, b = sign_check.split("-")
        seg = raw[int(a):int(b) + 1]
        sign_val = hashlib.sha1(seg).hexdigest().upper()
        print("== 二次认证 区间=%s 实取%d字节 sign_val=%s" % (sign_check, len(seg), sign_val))
        form.update({"sign_key": sign_key, "sign_val": sign_val})
        continue
    break

pick_code = d.get("pick_code", "")
bucket, obj = d.get("bucket", ""), d.get("object", "")
cb = d.get("callback") or {}
if isinstance(cb, list):
    cb = cb[0] if cb else {}
callback, callback_var = cb.get("callback", ""), cb.get("callback_var", "")
print("== 非秒传 pick_code=%s bucket=%s object=%s" % (pick_code, bucket, obj[:60]))
print("== callback=%s" % callback[:120])
print("== callback_var=%s" % callback_var[:200])

# 3. OSS PUT（V1 签名 + STS + callback 头）
host = "%s.%s" % (bucket, endpoint)
url = "http://%s/%s" % (host.replace("https://", ""), obj)
date = email.utils.formatdate(usegmt=True)
cb_b64 = base64.b64encode(callback.encode()).decode()
cbv_b64 = base64.b64encode(callback_var.encode()).decode()
oss_headers = {
    "x-oss-security-token": stoken,
    "x-oss-callback": cb_b64,
    "x-oss-callback-var": cbv_b64,
}
canon_headers = "".join("%s:%s\n" % (k, v) for k, v in sorted(oss_headers.items()))
canonical = canon_headers + "/" + bucket + "/" + obj
content_type = "application/octet-stream"
string_to_sign = "PUT\n\n%s\n%s\n%s" % (content_type, date, canonical)
sig = base64.b64encode(hmac.new(sk.encode(), string_to_sign.encode(), hashlib.sha1).digest()).decode()
print("== StringToSign=%r" % string_to_sign)

req = urllib.request.Request(url, data=raw, method="PUT")
req.add_header("Content-Type", content_type)
req.add_header("Date", date)
req.add_header("Authorization", "OSS %s:%s" % (ak, sig))
req.add_header("x-oss-security-token", stoken)
req.add_header("x-oss-callback", cb_b64)
req.add_header("x-oss-callback-var", cbv_b64)
try:
    with urllib.request.urlopen(req, timeout=60) as r:
        print("== OSS PUT http=%s resp=%s" % (r.status, r.read()[:500].decode("utf-8", "replace")))
except urllib.error.HTTPError as e:
    print("!! OSS PUT http=%s resp=%s" % (e.code, e.read()[:600].decode("utf-8", "replace")))
