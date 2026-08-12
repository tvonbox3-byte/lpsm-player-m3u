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

function normalizeText(
  value
) {
  return String(
    value || ''
  )
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
  source
) {
  return parseXtreamUrl(
    source?.url
  )
    ? 'XTREAM'
    : 'M3U';
}


/*
 * =========================================
 * COMPATIBILIDADE COM CLIENTES ANTIGOS
 * =========================================
 */

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

      const id =
        String(
          value
        ).trim();

      if (
        id &&
        !ids.includes(
          id
        )
      ) {
        ids.push(
          id
        );
      }
    };


  [
    'playlistIds',
    'sourceIds',
    'listIds'
  ]
    .forEach(
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
  ]
    .forEach(
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

  const url =
    client?.playlistUrl ||
    client?.m3uUrl ||
    client?.sourceUrl ||
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
      client?.playlistId ||
      client?.sourceId ||
      '',

    name:
      `Fonte de ${
        client?.name ||
        'cliente'
      }`,

    url,

    xmltvUrl:
      client?.xmltvUrl ||
      client?.epgUrl ||
      '',

    enabled:
      true,

    expiresAt:
      client?.expiresAt ||
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


  /*
   * Primeiro procura pelos IDs
   * oficiais vinculados ao cliente.
   */
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


  /*
   * Compatibilidade com versão
   * que guardava playlists
   * dentro do próprio cliente.
   */
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

      const matching =
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

      return (
        matching ||
        embedded
      );
    }
  }


  /*
   * Compatibilidade com possíveis
   * campos antigos.
   */
  const legacy =
    embeddedLegacySource(
      client
    );

  if (legacy) {
    return legacy;
  }


  /*
   * Procura pelo ID do cliente
   * ou MAC caso alguma versão
   * antiga tenha salvo isso na lista.
   */
  const byOwnerField =
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

        normalizeText(
          source.macAddress
        ) ===
        normalizeText(
          client.macAddress ||
          client.deviceId
        )
    );

  if (
    byOwnerField.length ===
    1
  ) {
    return byOwnerField[0];
  }


  /*
   * Procura pelo nome antigo.
   */
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


  /*
   * Instalação antiga com
   * somente 1 cliente e 1 lista.
   */
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


/*
 * =========================================
 * ESTILO COMPLEMENTAR
 * =========================================
 */

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


/*
 * =========================================
 * CARREGAR PAINEL
 * =========================================
 */

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
 * =========================================
 * RENDER
 * =========================================
 */

function render() {

  ensureExtraStyles();
  ensurePendingArea();
  ensureClientSourceWarning();


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


  /*
   * MACS PENDENTES
   */
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


  /*
   * CLIENTES
   */
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
                          SEM FONTE VINCULADA
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


  /*
   * FONTES
   */
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


  /*
   * APARÊNCIA
   */
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


  /*
   * LOGS
   */
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


/*
 * =========================================
 * MACS PENDENTES
 * =========================================
 */

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


/*
 * =========================================
 * CAMPOS M3U / XTREAM
 * =========================================
 */

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

  if (
    form.elements.m3uUrl
  ) {

    form.elements
      .m3uUrl
      .value =
      '';
  }


  if (
    form.elements.xmltvUrl
  ) {

    form.elements
      .xmltvUrl
      .value =
      '';
  }


  if (
    form.elements.xtreamServer
  ) {

    form.elements
      .xtreamServer
      .value =
      '';
  }


  if (
    form.elements.xtreamUsername
  ) {

    form.elements
      .xtreamUsername
      .value =
      '';
  }


  if (
    form.elements.xtreamPassword
  ) {

    form.elements
      .xtreamPassword
      .value =
      '';
  }
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


  /*
   * XTREAM
   */
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


  /*
   * M3U ANTIGA OU NOVA
   *
   * A URL EXISTENTE É COLOCADA
   * DIRETAMENTE NO CAMPO.
   */
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

    type:
      'M3U',

    url,

    xmltvUrl:
      form.elements
        .xmltvUrl
        .value
        .trim()
  };
}


/*
 * =========================================
 * AVISO DE FONTE
 * =========================================
 */

function showSourceWarning(
  message = ''
) {

  const box =
    $(
      '#clientSourceWarning'
    );


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


/*
 * =========================================
 * ABRIR CLIENTE
 * =========================================
 */

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
   * PROCURA A LISTA JÁ EXISTENTE.
   */
  const source =
    client
      ? primarySource(
          client
        )
      : null;


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


  /*
   * EDITAR CLIENTE
   */
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


    /*
     * Só mostra aviso se realmente
     * não encontrar nenhuma lista.
     */
    if (
      !source &&
      state.playlists.length
    ) {

      showSourceWarning(
        'Este cliente antigo não possui uma fonte vinculada por ID. A URL ficará em branco para evitar alterar a lista errada.'
      );
    }

  } else {

    /*
     * CLIENTE NOVO
     */
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


  /*
   * AQUI PREENCHE A URL ANTIGA.
   */
  fillSourceFields(
    form,
    source,
    'client'
  );


  $('#clientDialog')
    .showModal();
}


/*
 * =========================================
 * EDITAR FONTE
 * =========================================
 */

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


/*
 * =========================================
 * ATUALIZAR OU CRIAR FONTE
 * =========================================
 */

async function ensureClientSource(
  client,
  sourcePayload
) {

  const current =
    primarySource(
      client
    );


  /*
   * SE A LISTA JÁ EXISTE,
   * EDITA A MESMA.
   */
  if (
    current?.id &&
    !current.__embeddedLegacy
  ) {

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
              current.enabled !==
              false,

            expiresAt:
              client.expiresAt ||
              current.expiresAt ||
              null
          })
      }
    );


    return current.id;
  }


  /*
   * SÓ CRIA UMA NOVA SE
   * REALMENTE NÃO EXISTIR.
   */
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


/*
 * =========================================
 * LOGIN
 * =========================================
 */

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


/*
 * =========================================
 * ABAS
 * =========================================
 */

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


/*
 * =========================================
 * ALTERAR M3U / XTREAM
 * =========================================
 */

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


/*
 * =========================================
 * NOVO CLIENTE
 * =========================================
 */

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


/*
 * =========================================
 * CANCELAR
 * =========================================
 */

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


/*
 * =========================================
 * SALVAR CLIENTE
 * =========================================
 */

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


      /*
       * =====================================
       * CLIENTE NOVO
       * =====================================
       */
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

                playlistIds:
                  [],

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

        /*
         * =====================================
         * EDITAR CLIENTE
         * =====================================
         */
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


        /*
         * USA A LISTA QUE JÁ EXISTE.
         */
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
          collectClientSourceIds(
            client
          );


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


/*
 * =========================================
 * SALVAR FONTE
 * =========================================
 */

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


/*
 * =========================================
 * APARÊNCIA
 * =========================================
 */

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


/*
 * =========================================
 * BOTÕES DOS CARDS
 * =========================================
 */

document.body.onclick =
  async event => {

    /*
     * CADASTRAR MAC
     */
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


    /*
     * EDITAR CLIENTE
     */
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


    /*
     * EDITAR CONEXÃO
     */
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


    /*
     * ATIVAR / DESATIVAR / EXCLUIR
     */
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
