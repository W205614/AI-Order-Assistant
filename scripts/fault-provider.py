"""Internal synthetic provider for fault verification; never exposes a host port."""
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json,time
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args):pass
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length',0)))
        if '/disconnect/' in self.path:self.close_connection=True;return
        if '/timeout/' in self.path:time.sleep(40)
        body=json.dumps({'error':{'message':'synthetic provider rate limit','type':'rate_limit_error','code':'rate_limit'}}).encode()
        try:
            self.send_response(429);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(body)));self.end_headers();self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError):pass
ThreadingHTTPServer(('0.0.0.0',8810),Handler).serve_forever()
