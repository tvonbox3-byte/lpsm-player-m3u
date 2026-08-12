import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

import { Store, id } from './store.js';
import {
  passwordHash,
  passwordMatches,
  signToken,
  verifyToken
} from './auth.js';

const root = fileURLToPath(new URL('../', import.meta.url));

const config = {
  port: Number(process.env.PORT || 8080),

  dataFile:
    process.env.DATA_FILE ||
    join(root, 'data/lpsm.json'),

  adminUser:
    process.env.ADMIN_USER ||
    'admin',

  adminPassword:
    process.env.ADMIN_PASSWORD ||
    'admin123',

  secret:
    process.env.TOKEN_SECRET ||
    'development-only-change-me'
};

const store =
  new Store(config.dataFile);

await store.load();

/*
 * Garante compatibilidade com bancos
 * criados antes da lista de MACs pendentes.
 */
if (
  !Array.isArray(
    store.data.pendingDevices
  )
) {
  await store.mutate(data => {
    data.pendingDevices = [];
  });
}

/*
 * Credenciais do Render continuam
 * sendo a fonte oficial do administrador.
 */
const adminNeedsSync =
  !store.data.admin ||
  store.data.admin.user !==
    config.adminUser ||
  !passwordMatches(
    config.adminPassword,
    store.data.admin.hash
  );

if (adminNeedsSync) {
  await store.mutate(data => {
    data.admin = {
      user: config.adminUser,
      hash: passwordHash(
        config.adminPassword
      )
    };
  });
}

const json = (
  res,
  status,
  data
) => {
  res.writeHead(
    status,
    {
      'content-type':
        'application/json; charset=utf-8',

      'cache-control':
        'no-store'
    }
  );

  res.end(
    JSON.stringify(data)
  );
};

const body =
  async req => {
    const chunks = [];

    let total = 0;

    for await (
      const chunk of req
    ) {
      chunks.push(chunk);

      total += chunk.length;

      if (
        total >
        1_000_000
      ) {
        throw Error(
          'Payload muito grande'
        );
      }
    }

    if (
      chunks.length === 0
    ) {
      return {};
    }

    return JSON.parse(
      Buffer.concat(chunks)
    );
  };

const token =
  req =>
    verifyToken(
      (
        req.headers
          .authorization ||
        ''
      ).replace(
        /^Bearer /,
        ''
      ),

      config.secret
    );

const active =
  item =>
    item.enabled !== false &&
    (
      !item.expiresAt ||
      new Date(
        item.expiresAt
      ) > new Date()
    );

const normalizeMac =
  value =>
    String(
      value || ''
    )
      .replace(
        /[^0-9a-f]/gi,
        ''
      )
      .toUpperCase();

const formatMac =
  value => {
    const raw =
      normalizeMac(value);

    return (
      raw.match(
        /.{1,2}/g
      ) || []
    ).join(':');
  };

const safePlaylist =
  playlist => ({
    id: playlist.id,
    name: playlist.name,
    url: playlist.url,

    xmltvUrl:
      playlist.xmltvUrl ||
      '',

    enabled:
      playlist.enabled,

    expiresAt:
      playlist.expiresAt ||
      null
  });

function findClientByMac(
  macAddress
) {
  return store.data.clients
    .find(client =>
      normalizeMac(
        client.macAddress ||
        client.deviceId
      ) === macAddress
    );
}

function findPendingMac(
  macAddress
) {
  return (
    store.data.pendingDevices ||
    []
  ).find(
    device =>
      normalizeMac(
        device.macAddress
      ) === macAddress
  );
}

/*
 * Registra um MAC somente quando
 * ele realmente faz contato pelo APK.
 */
