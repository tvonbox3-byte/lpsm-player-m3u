const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];

let state = {
  clients: [],
  playlists: [],
  pendingDevices: [],
  appearance: {},
  audit: []
};

const request = async (path, options = {}) => {
  const response = await fetch(path, {
    ...options,
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${localStorage.lpsmToken || ''}`,
      ...options.headers
    }
  });

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
    throw Error(
      data.error ||
      `Erro ${response.status}`
    );
  }

  return data;
};

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

const formatMac = value => {
  const raw = String(value || '')
    .replace(
      /[^0-9a-f]/gi,
      ''
    )
    .toUpperCase()
    .slice(
      0,
      12
    );

  return (
    raw.match(
      /.{1,2}/g
    ) || []
  ).join(':');
};

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

function normalizeServer(
  value
) {
  return String(
    value || ''
  )
    .trim()
    .replace(
      /\/+$/,
      ''
    );
}

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
  playlist
) {
  return parseXtreamUrl(
    playlist?.url
  )
    ? 'XTREAM'
    : 'M3U';
}

function primarySource(
  client
) {
  const ids =
    client?.playlistIds ||
    [];

  for (
    const sourceId of ids
  ) {
    const source =
      state.playlists.find(
        item =>
          item.id ===
          sourceId
      );

    if (source) {
      return source;
    }
  }

  return null;
}

function clientsUsingSource(
  sourceId
) {
  return state.clients.filter(
    client =>
      (
        client.playlistIds ||
        []
      ).includes(
        sourceId
      )
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
    clients.length === 1
  ) {
    return `Fonte de ${clients[0].name}`;
  }

  if (
    clients.length > 1
  ) {
    return (
      `Fonte compartilhada por ` +
      `${clients.length} clientes`
    );
  }

  return (
    sourceTypeOf(
      source
    ) === 'XTREAM'
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
    `${source?.url || 'sem URL'}`
  );
}

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

          max-height: 92vh;
          overflow: auto;
        }

        .source-type {
          display: inline-block;
          margin-top: 6px;
          padding: 4px 9px;
          border-radius: 999px;
          background: #142438;
          color: #47e6a5;
          font-size: 12px;
          font-weight: 700;
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

  const clientList =
    $('#clientList');

  clientList
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
              MACs aguardando autorização
            </h2>

            <p class="meta">
              Abra o APK no aparelho.
              O MAC aparecerá aqui automaticamente.
            </p>

          </div>

        </div>

        <div
          id="pendingList"
          class="grid"
        ></div>

      </section>
      `
    );
}

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

function render() {
  ensureExtraStyles();
  ensurePendingArea();

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

  $('#summary')
    .textContent =
      `${activeClients} clientes ativos · ` +
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
          Abra o APK em um aparelho novo.
        </p>
      `;

  $('#clientList')
    .innerHTML =
      state.clients
        .map(
          client => {

            const source =
              primarySource(
                client
              );

            return `
              <article
                class="card item"
              >

                <div class="row">

                  <h3>
                    ${esc(
                      client.name
                    )}
                  </h3>

                  <span
                    class="pill ${
                      client.enabled ===
                      false
                        ? 'off'
                        : ''
                    }"
                  >
                    ${
                      client.enabled ===
                      false
                        ? 'INATIVO'
                        : 'ATIVO'
                    }
                  </span>

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
                  ${fmtDate(
                    client.expiresAt
                  )}

                  <br>

                  ${
                    source
                      ? `
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
                      `
                      : `
                        <span
                          class="source-type"
                        >
                          SEM FONTE
                        </span>
                      `
                  }

                </div>

                <div class="actions">

                  <button
                    class="ghost edit-client"
                    data-id="${client.id}"
                  >
                    Editar
                  </button>

                  <button
                    class="ghost toggle"
                    data-kind="clients"
                    data-id="${client.id}"
                    data-enabled="${
                      client.enabled ===
                      false
                    }"
                  >
                    ${
                      client.enabled ===
                      false
                        ? 'Ativar'
                        : 'Desativar'
                    }
                  </button>

                  <button
                    class="danger delete"
                    data-kind="clients"
                    data-id="${client.id}"
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
          Nenhum cliente cadastrado.
        </p>
      `;

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
          Cadastre um cliente para criar a primeira.
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

function fillPendingMacOptions(
  selectedMac = ''
) {
  const select =
    $(
      '#clientForm [name=pendingMac]'
    );

  select.innerHTML =
    state.pendingDevices
      .map(
        device => `
          <option
            value="${esc(
              formatMac(
                device.macAddress
              )
            )}"
            ${
              formatMac(
                device.macAddress
              ) ===
              formatMac(
                selectedMac
              )
                ? 'selected'
                : ''
            }
          >
            ${esc(
              formatMac(
                device.macAddress
              )
            )}
          </option>
        `
      )
      .join('');

  if (
    !state.pendingDevices.length
  ) {
    select.innerHTML =
      `
        <option value="">
          Nenhum MAC aguardando
        </option>
      `;
  }
}

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

  m3u.classList
    .toggle(
      'hidden',
      type !==
      'M3U'
    );

  xtream.classList
    .toggle(
      'hidden',
      type !==
      'XTREAM'
    );
}

function fillSourceFields(
  form,
  source,
  prefix
) {
  const type =
    sourceTypeOf(
      source
    );

  form.elements
    .sourceType
    .value =
    type;

  toggleSourceFields(
    prefix,
    type
  );

  if (!source) {

    form.elements
      .m3uUrl
      .value =
      '';

    form.elements
      .xmltvUrl
      .value =
      '';

    form.elements
      .xtreamServer
      .value =
      '';

    form.elements
      .xtreamUsername
      .value =
      '';

    form.elements
      .xtreamPassword
      .value =
      '';

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

    form.elements
      .m3uUrl
      .value =
      '';

    form.elements
      .xmltvUrl
      .value =
      '';

  } else {

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

    form.elements
      .xtreamServer
      .value =
      '';

    form.elements
      .xtreamUsername
      .value =
      '';

    form.elements
      .xtreamPassword
      .value =
      '';
  }
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
      type,

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
    type: 'M3U',

    url,

    xmltvUrl:
      form.elements
        .xmltvUrl
        .value
        .trim()
  };
}

function openClientDialog(
  client = null,
  pendingMac = ''
) {
  const form =
    $('#clientForm');

  form.reset();

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

  const source =
    client
      ? primarySource(
          client
        )
      : null;

  form.elements
    .sourceId
    .value =
    source?.id ||
    '';

  if (client) {

    $('#pendingMacLabel')
      .classList.add(
        'hidden'
      );

    $('#fixedMacLabel')
      .classList.remove(
        'hidden'
      );

    form.elements
      .macAddress
      .value =
      formatMac(
        client.macAddress ||
        client.deviceId
      );

  } else {

    $('#pendingMacLabel')
      .classList.remove(
        'hidden'
      );

    $('#fixedMacLabel')
      .classList.add(
        'hidden'
      );

    fillPendingMacOptions(
      pendingMac
    );

    form.elements
      .macAddress
      .value =
      '';
  }

  fillSourceFields(
    form,
    source,
    'client'
  );

  $('#clientDialog')
    .showModal();
}

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

async function ensureClientSource(
  client,
  sourcePayload
) {
  const current =
    primarySource(
      client
    );

  if (current) {

    await request(
      `/api/admin/playlists/${current.id}`,
      {
        method:
          'PUT',

        body:
          JSON.stringify({
            url:
              sourcePayload.url,

            xmltvUrl:
              sourcePayload.xmltvUrl,

            enabled:
              true,

            expiresAt:
              client.expiresAt ||
              null
          })
      }
    );

    return current.id;
  }

  const created =
    await request(
      '/api/admin/playlists',
      {
        method:
          'POST',

        body:
          JSON.stringify({
            name:
              `Fonte de ${client.name}`,

            url:
              sourcePayload.url,

            xmltvUrl:
              sourcePayload.xmltvUrl,

            enabled:
              true,

            expiresAt:
              client.expiresAt ||
              null
          })
      }
    );

  return created.id;
}

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

      localStorage.lpsmToken =
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

  } catch {

    localStorage
      .removeItem(
        'lpsmToken'
      );
  }
}

if (
  localStorage.lpsmToken
) {
  enter();
}

$('#logout')
  .onclick =
  () => {

    localStorage
      .removeItem(
        'lpsmToken'
      );

    location.reload();
  };

$('#refresh')
  .onclick =
  load;

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

$$('[data-open]')
  .forEach(
    button => {

      button.onclick =
        () => {

          if (
            button.dataset.open !==
            'clientDialog'
          ) {
            return;
          }

          if (
            !state.pendingDevices.length
          ) {

            alert(
              'Nenhum MAC aguardando autorização. Abra o APK no aparelho novo e aguarde o MAC aparecer.'
            );

            return;
          }

          openClientDialog();
        };
    }
  );

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

      const sourcePayload =
        sourcePayloadFromForm(
          form
        );

      const name =
        form.elements
          .name
          .value
          .trim();

      if (!name) {
        throw Error(
          'Informe o nome do cliente.'
        );
      }

      const expiresAt =
        isoEndOfDay(
          form.elements
            .expiresAt
            .value
        );

      const enabled =
        form.elements
          .enabled
          .value ===
        'true';

      if (!editId) {

        const macAddress =
          formatMac(
            form.elements
              .pendingMac
              .value
          );

        if (!macAddress) {

          throw Error(
            'Nenhum MAC foi selecionado. Abra o APK no aparelho primeiro.'
          );
        }

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
                playlistIds: [],

                inlinePlaylist: {

                  name:
                    `Fonte de ${name}`,

                  url:
                    sourcePayload.url,

                  xmltvUrl:
                    sourcePayload.xmltvUrl
                }
              })
          }
        );

      } else {

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

        const sourceId =
          await ensureClientSource(
            {
              ...client,
              name,
              expiresAt
            },

            sourcePayload
          );

        const currentIds =
          client.playlistIds ||
          [];

        const playlistIds =
          currentIds.includes(
            sourceId
          )
            ? currentIds
            : [
                sourceId,
                ...currentIds
              ];

        await request(
          `/api/admin/clients/${editId}`,
          {
            method:
              'PUT',

            body:
              JSON.stringify({
                name,

                macAddress:
                  client.macAddress ||
                  client.deviceId,

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
          : 'Cliente criado e fonte vinculada com sucesso.'
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

      const sourcePayload =
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
                sourcePayload.url,

              xmltvUrl:
                sourcePayload.xmltvUrl,

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
  };
