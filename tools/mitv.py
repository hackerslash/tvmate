import hmac,hashlib,time,urllib.parse,json,subprocess
K="581582928c881b42eedce96331bff5d3";T="0f9dfa001cba164d7bda671649c50abf"
CL={"pune":"20.47.89.121","sgp":"47.236.57.101","ams":"20.33.39.105"}
def get(path,cl,xff=None,**ov):
    q=dict(me="a1b2c3d4e5f60718",app="4",codever="58",today=str(int(time.time())),ostype="1",androidver="30",region="in",dlg="en",ptf="1234",devicetype="0")
    q.update(ov); pq=path+"?"+urllib.parse.urlencode(q)
    sig=hmac.new(K.encode(),(pq+"&token="+T).encode(),hashlib.sha1).hexdigest()
    cmd=["curl","-s","-m","20","--resolve","mitvplus.global.mi.com:443:"+CL[cl],"https://mitvplus.global.mi.com"+pq+"&opaque="+sig]
    if xff: cmd+=["-H","X-Forwarded-For: "+xff]
    return json.loads(subprocess.run(cmd,capture_output=True,text=True).stdout)
def chans(d):
    return [c for b in ((d.get("data") or {}).get("blocks") or []) for c in (b.get("channels") or [])]
