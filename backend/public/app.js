const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];

let state = {
  clients: [],
  playlists: [],
  pendingDevices: [],
  appearance: {},
  audit: []
};

let presenceRefreshInFlight =
  false;

let clientView =
  localStorage.getItem(
    'lpsmClientView'
  ) === 'cards'
    ? 'cards'
    : 'table';

let clientSearch = '';

const request = async (path, options = {}) => {
  const controller = new AbortController();
  const timeout = setTimeout(
    () => controller.abort(),
    90000
  );

  let response;

  try {
    response = await fetch(path, {
      ...options,
      signal: options.signal || controller.signal,
      headers: {
        'content-type': 'application/json',
        authorization: `Bearer ${sessionStorage.lpsmToken || ''}`,
        ...options.headers
      }
    });
  } catch (error) {
    if (error.name === 'AbortError') {
      throw Error('O servidor demorou para iniciar. Tente novamente em alguns segundos.');
    }
    throw Error('Não foi possível conectar ao servidor. Verifique a internet e tente novamente.');
  } finally {
    clearTimeout(timeout);
  }

  const raw = await response.text();
  let data = {};

  try {
    data = raw ? JSON.parse(raw) : {};
  } catch {
    data = {
      error: raw || `Erro ${response.status}`
    };
  }

  if (!response.ok) {
    const error = Error(
      data.error ||
      `Erro ${response.status}`
    );
    error.status = response.status;
    throw error;
  }

  return data;
};

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('/sw.js')
      .catch(() => {});
  });
}

const warmServer = async () => {
  const output = $('output');
  const message =
    'Servidor gratuito iniciando. Aguarde alguns segundos…';

  const notice = setTimeout(() => {
    if (output && !output.textContent.trim()) {
      output.textContent = message;
    }
  }, 500);

  try {
    const response = await fetch(
      '/api/health',
      {
        cache: 'no-store'
      }
    );

    if (
      response.ok &&
      output?.textContent === message
    ) {
      output.textContent = '';
    }
  } catch {
    if (output) {
      output.textContent =
        'Não foi possível conectar ao servidor. Tente novamente.';
    }
  } finally {
    clearTimeout(notice);
  }
};

warmServer();

const esc = value =>
  String(value ?? '').replace(
    /[&<>"']/g,
    char =>
      ({
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;'
      })[char]
  );

const normalizeMac = value =>
  String(value || '')
    .replace(
      /[^0-9a-f]/gi,
      ''
    )
    .toUpperCase()
    .slice(
      0,
      12
    );

const formatMac = value =>
  (
    normalizeMac(value)
      .match(
        /.{1,2}/g
      ) ||
    []
  ).join(':');

const fmtDate = value =>
  value
    ? new Date(
        value
      ).toLocaleDateString(
        'pt-BR'
      )
    : 'Sem expiração';

const fmtDateTime = value =>
  value
    ? new Date(
        value
      ).toLocaleString(
        'pt-BR'
      )
    : '';

const toDateInput = value =>
  value
    ? String(
        value
      ).slice(
        0,
        10
      )
    : '';

const isoEndOfDay = value =>
  value
    ? new Date(
        `${value}T23:59:59`
      ).toISOString()
    : null;

const normalizeText = value =>
  String(value || '')
    .trim()
    .toLocaleLowerCase(
      'pt-BR'
    )
    .normalize(
      'NFD'
    )
    .replace(
      /[\u0300-\u036f]/g,
      ''
    );

const normalizeServer = value =>
  String(value || '')
    .trim()
    .replace(
      /\/+$/,
      ''
    );

function buildXtreamM3uUrl(
  server,
  username,
  password
) {
  const base =
    normalizeServer(
      server
    );

  if (
    !/^https?:\/\//i.test(
      base
    )
  ) {
    throw Error(
      'Informe uma URL válida do servidor Xtream.'
    );
  }

  if (
    !username ||
    !password
  ) {
    throw Error(
      'Informe usuário e senha do Xtream.'
    );
  }

  return (
    `${base}/get.php` +
    `?username=${encodeURIComponent(username)}` +
    `&password=${encodeURIComponent(password)}` +
    `&type=m3u_plus&output=ts`
  );
}

function buildXtreamXmltvUrl(
  server,
  username,
  password
) {
  const base =
    normalizeServer(
      server
    );

  return (
    `${base}/xmltv.php` +
    `?username=${encodeURIComponent(username)}` +
    `&password=${encodeURIComponent(password)}`
  );
}

function parseXtreamUrl(
  value
) {
  try {
    const url =
      new URL(
        value
      );

    const username =
      url.searchParams.get(
        'username'
      ) || '';

    const password =
      url.searchParams.get(
        'password'
      ) || '';

    if (
      !username ||
      !password ||
      !url.pathname
        .toLowerCase()
        .endsWith(
          '/get.php'
        )
    ) {
      return null;
    }

    const pathname =
      url.pathname.replace(
        /\/get\.php$/i,
        ''
      );

    const server =
      `${url.protocol}//${url.host}${pathname}`
        .replace(
          /\/+$/,
          ''
        );

    return {
      server,
      username,
      password
    };

  } catch {
    return null;
  }
}

function sourceTypeOf(
  source
) {
  return parseXtreamUrl(
    source?.url
  )
    ? 'XTREAM'
    : 'M3U';
}


/* ==============================
   COMPATIBILIDADE CLIENTES ANTIGOS
   ============================== */

function collectClientSourceIds(
  client
) {
  const ids = [];

  const add =
    value => {

      if (
        value === null ||
        value === undefined
      ) {
        return;
      }

      if (
        typeof value ===
        'object'
      ) {

        if (
          value.id
        ) {
          add(
            value.id
          );
        }

        return;
      }

      const text =
        String(
          value
        ).trim();

      if (
        text &&
        !ids.includes(
          text
        )
      ) {
        ids.push(
          text
        );
      }
    };

  [
    'playlistIds',
    'sourceIds',
    'listIds'
  ].forEach(
    key => {

      const value =
        client?.[key];

      if (
        Array.isArray(
          value
        )
      ) {
        value.forEach(
          add
        );
      }
    }
  );

  [
    'playlistId',
    'sourceId',
    'listId'
  ].forEach(
    key =>
      add(
        client?.[key]
      )
  );

  if (
    Array.isArray(
      client?.playlists
    )
  ) {
    client.playlists
      .forEach(
        add
      );
  }

  return ids;
}

