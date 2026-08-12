import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Store, id } from './store.js';
import { passwordHash, passwordMatches, signToken, verifyToken } from './auth.js';

const root = fileURLToPath(new URL('../', import.meta.url));
const config = {
  port: Number(process.env.PORT || 8080), dataFile: process.env.DATA_FILE || join(root, 'data/lpsm.json'),
  adminUser: process.env.ADMIN_USER || 'admin', adminPassword: process.env.ADMIN_PASSWORD || 'admin123',
  secret: process.env.TOKEN_SECRET || 'development-only-change-me'
};
const store = new Store(config.dataFile); await store.load();
// Environment variables are the source of truth for the administrator credentials.
// This also makes it possible to reset the login from Render without editing JSON files.
const adminNeedsSync = !store.data.admin
  || store.data.admin.user !== config.adminUser
  || !passwordMatches(config.adminPassword, store.data.admin.hash);
if (adminNeedsSync) {
  await store.mutate(d => {
    d.admin = { user: config.adminUser, hash: passwordHash(config.adminPassword) };
  });
}

const json = (res, status, data) => { res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' }); res.end(JSON.stringify(data)); };
const body = async req => { const chunks=[]; for await (const c of req) { chunks.push(c); if (chunks.reduce((n,x)=>n+x.length,0)>1_000_000) throw Error('Payload muito grande'); } return chunks.length ? JSON.parse(Buffer.concat(chunks)) : {}; };
const token = req => verifyToken((req.headers.authorization || '').replace(/^Bearer /, ''), config.secret);
const active = item => item.enabled !== false && (!item.expiresAt || new Date(item.expiresAt) > new Date());
const safePlaylist = p => ({ id:p.id, name:p.name, url:p.url, xmltvUrl:p.xmltvUrl || '', enabled:p.enabled, expiresAt:p.expiresAt || null });
const normalizeMac = value => String(value || '').replace(/[^0-9a-f]/gi, '').toUpperCase();

async function api(req, res, path) {
  if (req.method === 'GET' && path === '/api/health') return json(res, 200, { ok:true, service:'lpsm-control' });
  if (req.method === 'POST' && path === '/api/admin/login') {
    const b = await body(req);
    if (b.user !== store.data.admin.user || !passwordMatches(b.password, store.data.admin.hash)) return json(res, 401, { error:'Credenciais inválidas' });
    return json(res, 200, { token:signToken({ role:'admin' }, config.secret, 8*3600) });
  }
  if (req.method === 'POST' && path === '/api/device/activate') {
    const b=await body(req), macAddress=normalizeMac(b.macAddress);
    const client=store.data.clients.find(c => normalizeMac(c.macAddress || c.deviceId)===macAddress);
    if (!client || !active(client)) return json(res, 403, { error:'Dispositivo não autorizado ou expirado' });
    return json(res, 200, { token:signToken({ role:'device', clientId:client.id, macAddress }, config.secret, 30*86400) });
  }
  if (req.method === 'GET' && path === '/api/device/config') {
    const t=token(req); if (!t || t.role!=='device') return json(res,401,{error:'Não autorizado'});
    const client=store.data.clients.find(c=>c.id===t.clientId && normalizeMac(c.macAddress || c.deviceId)===t.macAddress);
    if (!client || !active(client)) return json(res,403,{error:'Cliente inativo ou expirado'});
    const allowed=new Set(client.playlistIds||[]);
    return json(res,200,{client:{name:client.name,expiresAt:client.expiresAt||null},appearance:store.data.appearance,playlists:store.data.playlists.filter(p=>allowed.has(p.id)&&active(p)).map(safePlaylist)});
  }
  const admin=token(req); if (!admin || admin.role!=='admin') return json(res,401,{error:'Não autorizado'});
  if (req.method==='GET' && path==='/api/admin/state') return json(res,200,{...store.data,admin:undefined});
  if (req.method==='PUT' && path==='/api/admin/appearance') { const b=await body(req); await store.mutate(d=>{d.appearance={bannerUrl:String(b.bannerUrl||''),wallpaperUrl:String(b.wallpaperUrl||''),supportMessage:String(b.supportMessage||'')};d.audit.unshift({id:id(),at:new Date().toISOString(),action:'appearance.update'});}); return json(res,200,store.data.appearance); }
  if (req.method==='POST' && path==='/api/admin/playlists') { const b=await body(req); if(!/^https?:\/\//i.test(b.url||'')) return json(res,400,{error:'URL HTTP(S) obrigatória'}); const p={id:id(),name:String(b.name||'Lista'),url:b.url,xmltvUrl:String(b.xmltvUrl||''),enabled:b.enabled!==false,expiresAt:b.expiresAt||null}; await store.mutate(d=>{d.playlists.push(p);d.audit.unshift({id:id(),at:new Date().toISOString(),action:'playlist.create',detail:p.name});}); return json(res,201,p); }
  if (req.method==='POST' && path==='/api/admin/clients') { const b=await body(req); const macAddress=normalizeMac(b.macAddress || b.deviceId); if(macAddress.length!==12) return json(res,400,{error:'MAC inválido: informe 12 dígitos hexadecimais'}); const defaultExpiry=new Date();defaultExpiry.setFullYear(defaultExpiry.getFullYear()+1);const playlistIds=Array.isArray(b.playlistIds)?[...b.playlistIds]:[];let createdPlaylist=null;if(b.inlinePlaylist?.url){if(!/^https?:\/\//i.test(b.inlinePlaylist.url))return json(res,400,{error:'URL M3U HTTP(S) obrigatória'});createdPlaylist={id:id(),name:String(b.inlinePlaylist.name||b.name||'Lista'),url:String(b.inlinePlaylist.url),xmltvUrl:String(b.inlinePlaylist.xmltvUrl||''),enabled:true,expiresAt:b.expiresAt||defaultExpiry.toISOString()};playlistIds.push(createdPlaylist.id)}const c={id:id(),name:String(b.name||'Cliente'),macAddress,activationCode:String(b.activationCode||Math.random().toString(36).slice(2,8)).toUpperCase(),playlistIds,enabled:b.enabled!==false,expiresAt:b.expiresAt||defaultExpiry.toISOString(),createdAt:new Date().toISOString()}; await store.mutate(d=>{if(createdPlaylist)d.playlists.push(createdPlaylist);d.clients.push(c);d.audit.unshift({id:id(),at:new Date().toISOString(),action:'client.create',detail:c.name});}); return json(res,201,{...c,createdPlaylist}); }
  const match=path.match(/^\/api\/admin\/(clients|playlists)\/([^/]+)$/);
  if (match && req.method==='PUT') { const [,kind,itemId]=match, key=kind; const b=await body(req); let updated; await store.mutate(d=>{const i=d[key].findIndex(x=>x.id===itemId);if(i<0)return; const allowed=key==='clients'?['name','macAddress','activationCode','playlistIds','enabled','expiresAt']:['name','url','xmltvUrl','enabled','expiresAt']; for(const k of allowed) if(k in b)d[key][i][k]=k==='macAddress'?normalizeMac(b[k]):b[k];updated=d[key][i];d.audit.unshift({id:id(),at:new Date().toISOString(),action:`${kind}.update`,detail:itemId});}); return updated?json(res,200,updated):json(res,404,{error:'Não encontrado'}); }
  if (match && req.method==='DELETE') { const [,kind,itemId]=match; await store.mutate(d=>{d[kind]=d[kind].filter(x=>x.id!==itemId);if(kind==='playlists')d.clients.forEach(c=>c.playlistIds=(c.playlistIds||[]).filter(x=>x!==itemId));d.audit.unshift({id:id(),at:new Date().toISOString(),action:`${kind}.delete`,detail:itemId});}); return json(res,200,{ok:true}); }
  return json(res,404,{error:'Rota não encontrada'});
}

const mime={'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.svg':'image/svg+xml'};
const server=http.createServer(async(req,res)=>{try{const url=new URL(req.url,'http://localhost');if(url.pathname.startsWith('/api/'))return await api(req,res,url.pathname);const rel=url.pathname==='/'?'index.html':url.pathname.slice(1);const file=normalize(join(root,'public',rel));if(!file.startsWith(normalize(join(root,'public')))){res.writeHead(403);return res.end();}const data=await readFile(file);res.writeHead(200,{'content-type':mime[extname(file)]||'application/octet-stream','x-content-type-options':'nosniff','content-security-policy':"default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; connect-src 'self'"});res.end(data);}catch(e){if(e.code==='ENOENT'){res.writeHead(404);res.end('Não encontrado');}else{console.error(e);json(res,500,{error:'Erro interno'});}}});
server.listen(config.port,()=>console.log(`LPSM Control em http://localhost:${config.port}`));
export { server };
