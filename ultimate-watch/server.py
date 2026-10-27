#!/usr/bin/env python3
"""Ultimate Watch: experimental live snapshots over HTTPS, memory-only."""
import argparse, asyncio, hmac, os, secrets, ssl, time
from pathlib import Path
from aiohttp import web

ROOT = Path(__file__).resolve().parent
MAX_FRAME = 1_000_000
sessions = set()
cameras = {}
password = os.environ.get('WATCH_PASSWORD', '')

@web.middleware
async def guard(request, handler):
    if request.path.startswith('/api/'):
        if request.cookies.get('watch_session') not in sessions:
            raise web.HTTPUnauthorized()
        if request.method not in ('GET', 'HEAD'):
            origin = request.headers.get('Origin', '')
            if origin != f'{request.scheme}://{request.host}':
                raise web.HTTPForbidden(text='Origin mismatch')
    response = await handler(request)
    response.headers['Cache-Control'] = 'no-store'
    response.headers['X-Content-Type-Options'] = 'nosniff'
    response.headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"
    return response

async def index(request):
    return web.FileResponse(ROOT / 'index.html')
async def asset(request):
    name = request.match_info['name']
    if name not in {'app.js', 'style.css'}: raise web.HTTPNotFound()
    return web.FileResponse(ROOT / name)
async def login(request):
    if request.headers.get('Origin', '') != f'{request.scheme}://{request.host}': raise web.HTTPForbidden()
    data = await request.post()
    if not hmac.compare_digest(data.get('password', ''), password):
        await asyncio.sleep(0.5)
        raise web.HTTPUnauthorized(text='Incorrect password')
    token = secrets.token_urlsafe(32); sessions.add(token)
    response = web.Response(text='ok')
    response.set_cookie('watch_session', token, secure=True, httponly=True, samesite='Strict', path='/')
    return response
async def logout(request):
    sessions.discard(request.cookies.get('watch_session'))
    response = web.Response(text='ok'); response.del_cookie('watch_session', path='/'); return response
async def publish(request):
    name = request.match_info['name']
    if not name.isascii() or not all(c.isalnum() or c in '-_' for c in name) or not 1 <= len(name) <= 32: raise web.HTTPBadRequest(text='Use letters, numbers, - or _')
    if request.content_type != 'image/jpeg': raise web.HTTPUnsupportedMediaType()
    data = await request.read()
    if len(data) > MAX_FRAME or not data.startswith(b'\xff\xd8') or not data.endswith(b'\xff\xd9'): raise web.HTTPBadRequest(text='Invalid JPEG')
    if name not in cameras and len(cameras) >= 16:
        now = time.monotonic()
        for key in list(cameras):
            if now - cameras[key][1] > 30: del cameras[key]
        if len(cameras) >= 16: raise web.HTTPTooManyRequests(text='Camera limit reached')
    cameras[name] = (data, time.monotonic())
    return web.Response(status=204)
async def listing(request):
    now = time.monotonic()
    for key in list(cameras):
        if now - cameras[key][1] > 30: del cameras[key]
    return web.json_response(sorted(cameras))
async def frame(request):
    entry = cameras.get(request.match_info['name'])
    if not entry or time.monotonic() - entry[1] > 10: raise web.HTTPNotFound()
    return web.Response(body=entry[0], content_type='image/jpeg')

def make_app():
    app = web.Application(middlewares=[guard], client_max_size=MAX_FRAME)
    app.add_routes([web.get('/', index), web.get('/static/{name}', asset), web.post('/login', login), web.post('/api/logout', logout), web.get('/api/cameras', listing), web.post('/api/frame/{name}', publish), web.get('/api/frame/{name}', frame)])
    return app
if __name__ == '__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--cert', required=True); parser.add_argument('--key', required=True); parser.add_argument('--host', default='127.0.0.1'); parser.add_argument('--port', type=int, default=8443); args=parser.parse_args()
    if len(password) < 16: raise SystemExit('Set WATCH_PASSWORD to a unique password of at least 16 characters')
    context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); context.load_cert_chain(args.cert,args.key)
    web.run_app(make_app(), host=args.host, port=args.port, ssl_context=context)