function embeddedLegacySource(
  client
) {
  if (!client) {
    return null;
  }

  if (
    Array.isArray(
      client.playlists
    )
  ) {

    const embedded =
      client.playlists.find(
        item =>
          item &&
          typeof item ===
            'object' &&
          item.url
      );

    if (embedded) {
      return embedded;
    }
  }

  const url =
    client.playlistUrl ||
    client.m3uUrl ||
    client.sourceUrl ||
    '';

  if (
    !/^https?:\/\//i.test(
      url
    )
  ) {
    return null;
  }

  return {
    id:
      client.playlistId ||
      client.sourceId ||
      '',

    name:
      `Fonte de ${
        client.name ||
        'cliente'
      }`,

    url,

    xmltvUrl:
      client.xmltvUrl ||
      client.epgUrl ||
      '',

    enabled:
      true,

    expiresAt:
      client.expiresAt ||
      null,

    __embeddedLegacy:
      true
  };
}

function primarySource(
  client
) {
  if (!client) {
    return null;
  }

  const ids =
    collectClientSourceIds(
      client
    );

  for (
    const sourceId of ids
  ) {

    const source =
      state.playlists.find(
        item =>
          String(
            item.id
          ) ===
          String(
            sourceId
          )
      );

    if (source) {
      return source;
    }
  }

  const embedded =
    embeddedLegacySource(
      client
    );

  if (embedded) {

    const byId =
      embedded.id
        ? state.playlists.find(
            item =>
              String(
                item.id
              ) ===
              String(
                embedded.id
              )
          )
        : null;

    if (byId) {
      return byId;
    }

    const byUrl =
      state.playlists.find(
        item =>
          String(
            item.url ||
            ''
          ).trim() ===
          String(
            embedded.url ||
            ''
          ).trim()
      );

    return (
      byUrl ||
      embedded
    );
  }

  const clientMac =
    normalizeMac(
      client.macAddress ||
      client.deviceId
    );

  const byOwner =
    state.playlists.filter(
      source =>
        String(
          source.clientId ||
          ''
        ) ===
        String(
          client.id ||
          ''
        ) ||

        (
          clientMac &&
          normalizeMac(
            source.macAddress ||
            source.deviceId
          ) ===
          clientMac
        )
    );

  if (
    byOwner.length ===
    1
  ) {
    return byOwner[0];
  }

  const clientName =
    normalizeText(
      client.name
    );

  const byName =
    state.playlists.filter(
      source => {

        const name =
          normalizeText(
            source.name
          );

        return (
          name ===
          clientName ||

          name ===
          `fonte de ${clientName}`
        );
      }
    );

  if (
    byName.length ===
    1
  ) {
    return byName[0];
  }

  if (
    state.clients.length ===
      1 &&
    state.playlists.length ===
      1
  ) {
    return (
      state.playlists[0]
    );
  }

  return null;
}

function clientsUsingSource(
  sourceId
) {
  return state.clients.filter(
    client => {

      const source =
        primarySource(
          client
        );

      return (
        source &&
        String(
          source.id
        ) ===
        String(
          sourceId
        )
      );
    }
  );
}

function sourceDisplayName(
  source
) {
  const clients =
    clientsUsingSource(
      source.id
    );

  if (
    clients.length ===
    1
  ) {
    return (
      `Fonte de ${
        clients[0].name
      }`
    );
  }

  if (
    clients.length >
    1
  ) {
    return (
      `Fonte compartilhada por ` +
      `${clients.length} clientes`
    );
  }

  return (
    sourceTypeOf(
      source
    ) ===
    'XTREAM'
      ? 'Fonte Xtream'
      : 'Fonte M3U'
  );
}

function sourceSummary(
  source
) {
  const xtream =
    parseXtreamUrl(
      source?.url
    );

  if (xtream) {
    return (
      `Xtream Codes • ` +
      `${xtream.server} • ` +
      `usuário ${xtream.username}`
    );
  }

  return (
    `M3U URL • ` +
    `${
      source?.url ||
      'sem URL'
    }`
  );
}


/* ==============================
   ESTILO COMPLEMENTAR
   ============================== */

