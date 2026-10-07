"""Cookie/CSRF HTTP helper. Credentials never appear in assertion output."""
from __future__ import annotations
import httpx
class Session:
    def __init__(self, base: str, merchant: int | None = 1, management: bool = False):
        self.client=httpx.Client(base_url=base.rstrip("/"),timeout=45,trust_env=False)
        self.headers={}
        if merchant is not None:self.headers["X-Merchant-Id"]=str(merchant)
        if management:self.headers["X-Session-Type"]="management"
        self.csrf=self.ok("GET","/auth/csrf")["token"]
        self.headers["X-XSRF-TOKEN"]=self.csrf
    def response(self,method,path,body=None,extra=None):
        return self.client.request(method,path,json=body,headers={**self.headers,**(extra or {})})
    def ok(self,method,path,body=None,extra=None):
        r=self.response(method,path,body,extra)
        b=r.json()
        assert r.status_code==200 and b.get("code")==1,f"{method} {path}: HTTP {r.status_code} / {b.get('errorCode')}"
        if method=="POST" and path in ("/auth/login","/auth/register","/admin/login"):
            csrf=self.ok("GET","/auth/csrf")
            self.csrf=csrf["token"]
            self.headers[csrf["headerName"]]=self.csrf
        return b.get("data")
    def login(self,username,password,management=False):
        return self.ok("POST","/admin/login" if management else "/auth/login",{"username":username,"password":password})
    def close(self):
        self.client.close()