async function rememberPendingDevice(
  req,
  macAddress
) {
  const pending =
    findPendingMac(
      macAddress
    );

  const now =
    new Date();

  /*
   * O APK tenta ativar periodicamente.
   * Evitamos escrever no banco a
   * cada poucos segundos.
   */
  if (pending) {
    const last =
      new Date(
        pending.lastSeenAt ||
        0
      );

    if (
      now.getTime() -
      last.getTime() <
      60_000
    ) {
      return;
    }
  }

  await store.mutate(data => {
    data.pendingDevices =
      Array.isArray(
        data.pendingDevices
      )
        ? data.pendingDevices
        : [];

    const existing =
      data.pendingDevices
        .find(device =>
          normalizeMac(
            device.macAddress
          ) === macAddress
        );

    if (existing) {
      existing.lastSeenAt =
        now.toISOString();

      existing.userAgent =
        String(
          req.headers[
            'user-agent'
          ] || ''
        );

      return;
    }

    data.pendingDevices
      .unshift({
        id: id(),

        macAddress:
          formatMac(
            macAddress
          ),

        firstSeenAt:
          now.toISOString(),

        lastSeenAt:
          now.toISOString(),

        userAgent:
          String(
            req.headers[
              'user-agent'
            ] || ''
          )
      });

    data.pendingDevices =
      data.pendingDevices
        .slice(
          0,
          500
        );
  });
}