function ensureExtraStyles() {

  if (
    $('#lpsmExtraStyles')
  ) {
    return;
  }

  document.head
    .insertAdjacentHTML(
      'beforeend',
      `
      <style id="lpsmExtraStyles">

        .pending-area {
          margin-bottom: 28px;
          padding-bottom: 22px;
          border-bottom:
            1px solid #24364a;
        }

        .pending-card {
          border:
            1px solid #47e6a5 !important;
        }

        .mac-code {
          color: #ffe500;
          font-size: 18px;
          font-weight: 700;
          letter-spacing: 1px;
        }

        dialog form fieldset {
          margin: 18px 0;
          padding: 16px;
          border:
            1px solid #30445b;
          border-radius: 12px;
        }

        dialog form fieldset legend {
          padding: 0 8px;
          color: #47e6a5;
          font-weight: 700;
        }

        dialog select,
        dialog input {
          width: 100%;
          box-sizing:
            border-box;
        }

        #clientDialog,
        #sourceDialog {
          width:
            min(
              620px,
              92vw
            );

          max-height:
            92vh;

          overflow:
            auto;
        }

        .source-type {
          display:
            inline-block;

          margin-top:
            6px;

          padding:
            4px 9px;

          border-radius:
            999px;

          background:
            #142438;

          color:
            #47e6a5;

          font-size:
            12px;

          font-weight:
            700;
        }

        .source-warning {
          margin:
            10px 0;

          padding:
            10px 12px;

          border:
            1px solid #856404;

          border-radius:
            8px;

          color:
            #ffe69c;

          background:
            #332701;
        }

        .current-source-preview {
          margin: 0 0 14px;
          padding: 13px 14px;
          border: 1px solid #2f8066;
          border-radius: 10px;
          background: #0d2a24;
          color: #dfffee;
          line-height: 1.45;
        }

        .current-source-preview strong,
        .current-source-preview span {
          display: block;
        }

        .current-source-preview strong {
          margin-bottom: 3px;
          color: #47e6a5;
        }

        .current-source-preview span {
          color: #a9c7bc;
          font-size: 12px;
          word-break: break-word;
        }

        .mac-help {
          margin-top:
            6px;

          font-size:
            12px;

          opacity:
            .8;
        }

        .client-statuses {
          display:
            flex;

          flex-wrap:
            wrap;

          justify-content:
            flex-end;

          gap:
            7px;
        }

        .pill.presence.online {
          background:
            #0b6b45;

          color:
            #d8ffed;

          box-shadow:
            0 0 0 1px #39db93aa;
        }

        .pill.presence.offline {
          background:
            #6b2430;

          color:
            #ffdce2;

          box-shadow:
            0 0 0 1px #ff7189aa;
        }

        main {
          max-width: 1500px;
        }

        .client-toolbar {
          display: flex;
          align-items: end;
          gap: 12px;
          margin-bottom: 16px;
          padding: 14px;
        }

        .client-search-label {
          flex: 1;
          margin: 0;
        }

        .client-search-label span {
          display: block;
          margin-bottom: 7px;
          color: #aab7c7;
          font-size: 12px;
          font-weight: 700;
          letter-spacing: .08em;
          text-transform: uppercase;
        }

        #clientSearch {
          min-height: 45px;
        }

        #clientViewToggle {
          min-width: 112px;
          min-height: 45px;
          white-space: nowrap;
        }

        .client-list.cards-view {
          display: grid;
          grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
          gap: 15px;
        }

        .client-list.table-view {
          display: block;
        }

        .client-table-wrap {
          overflow: auto;
          border: 1px solid #26394f;
          border-radius: 16px;
          background: #0e1928;
          box-shadow: 0 18px 45px rgba(0, 0, 0, .18);
        }

        .client-table {
          width: 100%;
          min-width: 1220px;
          border-collapse: collapse;
        }

        .client-table th,
        .client-table td {
          padding: 15px 14px;
          border-bottom: 1px solid #26394f;
          text-align: left;
          vertical-align: middle;
        }

        .client-table th {
          position: sticky;
          top: 0;
          z-index: 1;
          background: #111d2e;
          color: #9dadc0;
          font-size: 11px;
          letter-spacing: .09em;
          text-transform: uppercase;
          white-space: nowrap;
        }

        .client-table tbody tr {
          transition: background .15s ease;
        }

        .client-table tbody tr:hover {
          background: #14243a;
        }

        .client-table tbody tr:last-child td {
          border-bottom: 0;
        }

        .client-table .client-name {
          display: block;
          min-width: 150px;
          color: #f4f7fb;
          font-weight: 800;
        }

        .client-table .client-mac,
        .client-table .client-date {
          white-space: nowrap;
        }

        .client-table .client-source-name {
          display: block;
          max-width: 250px;
          color: #e7edf5;
          font-weight: 700;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .client-table .client-source-summary,
        .client-table .client-secondary {
          display: block;
          max-width: 250px;
          margin-top: 4px;
          color: #91a1b4;
          font-size: 12px;
          overflow: hidden;
          text-overflow: ellipsis;
          white-space: nowrap;
        }

        .client-table-actions {
          display: flex;
          align-items: center;
          gap: 7px;
          min-width: max-content;
        }

        .client-table-actions button {
          min-height: 36px;
          padding: 8px 11px;
          font-size: 12px;
          white-space: nowrap;
        }

        .client-empty {
          margin: 0;
          padding: 26px;
          border: 1px dashed #30445b;
          border-radius: 14px;
          color: #9aa8b9;
          text-align: center;
        }

        @media (max-width: 760px) {
          .client-toolbar {
            align-items: stretch;
            flex-direction: column;
          }

          #clientViewToggle {
            width: 100%;
          }

          .client-list.cards-view {
            grid-template-columns: 1fr;
          }
        }

        .hidden {
          display:
            none !important;
        }

      </style>
      `
    );
}

function ensurePendingArea() {

  if (
    $('#pendingDevices')
  ) {
    return;
  }

  $('#clientList')
    .insertAdjacentHTML(
      'beforebegin',
      `
      <section
        id="pendingDevices"
        class="pending-area"
      >

        <div class="section-title">

          <div>

            <h2>
              MACs disponíveis para cadastro
            </h2>

            <p class="meta">
              Quando o aplicativo abrir em um aparelho novo,
              o MAC disponível aparece aqui como atalho.
              Você também pode cadastrar um MAC
              manualmente em Novo cliente.
            </p>

          </div>

          <button
            id="pendingVisibility"
            type="button"
            class="ghost"
          >
            Ocultar MACs
          </button>

        </div>

        <div
          id="pendingList"
          class="grid"
        ></div>

      </section>
      `
    );

  const button =
    $('#pendingVisibility');

  const list =
    $('#pendingList');

  const applyPendingVisibility = () => {
    const hidden =
      localStorage.getItem('lpsmPendingHidden') === '1';

    list?.classList.toggle('hidden', hidden);

    if (button) {
      button.textContent =
        hidden
          ? 'Mostrar MACs aguardando'
          : 'Ocultar MACs aguardando';
    }
  };

  if (button) {
    button.onclick = () => {
      const hidden =
        localStorage.getItem('lpsmPendingHidden') === '1';

      localStorage.setItem(
        'lpsmPendingHidden',
        hidden ? '0' : '1'
      );

      applyPendingVisibility();
    };
  }

  applyPendingVisibility();
}

function ensureClientSourceWarning() {

  const fieldset =
    $('#clientForm fieldset');

  if (
    !fieldset ||
    $('#clientSourceWarning')
  ) {
    return;
  }

  fieldset
    .insertAdjacentHTML(
      'afterbegin',
      `
      <div
        id="clientSourceWarning"
        class="source-warning hidden"
      ></div>
      `
    );
}

function ensureMacHelp() {

  const label =
    $('#fixedMacLabel');

  if (
    !label ||
    $('#macHelp')
  ) {
    return;
  }

  label
    .insertAdjacentHTML(
      'beforeend',
      `
      <div
        id="macHelp"
        class="mac-help"
      >
        Você pode digitar ou alterar
        o MAC manualmente.
      </div>
      `
    );
}


/* ==============================
   CARREGAR
   ============================== */

async function load() {

  state =
    await request(
      '/api/admin/state'
    );

  state.pendingDevices =
    state.pendingDevices ||
    [];

  state.clients =
    state.clients ||
    [];

  state.playlists =
    state.playlists ||
    [];

  state.appearance =
    state.appearance ||
    {};

  state.audit =
    state.audit ||
    [];

  render();
}


/*
 * Atualiza ONLINE/OFFLINE automaticamente.
 * Formulários abertos não são interrompidos.
 */
