# -*- coding: utf-8 -*-
"""验证：字幕上传到视频同级目录后，/open/video/subtitle 是否自动关联（主动挂载依据）"""
import base64
import hashlib
import hmac
import json
import sys
import urllib.request
import urllib.parse
import urllib.error
import email.utils

TOKEN = open("F:/ad/.fresh_token").read().strip()
PICK = sys.argv[1]
BASE = "https://proapi.115.com"

def http(url, method="GET", form=None, headers=None):
    data = urllib.parse.urlencode(form).encode() if form is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read().decode())

# 造一个最小 srt
srt = "1\n00:00:01,000 --> 00:00:04,000\n测试字幕行一\n\n2\n00:00:05,000 --> 00:00:08,000\n测试字幕行二\n".encode("utf-8")
video_name = "命悬一生.The.Hunt.S01E01.2025.2160p.WEB-DL.H265.HDR.DDP5.1-ColorTV.mkv"
sub_name = video_name + ".test.zh-CN.srt"

# 视频的 parent_id
info = http(BASE + "/open/video/play?pick_code=" + PICK)
dd = info.get("data")
if isinstance(dd, list):
    dd = dd[0] if dd else {}
parent = (dd or {}).get("parent_id", "0")
print("视频 parent_id =", parent, "| state =", info.get("state"), "msg =", info.get("message"))

# 上传字幕到同级目录（复用已验证的协议）
_c = http(BASE + "/open/upload/get_token")["data"]
cred = _c[0] if isinstance(_c, list) else _c
endpoint = cred["endpoint"].replace("https://", "").replace("http://", "")
size = len(srt)
sha1 = hashlib.sha1(srt).hexdigest().upper()
preid = hashlib.sha1(srt[:131072]).hexdigest().upper()
init = http(BASE + "/open/upload/init", "POST",
            {"file_name": sub_name, "file_size": size, "target": "U_1_" + parent,
             "fileid": sha1, "preid": preid})
d = init["data"][0] if isinstance(init["data"], list) else init["data"]
if d.get("status") == 2:
    print("秒传成功（该字幕已在库）file_id=", d.get("file_id"))
else:
    bucket, obj = d["bucket"], d["object"]
    cb = d["callback"]
    callback = cb["callback"] if isinstance(cb, list) else (cb if isinstance(cb, dict) else (cb[0] if isinstance(cb, list) and cb else {}))
    callback = cb.get("callback", "") if isinstance(cb, dict) else ""
    callback_var = cb.get("callback_var", "") if isinstance(cb, dict) else ""
    date = email.utils.formatdate(usegmt=True)
    cb_b = base64.b64encode(callback.encode()).decode()
    cbv_b = base64.b64encode(callback_var.encode()).decode()
    canon = ("x-oss-callback:%s\nx-oss-callback-var:%s\nx-oss-security-token:%s\n" % (cb_b, cbv_b, cred["SecurityToken"]))
    sts = "PUT\n\napplication/octet-stream\n%s\n%s/%s/%s" % (date, canon, bucket, obj)
    sig = base64.b64encode(hmac.new(cred["AccessKeySecret"].encode(), sts.encode(), hashlib.sha1).digest()).decode()
    req = urllib.request.Request("http://%s.%s/%s" % (bucket, endpoint, obj), data=srt, method="PUT")
    req.add_header("Content-Type", "application/octet-stream")
    req.add_header("Date", date)
    req.add_header("Authorization", "OSS %s:%s" % (cred["AccessKeyId"], sig))
    req.add_header("x-oss-security-token", cred["SecurityToken"])
    req.add_header("x-oss-callback", cb_b)
    req.add_header("x-oss-callback-var", cbv_b)
    with urllib.request.urlopen(req, timeout=30) as r:
        print("OSS PUT", r.status, r.read()[:200].decode("utf-8", "replace"))

# 关键验证：字幕关联接口是否已包含刚上传的字幕
sub = http(BASE + "/open/video/subtitle?pick_code=" + PICK)
dd = sub.get("data") or {}
lst = dd.get("list") or []
print("== 字幕关联列表（%d 条）==" % len(lst))
found = False
for it in lst:
    mark = " <== 刚上传的" if it.get("file_name") == sub_name else ""
    if mark:
        found = True
    print(" -", it.get("file_name"), it.get("type"), mark)
print("== 自动关联：", "成功（下次播放将主动挂载）" if found else "未发现（可能需要几分钟索引）")