async function api(
  req,
  res,
  path
) {
  /*
   * SAÚDE
   */
  if (
    req.method === 'GET' &&
    path ===
      '/api/health'
  ) {
    return json(
      res,
      200,
      {
        ok: true,
        service:
          'lpsm-control'
      }
    );
  }

  /*
   * LOGIN ADMIN
   */
  if (
    req.method === 'POST' &&
    path ===
      '/api/admin/login'
  ) {
    const data =
      await body(req);

    if (
      data.user !==
        store.data.admin.user ||
      !passwordMatches(
        data.password,
        store.data.admin.hash
      )
    ) {
      return json(
        res,
        401,
        {
          error:
            'Credenciais inválidas'
        }
      );
    }

    return json(
      res,
      200,
      {
        token:
          signToken(
            {
              role: 'admin'
            },

            config.secret,

            8 * 3600
          )
      }
    );
  }

  /*
   * =====================================================
   * APK -> PAINEL
   * =====================================================
   */

  if (
    req.method === 'POST' &&
    path ===
      '/api/device/activate'
  ) {
    const data =
      await body(req);

    const macAddress =
      normalizeMac(
        data.macAddress
      );

    if (
      macAddress.length !== 12
    ) {
      return json(
        res,
        400,
        {
          error:
            'MAC inválido'
        }
      );
    }

    const client =
      findClientByMac(
        macAddress
      );

    /*
     * MAC ainda não cadastrado:
     * aparece como pendente no painel.
     */
    if (!client) {
      await rememberPendingDevice(
        req,
        macAddress
      );

      return json(
        res,
        403,
        {
          error:
            'MAC aguardando autorização no painel'
        }
      );
    }

    if (!active(client)) {
      return json(
        res,
        403,
        {
          error:
            'Cliente inativo ou expirado'
        }
      );
    }

    return json(
      res,
      200,
      {
        token:
          signToken(
            {
              role:
                'device',

              clientId:
                client.id,

              macAddress
            },

            config.secret,

            30 * 86400
          )
      }
    );
  }

  /*
   * CONFIGURAÇÃO DO APK
   */
  if (
    req.method === 'GET' &&
    path ===
      '/api/device/config'
  ) {
    const deviceToken =
      token(req);

    if (
      !deviceToken ||
      deviceToken.role !==
        'device'
    ) {
      return json(
        res,
        401,
        {
          error:
            'Não autorizado'
        }
      );
    }

    const client =
      store.data.clients
        .find(item =>
          item.id ===
            deviceToken.clientId &&
          normalizeMac(
            item.macAddress ||
            item.deviceId
          ) ===
            deviceToken.macAddress
        );

    if (
      !client ||
      !active(client)
    ) {
      return json(
        res,
        403,
        {
          error:
            'Cliente inativo ou expirado'
        }
      );
    }

    const allowed =
      new Set(
        client.playlistIds ||
        []
      );

    return json(
      res,
      200,
      {
        client: {
          name:
            client.name,

          expiresAt:
            client.expiresAt ||
            null
        },

        appearance:
          store.data.appearance,

        playlists:
          store.data.playlists
            .filter(
              playlist =>
                allowed.has(
                  playlist.id
                ) &&
                active(
                  playlist
                )
            )
            .map(
              safePlaylist
            )
      }
    );
  }

  /*
   * Daqui para baixo:
   * somente administrador.
   */
  const admin =
    token(req);

  if (
    !admin ||
    admin.role !== 'admin'
  ) {
    return json(
      res,
      401,
      {
        error:
          'Não autorizado'
      }
    );
  }

  /*
   * ESTADO DO PAINEL
   */
  if (
    req.method === 'GET' &&
    path ===
      '/api/admin/state'
  ) {
    return json(
      res,
      200,
      {
        ...store.data,

        pendingDevices:
          store.data
            .pendingDevices ||
          [],

        admin:
          undefined
      }
    );
  }

  /*
   * APARÊNCIA
   */
  if (
    req.method === 'PUT' &&
    path ===
      '/api/admin/appearance'
  ) {
    const data =
      await body(req);

    await store.mutate(
      state => {
        state.appearance = {
          bannerUrl:
            String(
              data.bannerUrl ||
              ''
            ),

          wallpaperUrl:
            String(
              data.wallpaperUrl ||
              ''
            ),

          supportMessage:
            String(
              data.supportMessage ||
              ''
            )
        };

        state.audit.unshift({
          id: id(),

          at:
            new Date()
              .toISOString(),

          action:
            'appearance.update'
        });
      }
    );

    return json(
      res,
      200,
      store.data.appearance
    );
  }

  /*
   * CRIAR LISTA
   */
  if (
    req.method === 'POST' &&
    path ===
      '/api/admin/playlists'
  ) {
    const data =
      await body(req);

    if (
      !/^https?:\/\//i.test(
        data.url || ''
      )
    ) {
      return json(
        res,
        400,
        {
          error:
            'URL HTTP(S) obrigatória'
        }
      );
    }

    const playlist = {
      id: id(),

      name:
        String(
          data.name ||
          'Lista'
        ),

      url:
        String(
          data.url
        ),

      xmltvUrl:
        String(
          data.xmltvUrl ||
          ''
        ),

      enabled:
        data.enabled !== false,

      expiresAt:
        data.expiresAt ||
        null
    };

    await store.mutate(
      state => {
        state.playlists
          .push(
            playlist
          );

        state.audit
          .unshift({
            id: id(),

            at:
              new Date()
                .toISOString(),

            action:
              'playlist.create',

            detail:
              playlist.name
          });
      }
    );

    return json(
      res,
      201,
      playlist
    );
  }

  /*
   * CRIAR CLIENTE
   */
  if (
    req.method === 'POST' &&
    path ===
      '/api/admin/clients'
  ) {
    const data =
      await body(req);

    const macAddress =
      normalizeMac(
        data.macAddress ||
        data.deviceId
      );

    if (
      macAddress.length !== 12
    ) {
      return json(
        res,
        400,
        {
          error:
            'MAC inválido'
        }
      );
    }

    /*
     * Não permite cadastrar MAC
     * inventado/manual.
     */
    const pending =
      findPendingMac(
        macAddress
      );

    if (!pending) {
      return json(
        res,
        400,
        {
          error:
            'MAC não reconhecido. Abra o APK neste aparelho primeiro e aguarde o MAC aparecer no painel.'
        }
      );
    }

    if (
      findClientByMac(
        macAddress
      )
    ) {
      return json(
        res,
        409,
        {
          error:
            'Este MAC já está cadastrado'
        }
      );
    }

    const defaultExpiry =
      new Date();

    defaultExpiry.setFullYear(
      defaultExpiry
        .getFullYear() +
      1
    );

    const playlistIds =
      Array.isArray(
        data.playlistIds
      )
        ? [
            ...data.playlistIds
          ]
        : [];

    let createdPlaylist =
      null;

    /*
     * Permite continuar criando
     * uma lista junto do cliente.
     */
    if (
      data.inlinePlaylist
        ?.url
    ) {
      if (
        !/^https?:\/\//i.test(
          data.inlinePlaylist
            .url
        )
      ) {
        return json(
          res,
          400,
          {
            error:
              'URL M3U HTTP(S) obrigatória'
          }
        );
      }

      createdPlaylist = {
        id: id(),

        name:
          String(
            data.inlinePlaylist
              .name ||
            data.name ||
            'Lista'
          ),

        url:
          String(
            data.inlinePlaylist
              .url
          ),

        xmltvUrl:
          String(
            data.inlinePlaylist
              .xmltvUrl ||
            ''
          ),

        enabled:
          true,

        expiresAt:
          data.expiresAt ||
          defaultExpiry
            .toISOString()
      };

      playlistIds.push(
        createdPlaylist.id
      );
    }

    const client = {
      id: id(),

      name:
        String(
          data.name ||
          'Cliente'
        ),

      macAddress:
        formatMac(
          macAddress
        ),

      activationCode:
        String(
          data.activationCode ||
          Math.random()
            .toString(36)
            .slice(2, 8)
        ).toUpperCase(),

      playlistIds,

      enabled:
        data.enabled !== false,

      expiresAt:
        data.expiresAt ||
        defaultExpiry
          .toISOString(),

      createdAt:
        new Date()
          .toISOString()
    };

    await store.mutate(
      state => {
        if (
          createdPlaylist
        ) {
          state.playlists
            .push(
              createdPlaylist
            );
        }

        state.clients
          .push(
            client
          );

        /*
         * Sai da fila de pendentes
         * quando for autorizado.
         */
        state.pendingDevices =
          (
            state.pendingDevices ||
            []
          ).filter(
            device =>
              normalizeMac(
                device.macAddress
              ) !==
              macAddress
          );

        state.audit
          .unshift({
            id: id(),

            at:
              new Date()
                .toISOString(),

            action:
              'client.create',

            detail:
              client.name
          });
      }
    );

    return json(
      res,
      201,
      {
        ...client,
        createdPlaylist
      }
    );
  }

  const match =
    path.match(
      /^\/api\/admin\/(clients|playlists)\/([^/]+)$/
    );

  /*
   * EDITAR CLIENTE OU LISTA
   */
  if (
    match &&
    req.method === 'PUT'
  ) {
    const [
      ,
      kind,
      itemId
    ] = match;

    const key = kind;

    const data =
      await body(req);

    let updated;

    /*
     * Validação da URL
     * ao editar M3U.
     */
    if (
      key ===
        'playlists' &&
      'url' in data &&
      !/^https?:\/\//i.test(
        data.url || ''
      )
    ) {
      return json(
        res,
        400,
        {
          error:
            'URL HTTP(S) obrigatória'
        }
      );
    }

    /*
     * Se trocar MAC de um cliente,
     * o novo MAC também precisa ter
     * aparecido realmente no APK.
     */
    if (
      key === 'clients' &&
      'macAddress' in data
    ) {
      const existingClient =
        store.data.clients
          .find(
            item =>
              item.id ===
              itemId
          );

      if (
        existingClient
      ) {
        const oldMac =
          normalizeMac(
            existingClient
              .macAddress
          );

        const newMac =
          normalizeMac(
            data.macAddress
          );

        if (
          newMac.length !== 12
        ) {
          return json(
            res,
            400,
            {
              error:
                'MAC inválido'
            }
          );
        }

        if (
          newMac !== oldMac &&
          !findPendingMac(
            newMac
          )
        ) {
          return json(
            res,
            400,
            {
              error:
                'Novo MAC não reconhecido pelo APK'
            }
          );
        }

        const duplicate =
          store.data.clients
            .find(
              item =>
                item.id !==
                  itemId &&
                normalizeMac(
                  item.macAddress
                ) ===
                  newMac
            );

        if (duplicate) {
          return json(
            res,
            409,
            {
              error:
                'Este MAC já pertence a outro cliente'
            }
          );
        }
      }
    }

    await store.mutate(
      state => {
        const index =
          state[key]
            .findIndex(
              item =>
                item.id ===
                itemId
            );

        if (
          index < 0
        ) {
          return;
        }

        const allowed =
          key === 'clients'
            ? [
                'name',
                'macAddress',
                'activationCode',
                'playlistIds',
                'enabled',
                'expiresAt'
              ]
            : [
                'name',
                'url',
                'xmltvUrl',
                'enabled',
                'expiresAt'
              ];

        for (
          const field of allowed
        ) {
          if (
            field in data
          ) {
            state[key][index][field] =
              field ===
                'macAddress'
                ? formatMac(
                    data[field]
                  )
                : data[field];
          }
        }

        updated =
          state[key][index];

        if (
          key ===
            'clients' &&
          'macAddress' in
            data
        ) {
          const newMac =
            normalizeMac(
              data.macAddress
            );

          state.pendingDevices =
            (
              state.pendingDevices ||
              []
            ).filter(
              device =>
                normalizeMac(
                  device.macAddress
                ) !==
                newMac
            );
        }

        state.audit
          .unshift({
            id: id(),

            at:
              new Date()
                .toISOString(),

            action:
              `${kind}.update`,

            detail:
              itemId
          });
      }
    );

    return updated
      ? json(
          res,
          200,
          updated
        )
      : json(
          res,
          404,
          {
            error:
              'Não encontrado'
          }
        );
  }

  /*
   * EXCLUIR
   */
  if (
    match &&
    req.method === 'DELETE'
  ) {
    const [
      ,
      kind,
      itemId
    ] = match;

    await store.mutate(
      state => {
        state[kind] =
          state[kind]
            .filter(
              item =>
                item.id !==
                itemId
            );

        if (
          kind ===
          'playlists'
        ) {
          state.clients
            .forEach(
              client => {
                client.playlistIds =
                  (
                    client
                      .playlistIds ||
                    []
                  ).filter(
                    playlistId =>
                      playlistId !==
                      itemId
                  );
              }
            );
        }

        state.audit
          .unshift({
            id: id(),

            at:
              new Date()
                .toISOString(),

            action:
              `${kind}.delete`,

            detail:
              itemId
          });
      }
    );

    return json(
      res,
      200,
      {
        ok: true
      }
    );
  }

  return json(
    res,
    404,
    {
      error:
        'Rota não encontrada'
    }
  );
}