setInterval(
  async () => {

    if (
      !sessionStorage.lpsmToken ||
      document.hidden ||
      presenceRefreshInFlight ||
      document.querySelector(
        'dialog[open]'
      )
    ) {
      return;
    }

    presenceRefreshInFlight =
      true;

    try {
      await load();
    } catch (error) {

      if (
        error.status ===
        401
      ) {
        return;
      }

      console.debug(
        'Presença temporariamente indisponível',
        error.message
      );

    } finally {

      presenceRefreshInFlight =
        false;
    }
  },
  2_000
);

function clientAuthorizationMarkup(
  client
) {
  return `
    <span
      class="pill ${
        client.enabled === false
          ? 'off'
          : ''
      }"
      title="Autorização definida por você"
    >
      ${
        client.enabled === false
          ? 'DESATIVADO'
          : 'ATIVO'
      }
    </span>
  `;
}

function clientPresenceMarkup(
  client
) {
  return `
    <span
      class="pill presence ${
        client.online === true
          ? 'online'
          : 'offline'
      }"
      title="Presença enviada pelo aplicativo"
    >
      ${
        client.online === true
          ? 'ONLINE'
          : 'OFFLINE'
      }
    </span>
  `;
}

function clientActionsMarkup(
  client,
  compact = false
) {
  const id =
    esc(
      client.id
    );

  return `
    <div class="${
      compact
        ? 'client-table-actions'
        : 'actions'
    }">
      ${
        client.online === true &&
        client.nowPlaying?.name
          ? `
            <button
              class="ghost monitor-client"
              data-id="${id}"
            >
              Ver agora
            </button>
          `
          : ''
      }

      <button
        class="ghost edit-client"
        data-id="${id}"
      >
        Editar
      </button>

      <button
        class="ghost toggle"
        data-kind="clients"
        data-id="${id}"
        data-enabled="${
          client.enabled === false
        }"
      >
        ${
          client.enabled === false
            ? 'Ativar'
            : 'Desativar'
        }
      </button>

      <button
        class="danger delete"
        data-kind="clients"
        data-id="${id}"
      >
        Excluir
      </button>
    </div>
  `;
}

function clientMatchesSearch(
  client,
  source
) {
  const query =
    normalizeText(
      clientSearch
    );

  if (!query) {
    return true;
  }

  const searchable = [
    client.name,
    formatMac(
      client.macAddress ||
      client.deviceId
    ),
    source
      ? sourceDisplayName(
          source
        )
      : '',
    source
      ? sourceSummary(
          source
        )
      : '',
    source
      ? sourceTypeOf(
          source
        )
      : '',
    client.nowPlaying?.name
  ]
    .map(normalizeText)
    .join(' ');

  return searchable.includes(
    query
  );
}

function clientCardMarkup(
  client
) {
  const source =
    primarySource(
      client
    );

  return `
    <article class="card item">
      <div class="row">
        <h3>
          ${esc(client.name)}
        </h3>

        <div class="client-statuses">
          ${clientAuthorizationMarkup(client)}
          ${clientPresenceMarkup(client)}
        </div>
      </div>

      <div class="meta">
        MAC:
        ${esc(
          formatMac(
            client.macAddress ||
            client.deviceId
          )
        )}

        <br>

        Expira:
        ${esc(fmtDate(client.expiresAt))}

        <br>

        Último contato:
        ${
          client.lastSeenAt
            ? esc(
                fmtDateTime(
                  client.lastSeenAt
                )
              )
            : 'Ainda não informado'
        }

        <br>

        Assistindo agora:
        ${
          client.nowPlaying?.name
            ? `<strong>${esc(client.nowPlaying.name)}</strong>`
            : 'Nada informado'
        }

        <br>

        ${
          source
            ? `
              <span class="source-type">
                ${esc(sourceTypeOf(source))}
              </span>

              <strong>
                ${esc(sourceDisplayName(source))}
              </strong>

              ${esc(sourceSummary(source))}
            `
            : `
              <span class="source-type">
                SEM FONTE VINCULADA
              </span>
            `
        }
      </div>

      ${clientActionsMarkup(client)}
    </article>
  `;
}

function clientTableRowMarkup(
  client
) {
  const source =
    primarySource(
      client
    );

  return `
    <tr>
      <td>
        <span class="client-name">
          ${esc(client.name)}
        </span>
      </td>

      <td class="client-mac">
        ${esc(
          formatMac(
            client.macAddress ||
            client.deviceId
          )
        )}
      </td>

      <td>
        ${
          source
            ? `
              <span class="client-source-name">
                ${esc(sourceDisplayName(source))}
              </span>
              <span class="client-source-summary">
                ${esc(sourceTypeOf(source))} ·
                ${esc(sourceSummary(source))}
              </span>
            `
            : `
              <span class="client-secondary">
                Sem fonte vinculada
              </span>
            `
        }
      </td>

      <td>
        ${clientAuthorizationMarkup(client)}
      </td>

      <td>
        ${clientPresenceMarkup(client)}
      </td>

      <td class="client-date">
        ${esc(fmtDate(client.expiresAt))}
      </td>

      <td>
        ${
          client.lastSeenAt
            ? esc(
                fmtDateTime(
                  client.lastSeenAt
                )
              )
            : 'Ainda não informado'
        }
      </td>

      <td>
        ${
          client.nowPlaying?.name
            ? esc(client.nowPlaying.name)
            : '—'
        }
      </td>

      <td>
        ${clientActionsMarkup(client, true)}
      </td>
    </tr>
  `;
}

