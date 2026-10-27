import unittest
from aiohttp.test_utils import TestClient, TestServer
import server

class ServerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        server.password='a strong testing password'; server.sessions.clear(); server.cameras.clear()
        self.client=TestClient(TestServer(server.make_app())); await self.client.start_server()
        self.origin=str(self.client.make_url('/')).rstrip('/')
    async def asyncTearDown(self): await self.client.close()
    async def test_auth_upload_view_and_logout(self):
        self.assertEqual((await self.client.get('/api/cameras')).status,401)
        self.assertEqual((await self.client.post('/login',data={'password':'wrong'},headers={'Origin':self.origin})).status,401)
        r=await self.client.post('/login',data={'password':server.password},headers={'Origin':self.origin}); self.assertEqual(r.status,200)
        token=r.cookies['watch_session'].value
        h={'Cookie':f'watch_session={token}','Origin':self.origin}
        r=await self.client.post('/api/frame/porch',data=b'\xff\xd8test\xff\xd9',headers={**h,'Content-Type':'image/jpeg'}); self.assertEqual(r.status,204)
        r=await self.client.get('/api/cameras',headers=h); self.assertEqual(await r.json(),['porch'])
        r=await self.client.get('/api/frame/porch',headers=h); self.assertEqual(await r.read(),b'\xff\xd8test\xff\xd9')
        r=await self.client.post('/api/frame/porch',data=b'bad',headers={**h,'Content-Type':'image/jpeg'}); self.assertEqual(r.status,400)
        r=await self.client.post('/api/logout',headers={**h,'Origin':'https://evil.example'}); self.assertEqual(r.status,403)
        self.assertEqual((await self.client.post('/api/logout',headers=h)).status,200)
        self.assertEqual((await self.client.get('/api/cameras',headers=h)).status,401)
if __name__=='__main__': unittest.main()