const mime = {
  '.html':
    'text/html; charset=utf-8',

  '.js':
    'text/javascript; charset=utf-8',

  '.css':
    'text/css; charset=utf-8',

  '.svg':
    'image/svg+xml'
};

const server =
  http.createServer(
    async (
      req,
      res
    ) => {
      try {
        const url =
          new URL(
            req.url,
            'http://localhost'
          );

        if (
          url.pathname
            .startsWith(
              '/api/'
            )
        ) {
          return await api(
            req,
            res,
            url.pathname
          );
        }

        const rel =
          url.pathname === '/'
            ? 'index.html'
            : url.pathname
                .slice(1);

        const file =
          normalize(
            join(
              root,
              'public',
              rel
            )
          );

        const publicRoot =
          normalize(
            join(
              root,
              'public'
            )
          );

        if (
          !file.startsWith(
            publicRoot
          )
        ) {
          res.writeHead(403);
          return res.end();
        }

        const data =
          await readFile(
            file
          );

        res.writeHead(
          200,
          {
            'content-type':
              mime[
                extname(file)
              ] ||
              'application/octet-stream',

            'x-content-type-options':
              'nosniff',

            'content-security-policy':
              "default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; connect-src 'self'"
          }
        );

        res.end(data);

      } catch (error) {
        if (
          error.code ===
          'ENOENT'
        ) {
          res.writeHead(404);
          res.end(
            'Não encontrado'
          );

        } else {
          console.error(
            error
          );

          json(
            res,
            500,
            {
              error:
                'Erro interno'
            }
          );
        }
      }
    }
  );

server.listen(
  config.port,
  () =>
    console.log(
      `LPSM Control em http://localhost:${config.port}`
    )
);

export { server };