function renderClientList() {
  const list =
    $('#clientList');

  const toggle =
    $('#clientViewToggle');

  const searchInput =
    $('#clientSearch');

  if (
    !list ||
    !toggle
  ) {
    return;
  }

  if (
    searchInput &&
    searchInput.value !==
      clientSearch
  ) {
    searchInput.value =
      clientSearch;
  }

  toggle.textContent =
    clientView === 'cards'
      ? 'Lista'
      : 'Cards';

  toggle.title =
    clientView === 'cards'
      ? 'Mudar para lista'
      : 'Mudar para cards';

  toggle.setAttribute(
    'aria-pressed',
    String(
      clientView === 'cards'
    )
  );

  list.className =
    `client-list ${clientView}-view`;

  const clients =
    state.clients.filter(
      client =>
        clientMatchesSearch(
          client,
          primarySource(
            client
          )
        )
    );

  if (!clients.length) {
    list.innerHTML = `
      <p class="client-empty">
        ${
          normalizeText(clientSearch)
            ? 'Nenhum cliente encontrado.'
            : 'Nenhum cliente cadastrado.'
        }
      </p>
    `;
    return;
  }

  if (clientView === 'cards') {
    list.innerHTML =
      clients
        .map(clientCardMarkup)
        .join('');
    return;
  }

  list.innerHTML = `
    <div class="client-table-wrap">
      <table class="client-table">
        <thead>
          <tr>
            <th>Cliente</th>
            <th>MAC</th>
            <th>Fonte</th>
            <th>Autorização</th>
            <th>Presença</th>
            <th>Expiração</th>
            <th>Último contato</th>
            <th>Assistindo</th>
            <th>Ações</th>
          </tr>
        </thead>
        <tbody>
          ${clients
            .map(clientTableRowMarkup)
            .join('')}
        </tbody>
      </table>
    </div>
  `;
}


/* ==============================
   RENDER
   ============================== */

function render() {

  ensureExtraStyles();
  ensurePendingArea();
  ensureClientSourceWarning();
  ensureMacHelp();

  const activeClients =
    state.clients.filter(
      client =>
        client.enabled !==
        false
    ).length;

  const activeSources =
    state.playlists.filter(
      source =>
        source.enabled !==
        false
    ).length;

  const onlineClients =
    state.clients.filter(
      client =>
        client.online ===
        true
    ).length;

  $('#summary')
    .textContent =
      `${activeClients} clientes ativos · ` +
      `${onlineClients} online agora · ` +
      `${activeSources} fontes ativas · ` +
      `${state.pendingDevices.length} MAC(s) aguardando`;

  $('#pendingList')
    .innerHTML =
      state.pendingDevices
        .map(
          device => `
            <article
              class="card item pending-card"
            >

              <div class="row">

                <h3>
                  Novo aparelho
                </h3>

                <span class="pill">
                  AGUARDANDO
                </span>

              </div>

              <div class="meta">

                MAC:

                <div class="mac-code">
                  ${esc(
                    formatMac(
                      device.macAddress
                    )
                  )}
                </div>

                Detectado:
                ${esc(
                  fmtDateTime(
                    device.firstSeenAt
                  )
                )}

                <br>

                Último contato:
                ${esc(
                  fmtDateTime(
                    device.lastSeenAt
                  )
                )}

              </div>

              <div class="actions">

                <button
                  class="authorize-mac"
                  data-mac="${esc(
                    formatMac(
                      device.macAddress
                    )
                  )}"
                >
                  Cadastrar cliente
                </button>

              </div>

            </article>
          `
        )
        .join('') ||

      `
        <p>
          Nenhum MAC aguardando.
        </p>
      `;

  renderClientList();

  $('#playlistList')
    .innerHTML =
      state.playlists
        .map(
          source => {

            const usedBy =
              clientsUsingSource(
                source.id
              );

            return `
              <article
                class="card item"
              >

                <div class="row">

                  <h3>
                    ${esc(
                      sourceDisplayName(
                        source
                      )
                    )}
                  </h3>

                  <span
                    class="pill ${
                      source.enabled ===
                      false
                        ? 'off'
                        : ''
                    }"
                  >
                    ${
                      source.enabled ===
                      false
                        ? 'INATIVA'
                        : 'ATIVA'
                    }
                  </span>

                </div>

                <div class="meta">

                  <span
                    class="source-type"
                  >
                    ${esc(
                      sourceTypeOf(
                        source
                      )
                    )}
                  </span>

                  ${esc(
                    sourceSummary(
                      source
                    )
                  )}

                  <br>

                  Expira:
                  ${fmtDate(
                    source.expiresAt
                  )}

                  <br>

                  Vinculada a
                  ${usedBy.length}
                  cliente(s)

                </div>

                <div class="actions">

                  <button
                    class="ghost edit-source"
                    data-id="${source.id}"
                  >
                    Editar conexão
                  </button>

                  <button
                    class="ghost toggle"
                    data-kind="playlists"
                    data-id="${source.id}"
                    data-enabled="${
                      source.enabled ===
                      false
                    }"
                  >
                    ${
                      source.enabled ===
                      false
                        ? 'Ativar'
                        : 'Desativar'
                    }
                  </button>

                  <button
                    class="danger delete"
                    data-kind="playlists"
                    data-id="${source.id}"
                  >
                    Excluir
                  </button>

                </div>

              </article>
            `;
          }
        )
        .join('') ||

      `
        <p>
          Nenhuma fonte cadastrada.
        </p>
      `;

  for (
    const [
      key,
      value
    ] of Object.entries(
      state.appearance
    )
  ) {

    const element =
      $(
        `#appearanceForm [name=${key}]`
      );

    if (element) {
      element.value =
        value ||
        '';
    }
  }

  $('#auditList')
    .innerHTML =
      state.audit
        .slice(
          0,
          50
        )
        .map(
          audit => `
            <div class="log">

              <time>
                ${
                  new Date(
                    audit.at
                  )
                    .toLocaleString(
                      'pt-BR'
                    )
                }
              </time>

              ${esc(
                audit.action
              )}

              ${esc(
                audit.detail ||
                ''
              )}

            </div>
          `
        )
        .join('') ||

      'Sem atividade.';
}


/* ==============================
   MAC PENDENTE / MANUAL
   ============================== */

function fillPendingMacOptions(
  selectedMac = ''
) {

  const select =
    $(
      '#clientForm [name=pendingMac]'
    );

  if (!select) {
    return;
  }

  const selected =
    formatMac(
      selectedMac
    );

  select.innerHTML =
    `
      <option value="">
        Digitar MAC manualmente
      </option>
    ` +

    state.pendingDevices
      .map(
        device => {

          const mac =
            formatMac(
              device.macAddress
            );

          return `
            <option
              value="${esc(mac)}"
              ${
                mac ===
                selected
                  ? 'selected'
                  : ''
              }
            >
              ${esc(mac)}
            </option>
          `;
        }
      )
      .join('');
}


/* ==============================
   CAMPOS M3U / XTREAM
   ============================== */

