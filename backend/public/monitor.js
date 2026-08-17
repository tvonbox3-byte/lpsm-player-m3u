const token = localStorage.getItem('lpsmMonitorToken') || sessionStorage.lpsmToken || '';
const clientId = new URLSearchParams(location.search).get('client') || '';
const video = document.querySelector('#video');
const statusEl = document.querySelector('#status');
const clientEl = document.querySelector('#client');
const nowEl = document.querySelector('#now');
const hintEl = document.querySelector('#hint');
let hls = null;
let currentUrl = '';

async function request() {
  const response = await fetch(`/api/admin/monitor/${encodeURIComponent(clientId)}`, {
    cache: 'no-store',
    headers: { authorization: `Bearer ${token}` }
  });
  if (!response.ok) throw new Error('Sem autorização ou cliente indisponível.');
  return response.json();
}

function play(url) {
  if (!url || url === currentUrl) return;
  currentUrl = url;
  if (hls) { hls.destroy(); hls = null; }

  if (window.Hls && Hls.isSupported() && /\.m3u8($|\?)/i.test(url)) {
    hls = new Hls({ lowLatencyMode: true, maxBufferLength: 20 });
    hls.loadSource(url);
    hls.attachMedia(video);
    hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
  } else {
    video.src = url;
    video.play().catch(() => {});
  }
}

async function refresh() {
  try {
    const data = await request();
    clientEl.textContent = data.client?.name || 'Cliente';
    statusEl.textContent = data.client?.online ? 'ONLINE' : 'OFFLINE';

    if (data.nowPlaying?.url) {
      nowEl.textContent = `${data.nowPlaying.name || 'Conteúdo'}${data.nowPlaying.group ? ' • ' + data.nowPlaying.group : ''}`;
      hintEl.textContent = 'Prévia do mesmo stream reproduzido pelo cliente. Não é captura da tela do aparelho.';
      play(data.nowPlaying.url);
    } else {
      nowEl.textContent = 'O cliente não está reproduzindo conteúdo neste momento.';
    }
  } catch (error) {
    statusEl.textContent = 'ERRO';
    hintEl.textContent = error.message;
  }
}

refresh();
setInterval(refresh, 3000);
