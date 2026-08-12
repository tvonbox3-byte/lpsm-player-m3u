const $ =
  selector =>
    document.querySelector(
      selector
    );

const $$ =
  selector =>
    [
      ...document
        .querySelectorAll(
          selector
        )
    ];

let state = {
  clients: [],
  playlists: [],
  pendingDevices: [],
  appearance: {},
  audit: []
};

const request =
  async (
    path,
    options = {}
  ) => {
    const response =
      await fetch(
        path,
        {
          ...options,

          headers: {
            'content-type':
              'application/json',

            authorization:
              `Bearer ${
                localStorage
                  .lpsmToken ||
                ''
              }`,

            ...options.headers
          }
        }
      );

    const data =
      await response.json();

    if (!response.ok) {
      throw Error(
        data.error ||
        'Falha'
      );
    }

    return data;
  };

const esc =
  value =>
    String(
      value ?? ''
    ).replace(
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

const formatMac =
  value => {
    const raw =
      String(
        value || ''
      )
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

const fmt =
  value =>
    value
      ? new Date(
          value
        ).toLocaleDateString(
          'pt-BR'
        )
      : 'Sem expiração';

const fmtDateTime =
  value =>
    value
      ? new Date(
          value
        ).toLocaleString(
          'pt-BR'
        )
      : '';

/*
 * Cria a seção de MACs que
 * apareceram realmente no APK.
 */
function ensurePendingArea() {
  if (
    $('#pendingDevices')
  ) {
    return;
  }

  const clientList =
    $('#clientList');

  clientList.insertAdjacentHTML(
    'beforebegin',
    `
      <section
        id="pendingDevices"
        class="pending-area"
      >
        <div class="section-title">
          <div>
            <h2>MACs aguardando autorização</h2>
            <p class="meta">
              Estes aparelhos abriram o APK
              e estão aguardando cadastro.
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

  document.head
    .insertAdjacentHTML(
      'beforeend',
      `
      <style>
        .pending-area {
          margin-bottom: 30px;
          padding-bottom: 25px;
          border-bottom:
            1px solid #24364a;
        }

        .pending-card {
          border:
            1px solid #47e6a5 !important;
        }

        .mac-code {
          color: #ffe500;
          font-size: 19px;
          font-weight: 700;
          letter-spacing: 1px;
        }

        .inline-playlist {
          margin-top: 22px;
          padding: 18px;
          background: #0a1624;
          border:
            1px solid #30445b;
          border-radius: 12px;
        }

        .inline-playlist h3 {
          margin-top: 0;
          color: #47e6a5;
        }

        #clientDialog,
        #playlistDialog {
          max-height: 92vh;
          overflow: auto;
        }

        input[readonly] {
          opacity: .85;
          cursor: not-allowed;
        }
      </style>
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

  render();
}

function render() {
  ensurePendingArea();

  state.clients.forEach(
    client => {
      client.deviceId =
        formatMac(
          client.macAddress ||
          client.deviceId
        );
    }
  );

  $('#summary')
    .textContent =
      `${
        state.clients.filter(
          client =>
            client.enabled
        ).length
      } clientes ativos · ${
        state.playlists.filter(
          playlist =>
            playlist.enabled
        ).length
      } listas disponíveis · ${
        state.pendingDevices
          .length
      } MAC(s) aguardando`;

  /*
   * MACs PENDENTES
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
          Abra o APK no aparelho para
          ele aparecer aqui.
        </p>
      `;

  /*
   * CLIENTES
   */
  $('#clientList')
    .innerHTML =
      state.clients
        .map(
          client => `
            <article class="card item">
              <div class="row">
                <h3>
                  ${esc(
                    client.name
                  )}
                </h3>

                <span
                  class="pill ${
                    client.enabled
                      ? ''
                      : 'off'
                  }"
                >
                  ${
                    client.enabled
                      ? 'ATIVO'
                      : 'INATIVO'
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
                ${fmt(
                  client.expiresAt
                )}
                <br>

                ${
                  (
                    client.playlistIds ||
                    []
                  ).length
                } lista(s)
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
                    !client.enabled
                  }"
                >
                  ${
                    client.enabled
                      ? 'Desativar'
                      : 'Ativar'
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
          `
        )
        .join('') ||
      '<p>Nenhum cliente cadastrado.</p>';

  /*
   * LISTAS M3U
   */
  $('#playlistList')
    .innerHTML =
      state.playlists
        .map(
          playlist => `
            <article class="card item">
              <div class="row">
                <h3>
                  ${esc(
                    playlist.name
                  )}
                </h3>

                <span
                  class="pill ${
                    playlist.enabled
                      ? ''
                      : 'off'
                  }"
                >
                  ${
                    playlist.enabled
                      ? 'ATIVA'
                      : 'INATIVA'
                  }
                </span>
              </div>

              <div class="meta">
                ${esc(
                  playlist.url
                )}
                <br>

                XMLTV:
                ${
                  playlist.xmltvUrl
                    ? 'configurado'
                    : 'não configurado'
                }
                <br>

                Expira:
                ${fmt(
                  playlist.expiresAt
                )}
              </div>

              <div class="actions">
                <button
                  class="ghost edit-playlist"
                  data-id="${playlist.id}"
                >
                  Editar
                </button>

                <button
                  class="ghost toggle"
                  data-kind="playlists"
                  data-id="${playlist.id}"
                  data-enabled="${
                    !playlist.enabled
                  }"
                >
                  ${
                    playlist.enabled
                      ? 'Desativar'
                      : 'Ativar'
                  }
                </button>

                <button
                  class="danger delete"
                  data-kind="playlists"
                  data-id="${playlist.id}"
                >
                  Excluir
                </button>
              </div>
            </article>
          `
        )
        .join('') ||
      '<p>Nenhuma lista cadastrada.</p>';

  /*
   * CHECKBOX DE LISTAS
   */
  $('#playlistChecks')
    .className =
      'checks';

  $('#playlistChecks')
    .innerHTML =
      state.playlists
        .map(
          playlist => `
            <label>
              <input
                type="checkbox"
                name="playlistIds"
                value="${playlist.id}"
              >
              ${esc(
                playlist.name
              )}
            </label>
          `
        )
        .join('') ||
      'Cadastre uma lista primeiro.';

  /*
   * APARÊNCIA
   */
  for (
    const [
      key,
      value
    ] of Object.entries(
      state.appearance ||
      {}
    )
  ) {
    const element =
      $(
        `#appearanceForm [name=${key}]`
      );

    if (element) {
      element.value =
        value || '';
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
 * LOGIN
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

        localStorage
          .lpsmToken =
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

      } catch (error) {
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
  .onclick = () => {
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
 * ABAS
 */
$$('nav button')
  .forEach(
    button =>
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
                      button.dataset
                        .tab
                  )
            );
        }
  );

/*
 * Campo MAC do cliente.
 */
const macInput =
  $(
    '#clientForm [name=deviceId]'
  );

macInput.name =
  'macAddress';

macInput.placeholder =
  'AA:BB:CC:DD:EE:FF';

macInput.maxLength =
  17;

macInput.parentElement
  .childNodes[0]
  .textContent =
    'MAC exibido no aplicativo';

macInput
  .addEventListener(
    'input',
    () => {
      macInput.value =
        formatMac(
          macInput.value
        );
    }
  );

/*
 * Código antigo não é mais
 * necessário para cadastro.
 */
$('#clientForm [name=activationCode]')
  .parentElement
  .classList
  .add(
    'hidden'
  );

/*
 * Permite criar uma M3U
 * junto do novo cliente.
 */
$('#clientForm fieldset')
  .insertAdjacentHTML(
    'beforebegin',
    `
      <div class="inline-playlist">
        <h3>
          Playlist deste cliente
        </h3>

        <label>
          Título da lista
          <input
            name="playlistName"
            placeholder="Ex.: TV Sala"
          >
        </label>

        <label>
          URL M3U/M3U8
          <input
            name="playlistUrl"
            type="url"
            placeholder="https://servidor/lista.m3u"
          >
        </label>

        <label>
          URL XMLTV (opcional)
          <input
            name="inlineXmltvUrl"
            type="url"
          >
        </label>

        <p class="meta">
          A lista será criada e
          vinculada automaticamente.
        </p>
      </div>
    `
  );

function openClientDialog(
  client = null,
  pendingMac = ''
) {
  const form =
    $('#clientForm');

  form.reset();

  form.dataset.editId =
    client?.id || '';

  form
    .querySelector('h2')
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
        : 'Criar';

  const expiry =
    new Date();

  expiry.setFullYear(
    expiry.getFullYear() +
    1
  );

  form.elements.name
    .value =
      client?.name ||
      '';

  form.elements.macAddress
    .value =
      formatMac(
        client?.macAddress ||
        pendingMac ||
        ''
      );

  /*
   * Novo cliente:
   * MAC vem obrigatoriamente
   * da fila do APK.
   */
  form.elements.macAddress
    .readOnly =
      !client;

  form.elements.expiresAt
    .value =
      client?.expiresAt
        ? String(
            client.expiresAt
          ).slice(
            0,
            10
          )
        : expiry
            .toISOString()
            .slice(
              0,
              10
            );

  form.elements.enabled
    .value =
      String(
        client?.enabled !==
        false
      );

  $$('#playlistChecks input')
    .forEach(
      input => {
        input.checked =
          !!client
            ?.playlistIds
            ?.includes(
              input.value
            );
      }
    );

  const inline =
    form.querySelector(
      '.inline-playlist'
    );

  if (inline) {
    inline.classList
      .toggle(
        'hidden',
        !!client
      );
  }

  $('#clientDialog')
    .showModal();
}

/*
 * EDITAR / CRIAR PLAYLIST
 */
function openPlaylistDialog(
  playlist = null
) {
  const form =
    $('#playlistForm');

  form.reset();

  form.dataset.editId =
    playlist?.id || '';

  form
    .querySelector('h2')
    .textContent =
      playlist
        ? 'Editar lista M3U'
        : 'Nova lista autorizada';

  form
    .querySelector(
      'button[value=default]'
    )
    .textContent =
      playlist
        ? 'Salvar alterações'
        : 'Criar';

  form.elements.name
    .value =
      playlist?.name ||
      '';

  form.elements.url
    .value =
      playlist?.url ||
      '';

  form.elements.xmltvUrl
    .value =
      playlist?.xmltvUrl ||
      '';

  form.elements.expiresAt
    .value =
      playlist?.expiresAt
        ? String(
            playlist
              .expiresAt
          ).slice(
            0,
            10
          )
        : '';

  $('#playlistDialog')
    .showModal();
}

/*
 * Botões Novo cliente / Nova lista.
 */
$$('[data-open]')
  .forEach(
    button =>
      button.onclick =
        () => {
          if (
            button.dataset
              .open ===
            'clientDialog'
          ) {
            const firstPending =
              state
                .pendingDevices?.[0];

            if (!firstPending) {
              alert(
                'Nenhum MAC reconhecido aguardando autorização. Abra o APK no aparelho primeiro.'
              );

              return;
            }

            openClientDialog(
              null,
              firstPending
                .macAddress
            );

          } else if (
            button.dataset
              .open ===
            'playlistDialog'
          ) {
            openPlaylistDialog();
          }
        }
  );

$$('.close')
  .forEach(
    button =>
      button.onclick =
        () =>
          button
            .closest(
              'dialog'
            )
            .close()
  );

/*
 * SALVAR LISTA
 */
$('#playlistForm')
  .onsubmit =
    async event => {
      event.preventDefault();

      const form =
        event.target;

      const editId =
        form.dataset
          .editId;

      const submit =
        event.submitter;

      submit.disabled =
        true;

      try {
        const data =
          Object.fromEntries(
            new FormData(
              form
            )
          );

        data.enabled =
          true;

        data.expiresAt =
          data.expiresAt
            ? new Date(
                data.expiresAt +
                'T23:59:59'
              ).toISOString()
            : null;

        await request(
          editId
            ? `/api/admin/playlists/${editId}`
            : '/api/admin/playlists',

          {
            method:
              editId
                ? 'PUT'
                : 'POST',

            body:
              JSON.stringify(
                data
              )
          }
        );

        form
          .closest(
            'dialog'
          )
          .close();

        form.reset();

        form.dataset.editId =
          '';

        await load();

        alert(
          editId
            ? 'Lista atualizada com sucesso.'
            : 'Lista criada com sucesso.'
        );

      } catch (error) {
        alert(
          'Não foi possível salvar a lista: ' +
          error.message
        );

      } finally {
        submit.disabled =
          false;
      }
    };

/*
 * SALVAR CLIENTE
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
        form.dataset
          .editId;

      submit.disabled =
        true;

      try {
        const formData =
          new FormData(
            form
          );

        const data =
          Object.fromEntries(
            formData
          );

        data.playlistIds =
          formData.getAll(
            'playlistIds'
          );

        data.enabled =
          data.enabled ===
          'true';

        data.expiresAt =
          data.expiresAt
            ? new Date(
                data.expiresAt +
                'T23:59:59'
              ).toISOString()
            : null;

        if (
          !editId &&
          data.playlistUrl
        ) {
          data.inlinePlaylist = {
            name:
              data.playlistName ||
              data.name,

            url:
              data.playlistUrl,

            xmltvUrl:
              data.inlineXmltvUrl ||
              ''
          };
        }

        delete data.playlistName;
        delete data.playlistUrl;
        delete data.inlineXmltvUrl;
        delete data.activationCode;

        await request(
          editId
            ? `/api/admin/clients/${editId}`
            : '/api/admin/clients',

          {
            method:
              editId
                ? 'PUT'
                : 'POST',

            body:
              JSON.stringify(
                data
              )
          }
        );

        form
          .closest(
            'dialog'
          )
          .close();

        form.reset();

        form.dataset.editId =
          '';

        await load();

        alert(
          editId
            ? 'Cliente atualizado com sucesso.'
            : 'Cliente autorizado com sucesso.'
        );

      } catch (error) {
        alert(
          'Não foi possível salvar: ' +
          error.message
        );

      } finally {
        submit.disabled =
          false;

        submit.textContent =
          editId
            ? 'Salvar alterações'
            : 'Criar';
      }
    };

/*
 * APARÊNCIA
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
 * BOTÕES DAS LISTAS/CARDS
 */
document.body.onclick =
  async event => {
    /*
     * Autorizar MAC detectado.
     */
    const authorize =
      event.target.closest(
        '.authorize-mac'
      );

    if (authorize) {
      openClientDialog(
        null,
        authorize.dataset
          .mac
      );

      return;
    }

    /*
     * Editar cliente.
     */
    const editClient =
      event.target.closest(
        '.edit-client'
      );

    if (editClient) {
      openClientDialog(
        state.clients
          .find(
            client =>
              client.id ===
              editClient
                .dataset.id
          )
      );

      return;
    }

    /*
     * Editar playlist.
     */
    const editPlaylist =
      event.target.closest(
        '.edit-playlist'
      );

    if (editPlaylist) {
      openPlaylistDialog(
        state.playlists
          .find(
            playlist =>
              playlist.id ===
              editPlaylist
                .dataset.id
          )
      );

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
                button.dataset
                  .enabled ===
                'true'
            })
        }
      );
    }

    await load();
  };