function toggleSourceFields(
  prefix,
  type
) {

  const m3u =
    $(
      `#${prefix}M3uFields`
    );

  const xtream =
    $(
      `#${prefix}XtreamFields`
    );

  if (m3u) {

    m3u.classList
      .toggle(
        'hidden',
        type !==
        'M3U'
      );
  }

  if (xtream) {

    xtream.classList
      .toggle(
        'hidden',
        type !==
        'XTREAM'
      );
  }
}

function clearSourceFields(
  form
) {

  [
    'm3uUrl',
    'xmltvUrl',
    'xtreamServer',
    'xtreamUsername',
    'xtreamPassword'
  ].forEach(
    name => {

      if (
        form.elements[
          name
        ]
      ) {

        form.elements[
          name
        ].value =
          '';
      }
    }
  );
}

function fillSourceFields(
  form,
  source,
  prefix
) {

  clearSourceFields(
    form
  );

  const type =
    sourceTypeOf(
      source
    );

  if (
    form.elements
      .sourceType
  ) {

    form.elements
      .sourceType
      .value =
      type;
  }

  toggleSourceFields(
    prefix,
    type
  );

  if (!source) {
    return;
  }

  const xtream =
    parseXtreamUrl(
      source.url
    );

  if (xtream) {

    form.elements
      .xtreamServer
      .value =
      xtream.server;

    form.elements
      .xtreamUsername
      .value =
      xtream.username;

    form.elements
      .xtreamPassword
      .value =
      xtream.password;

    return;
  }

  form.elements
    .m3uUrl
    .value =
    source.url ||
    '';

  form.elements
    .xmltvUrl
    .value =
    source.xmltvUrl ||
    '';
}

function sourcePayloadFromForm(
  form
) {

  const type =
    form.elements
      .sourceType
      .value;

  if (
    type ===
    'XTREAM'
  ) {

    const server =
      form.elements
        .xtreamServer
        .value
        .trim();

    const username =
      form.elements
        .xtreamUsername
        .value
        .trim();

    const password =
      form.elements
        .xtreamPassword
        .value;

    return {

      sourceType:
        'XTREAM',

      xtreamServer:
        normalizeServer(
          server
        ),

      xtreamUsername:
        username,

      xtreamPassword:
        password,

      url:
        buildXtreamM3uUrl(
          server,
          username,
          password
        ),

      xmltvUrl:
        buildXtreamXmltvUrl(
          server,
          username,
          password
        )
    };
  }

  const url =
    form.elements
      .m3uUrl
      .value
      .trim();

  if (
    !/^https?:\/\//i.test(
      url
    )
  ) {

    throw Error(
      'Informe a URL da lista M3U.'
    );
  }

  return {

    sourceType:
      'M3U',

    xtreamServer:
      '',

    xtreamUsername:
      '',

    xtreamPassword:
      '',

    url,

    xmltvUrl:
      form.elements
        .xmltvUrl
        .value
        .trim()
  };
}

function showSourceWarning(
  message = ''
) {

  const box =
    $('#clientSourceWarning');

  if (!box) {
    return;
  }

  box.textContent =
    message;

  box.classList
    .toggle(
      'hidden',
      !message
    );
}

function showCurrentClientSource(
  source
) {
  const preview =
    $('#currentClientSource');

  if (!preview) {
    return;
  }

  if (!source) {
    preview.innerHTML = '';
    preview.classList.add(
      'hidden'
    );
    return;
  }

  preview.innerHTML = `
    <strong>
      Lista atual: ${esc(sourceDisplayName(source))}
    </strong>
    <span>
      ${esc(sourceTypeOf(source))} ·
      ${esc(sourceSummary(source))}
    </span>
  `;

  preview.classList.remove(
    'hidden'
  );
}


/* ==============================
   ABRIR CLIENTE
   ============================== */

function openClientDialog(
  client = null,
  pendingMac = ''
) {

  const form =
    $('#clientForm');

  form.reset();

  showSourceWarning(
    ''
  );

  form.dataset.editId =
    client?.id ||
    '';

  form
    .querySelector(
      'h2'
    )
    .textContent =
    client
      ? 'Editar cliente'
      : 'Novo cliente';

  form
    .querySelector(
      'button[value=default]'
    )
    .textContent =
    client
      ? 'Salvar alterações'
      : 'Criar cliente';

  const expiry =
    new Date();

  expiry.setFullYear(
    expiry.getFullYear() +
    1
  );

  form.elements
    .name
    .value =
    client?.name ||
    '';

  form.elements
    .enabled
    .value =
    String(
      client?.enabled !==
      false
    );

  form.elements
    .expiresAt
    .value =
    client?.expiresAt
      ? toDateInput(
          client.expiresAt
        )
      : expiry
          .toISOString()
          .slice(
            0,
            10
          );

  /*
   * BUSCA A LISTA QUE JÁ EXISTE.
   */
  const source =
    client
      ? primarySource(
          client
        )
      : null;

  showCurrentClientSource(
    source
  );

  if (
    form.elements
      .sourceId
  ) {

    form.elements
      .sourceId
      .value =
      source?.id ||
      '';
  }

  const pendingLabel =
    $('#pendingMacLabel');

  const macLabel =
    $('#fixedMacLabel');

  const macInput =
    form.elements
      .macAddress;

  /*
   * MAC AGORA É EDITÁVEL.
   */
  if (macLabel) {

    macLabel.classList
      .remove(
        'hidden'
      );
  }

  macInput.readOnly =
    false;

  if (client) {

    if (pendingLabel) {

      pendingLabel.classList
        .add(
          'hidden'
        );
    }

    macInput.value =
      formatMac(
        client.macAddress ||
        client.deviceId
      );

    if (
      !source &&
      state.playlists.length
    ) {

      showSourceWarning(
        'Não encontrei automaticamente a fonte deste cliente antigo. A conexão ficou vazia para não alterar outra lista por engano.'
      );
    }

  } else {

    if (pendingLabel) {

      pendingLabel.classList
        .remove(
          'hidden'
        );
    }

    fillPendingMacOptions(
      pendingMac
    );

    macInput.value =
      formatMac(
        pendingMac
      );
  }

  /*
   * PREENCHE M3U OU XTREAM
   * COM OS DADOS EXISTENTES.
   */
  fillSourceFields(
    form,
    source,
    'client'
  );

  $('#clientDialog')
    .showModal();
}


/* ==============================
   ABRIR FONTE
   ============================== */

function openSourceDialog(
  source
) {

  const form =
    $('#sourceForm');

  form.reset();

  form.elements
    .sourceId
    .value =
    source.id;

  form.elements
    .enabled
    .value =
    String(
      source.enabled !==
      false
    );

  form.elements
    .expiresAt
    .value =
    toDateInput(
      source.expiresAt
    );

  fillSourceFields(
    form,
    source,
    'source'
  );

  $('#sourceDialog')
    .showModal();
}


/* ==============================
   SALVAR FONTE EXISTENTE
   ============================== */

async function saveExistingSource(
  source,
  payload
) {

  return request(
    `/api/admin/playlists/${source.id}`,
    {
      method:
        'PUT',

      body:
        JSON.stringify({

          url:
            payload.url,

          xmltvUrl:
            payload.xmltvUrl,

          sourceType:
            payload.sourceType,

          xtreamServer:
            payload.xtreamServer,

          xtreamUsername:
            payload.xtreamUsername,

          xtreamPassword:
            payload.xtreamPassword
        })
    }
  );
}


/* ==============================
   CRIAR FONTE PARA CLIENTE ANTIGO
   ============================== */

async function createSourceForClient(
  client,
  payload
) {

  return request(
    '/api/admin/playlists',
    {
      method:
        'POST',

      body:
        JSON.stringify({

          name:
            `Fonte de ${client.name}`,

          url:
            payload.url,

          xmltvUrl:
            payload.xmltvUrl,

          sourceType:
            payload.sourceType,

          xtreamServer:
            payload.xtreamServer,

          xtreamUsername:
            payload.xtreamUsername,

          xtreamPassword:
            payload.xtreamPassword,

          enabled:
            true,

          expiresAt:
            client.expiresAt ||
            null
        })
    }
  );
}


/* ==============================
   LOGIN
   ============================== */

$('#loginForm')
  .onsubmit =
  async event => {

    event.preventDefault();

    try {

      const data =
        Object.fromEntries(
          new FormData(
            event.target
          )
        );

      sessionStorage.lpsmToken =
        (
          await request(
            '/api/admin/login',
            {
              method:
                'POST',

              body:
                JSON.stringify(
                  data
                )
            }
          )
        ).token;

      await enter();

    } catch (
      error
    ) {

      $('output')
        .textContent =
        error.message;
    }
  };

async function enter() {

  try {

    await load();

    $('#login')
      .classList.add(
        'hidden'
      );

    $('#dashboard')
      .classList.remove(
        'hidden'
      );

    $('#logout')
      .classList.remove(
        'hidden'
      );

  } catch (error) {

    if (error.status === 401) {
      sessionStorage
        .removeItem(
          'lpsmToken'
        );
    } else {
      $('output').textContent =
        error.message;
    }
  }
}

if (
  sessionStorage.lpsmToken
) {
  enter();
}

$('#logout')
  .onclick =
  () => {

    sessionStorage
      .removeItem(
        'lpsmToken'
      );

    location.reload();
  };

$('#refresh')
  .onclick =
  load;

$('#clientViewToggle')
  .onclick =
  () => {
    clientView =
      clientView === 'table'
        ? 'cards'
        : 'table';

    localStorage.setItem(
      'lpsmClientView',
      clientView
    );

    renderClientList();
  };

$('#clientSearch')
  .addEventListener(
    'input',
    event => {
      clientSearch =
        event.target.value;

      renderClientList();
    }
  );


/* ==============================
   ABAS
   ============================== */

$$('nav button')
  .forEach(
    button => {

      button.onclick =
        () => {

          $$('nav button')
            .forEach(
              item =>
                item.classList
                  .toggle(
                    'active',
                    item ===
                    button
                  )
            );

          $$('.tab')
            .forEach(
              tab =>
                tab.classList
                  .toggle(
                    'hidden',
                    tab.id !==
                    button.dataset.tab
                  )
            );
        };
    }
  );


/* ==============================
   TROCAR M3U / XTREAM
   ============================== */

$('#clientSourceType')
  .onchange =
  event => {

    toggleSourceFields(
      'client',
      event.target.value
    );
  };

$('#sourceType')
  .onchange =
  event => {

    toggleSourceFields(
      'source',
      event.target.value
    );
  };


/* ==============================
   FORMATAÇÃO DO MAC
   ============================== */

const clientMacInput =
  $(
    '#clientForm [name=macAddress]'
  );

if (
  clientMacInput
) {

  clientMacInput
    .addEventListener(
      'input',
      () => {

        clientMacInput.value =
          formatMac(
            clientMacInput.value
          );
      }
    );
}


/* ==============================
   MAC DA FILA PREENCHE CAMPO
   ============================== */

const pendingMacSelect =
  $(
    '#clientForm [name=pendingMac]'
  );

if (
  pendingMacSelect
) {

  pendingMacSelect
    .addEventListener(
      'change',
      () => {

        if (
          pendingMacSelect.value
        ) {

          clientMacInput.value =
            formatMac(
              pendingMacSelect.value
            );
        }
      }
    );
}


/* ==============================
   NOVO CLIENTE
   ============================== */

$$('[data-open]')
  .forEach(
    button => {

      button.onclick =
        () => {

          if (
            button.dataset.open ===
            'clientDialog'
          ) {

            /*
             * NÃO PRECISA MAIS
             * TER MAC NA ESPERA.
             */
            openClientDialog();
          }
        };
    }
  );


/* ==============================
   CANCELAR
   ============================== */

$$('.close')
  .forEach(
    button => {

      button.onclick =
        () =>

          button
            .closest(
              'dialog'
            )
            .close();
    }
  );


/* ==============================
   SALVAR CLIENTE
   ============================== */

$('#clientForm')
  .onsubmit =
  async event => {

    event.preventDefault();

    const form =
      event.target;

    const submit =
      event.submitter;

    const editId =
      form.dataset.editId;

    submit.disabled =
      true;

    try {

      const name =
        form.elements
          .name
          .value
          .trim();

      const macAddress =
        formatMac(
          form.elements
            .macAddress
            .value
        );

      const enabled =
        form.elements
          .enabled
          .value ===
        'true';

      const expiresAt =
        isoEndOfDay(
          form.elements
            .expiresAt
            .value
        );

      const sourcePayload =
        sourcePayloadFromForm(
          form
        );

      if (!name) {

        throw Error(
          'Informe o nome do cliente.'
        );
      }

      if (
        normalizeMac(
          macAddress
        ).length !==
        12
      ) {

        throw Error(
          'Informe um MAC válido com 12 caracteres.'
        );
      }


      /* ==========================
         CLIENTE NOVO
         ========================== */

      if (!editId) {

        await request(
          '/api/admin/clients',
          {
            method:
              'POST',

            body:
              JSON.stringify({

                name,

                macAddress,

                enabled,

                expiresAt,

                playlistIds:
                  [],

                inlinePlaylist: {

                  name:
                    `Fonte de ${name}`,

                  url:
                    sourcePayload.url,

                  xmltvUrl:
                    sourcePayload.xmltvUrl,

                  sourceType:
                    sourcePayload.sourceType,

                  xtreamServer:
                    sourcePayload.xtreamServer,

                  xtreamUsername:
                    sourcePayload.xtreamUsername,

                  xtreamPassword:
                    sourcePayload.xtreamPassword
                }
              })
          }
        );

      } else {


        /* ==========================
           EDITAR CLIENTE
           ========================== */

        const client =
          state.clients.find(
            item =>
              item.id ===
              editId
          );

        if (!client) {

          throw Error(
            'Cliente não encontrado.'
          );
        }

        let source =
          primarySource(
            client
          );

        let sourceId =
          source?.id ||
          '';

        /*
         * SE JÁ TEM LISTA,
         * ALTERA A MESMA.
         */
        if (
          source?.id &&
          !source.__embeddedLegacy
        ) {

          await saveExistingSource(
            source,
            sourcePayload
          );

        } else {

          /*
           * CLIENTE MUITO ANTIGO
           * SEM VÍNCULO.
           */
          const created =
            await createSourceForClient(
              {
                ...client,
                name,
                expiresAt
              },

              sourcePayload
            );

          sourceId =
            created.id;
        }

        const currentIds =
          collectClientSourceIds(
            client
          );

        const playlistIds =
          sourceId
            ? [
                ...new Set([
                  sourceId,
                  ...currentIds
                ])
              ]
            : currentIds;

        /*
         * MAC É SALVO MESMO
         * QUE TENHA SIDO ALTERADO.
         */
        await request(
          `/api/admin/clients/${editId}`,
          {
            method:
              'PUT',

            body:
              JSON.stringify({

                name,

                macAddress,

                enabled,

                expiresAt,

                playlistIds
              })
          }
        );
      }

      form
        .closest(
          'dialog'
        )
        .close();

      await load();

      alert(
        editId
          ? 'Cliente atualizado com sucesso.'
          : 'Cliente criado com sucesso.'
      );

    } catch (
      error
    ) {

      alert(
        `Não foi possível salvar: ${error.message}`
      );

    } finally {

      submit.disabled =
        false;
    }
  };


/* ==============================
   SALVAR FONTE
   ============================== */

$('#sourceForm')
  .onsubmit =
  async event => {

    event.preventDefault();

    const form =
      event.target;

    const submit =
      event.submitter;

    submit.disabled =
      true;

    try {

      const sourceId =
        form.elements
          .sourceId
          .value;

      const payload =
        sourcePayloadFromForm(
          form
        );

      await request(
        `/api/admin/playlists/${sourceId}`,
        {
          method:
            'PUT',

          body:
            JSON.stringify({

              url:
                payload.url,

              xmltvUrl:
                payload.xmltvUrl,

              sourceType:
                payload.sourceType,

              xtreamServer:
                payload.xtreamServer,

              xtreamUsername:
                payload.xtreamUsername,

              xtreamPassword:
                payload.xtreamPassword,

              enabled:
                form.elements
                  .enabled
                  .value ===
                'true',

              expiresAt:
                isoEndOfDay(
                  form.elements
                    .expiresAt
                    .value
                )
            })
        }
      );

      form
        .closest(
          'dialog'
        )
        .close();

      await load();

      alert(
        'Fonte atualizada com sucesso.'
      );

    } catch (
      error
    ) {

      alert(
        `Não foi possível salvar a fonte: ${error.message}`
      );

    } finally {

      submit.disabled =
        false;
    }
  };


/* ==============================
   APARÊNCIA
   ============================== */

$('#appearanceForm')
  .onsubmit =
  async event => {

    event.preventDefault();

    await request(
      '/api/admin/appearance',
      {
        method:
          'PUT',

        body:
          JSON.stringify(
            Object.fromEntries(
              new FormData(
                event.target
              )
            )
          )
      }
    );

    await load();
  };


/* ==============================
   BOTÕES DOS CARDS
   ============================== */

document.body.onclick =
  async event => {

    const authorize =
      event.target.closest(
        '.authorize-mac'
      );

    if (authorize) {

      openClientDialog(
        null,
        authorize.dataset.mac
      );

      return;
    }

    const editClient =
      event.target.closest(
        '.edit-client'
      );

    if (editClient) {

      const client =
        state.clients.find(
          item =>
            item.id ===
            editClient.dataset.id
        );

      if (client) {

        openClientDialog(
          client
        );
      }

      return;
    }

    const editSource =
      event.target.closest(
        '.edit-source'
      );

    if (editSource) {

      const source =
        state.playlists.find(
          item =>
            item.id ===
            editSource.dataset.id
        );

      if (source) {

        openSourceDialog(
          source
        );
      }

      return;
    }

    const button =
      event.target.closest(
        '.toggle,.delete'
      );

    if (!button) {
      return;
    }

    try {

      if (
        button.classList
          .contains(
            'delete'
          )
      ) {

        if (
          !confirm(
            'Excluir este item?'
          )
        ) {
          return;
        }

        await request(
          `/api/admin/${button.dataset.kind}/${button.dataset.id}`,
          {
            method:
              'DELETE'
          }
        );

      } else {

        await request(
          `/api/admin/${button.dataset.kind}/${button.dataset.id}`,
          {
            method:
              'PUT',

            body:
              JSON.stringify({

                enabled:
                  button.dataset.enabled ===
                  'true'
              })
          }
        );
      }

      await load();

    } catch (
      error
    ) {

      alert(
        error.message
      );
    }
  };


// Build 46: abre monitor em janela separada sem pesar o painel principal.
document.addEventListener('click', event => {
  const button = event.target.closest('.monitor-client');
  if (!button) return;
  const token = sessionStorage.lpsmToken || '';
  if (!token) return;
  localStorage.setItem('lpsmMonitorToken', token);
  window.open(`/monitor.html?client=${encodeURIComponent(button.dataset.id)}`, '_blank', 'noopener');
});
