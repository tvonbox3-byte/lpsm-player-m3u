import http from 'node:http';
import { readFile } from 'node:fs/promises';
import {
  extname,
  join,
  normalize,
  sep
} from 'node:path';
import {
  fileURLToPath
} from 'node:url';

import {
  Store,
  id
} from './store.js';

import {
  passwordHash,
  passwordMatches,
  signToken,
  verifyToken
} from './auth.js';


const root =
  fileURLToPath(
    new URL(
      '../',
      import.meta.url
    )
  );


const config = {

  port:
    Number(
      process.env.PORT ||
      8080
    ),

  dataFile:
    process.env.DATA_FILE ||
    join(
      root,
      'data/lpsm.json'
    ),

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
  new Store(
    config.dataFile
  );


await store.load();


/*
 * Presença dos aparelhos fica somente em memória.
 * Não é necessário gravar no Supabase a cada poucos segundos.
 * Se o servidor reiniciar, todos aparecem OFFLINE até o próximo sinal.
 */
const DEVICE_ONLINE_WINDOW_MS =
  45 * 1000;

const devicePresence =
  new Map();


/*
 * ==========================================
 * MAC
 * ==========================================
 */

const normalizeMac =
  value =>
    String(
      value ||
      ''
    )
      .replace(
        /[^0-9a-f]/gi,
        ''
      )
      .toUpperCase();


const formatMac =
  value => {

    const raw =
      normalizeMac(
        value
      )
        .slice(
          0,
          12
        );


    return (
      raw.match(
        /.{1,2}/g
      ) ||
      []
    ).join(':');
  };


/*
 * ==========================================
 * STATUS
 * ==========================================
 */

const active =
  item =>
    item?.enabled !==
      false &&

    (
      !item?.expiresAt ||

      new Date(
        item.expiresAt
      ) >
      new Date()
    );


const presenceForClient =
  client => {

    const lastSeenAt =
      devicePresence.get(
        client.id
      ) ||
      null;

    const age =
      lastSeenAt
        ? Date.now() -
          Date.parse(lastSeenAt)
        : Infinity;

    return {

      online:
        age >= 0 &&
        age <=
          DEVICE_ONLINE_WINDOW_MS,

      lastSeenAt
    };
  };


const markClientOnline =
  client => {

    const lastSeenAt =
      new Date()
        .toISOString();

    devicePresence.set(
      client.id,
      lastSeenAt
    );

    return lastSeenAt;
  };


/*
 * ==========================================
 * URL
 * ==========================================
 */

const cleanUrl =
  value =>
    String(
      value ||
      ''
    ).trim();


const isHttpUrl =
  value =>
    /^https?:\/\//i
      .test(
        cleanUrl(
          value
        )
      );


/*
 * ==========================================
 * COMPATIBILIDADE COM CLIENTES ANTIGOS
 * ==========================================
 */

function normalizePlaylistIds(
  client,
  playlists
) {

  const ids = [];


  const add =
    value => {

      if (
        value ===
          null ||
        value ===
          undefined
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


  if (
    Array.isArray(
      client?.playlistIds
    )
  ) {

    client.playlistIds
      .forEach(
        add
      );
  }


  if (
    Array.isArray(
      client?.sourceIds
    )
  ) {

    client.sourceIds
      .forEach(
        add
      );
  }


  if (
    Array.isArray(
      client?.listIds
    )
  ) {

    client.listIds
      .forEach(
        add
      );
  }


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


  add(
    client?.playlistId
  );

  add(
    client?.sourceId
  );

  add(
    client?.listId
  );


  const known =
    new Set(
      playlists.map(
        item =>
          String(
            item.id
          )
      )
    );


  return ids.filter(
    value =>
      known.has(
        String(
          value
        )
      )
  );
}


/*
 * Tenta encontrar a lista de um
 * cliente criado em versão antiga.
 */
function findLegacyPlaylistForClient(
  client,
  playlists
) {

  const legacyUrl =
    cleanUrl(

      client?.playlistUrl ||

      client?.m3uUrl ||

      client?.sourceUrl ||

      ''
    );


  /*
   * Procura pela URL antiga.
   */
  if (
    legacyUrl
  ) {

    const byUrl =
      playlists.find(
        item =>
          cleanUrl(
            item.url
          ) ===
          legacyUrl
      );


    if (
      byUrl
    ) {

      return byUrl;
    }
  }


  /*
   * Procura pelo nome.
   */
  const clientName =
    String(
      client?.name ||
      ''
    )
      .trim()
      .toLowerCase();


  if (
    clientName
  ) {

    const byName =
      playlists.filter(
        item => {

          const name =
            String(
              item?.name ||
              ''
            )
              .trim()
              .toLowerCase();


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
  }


  /*
   * Se só existe uma lista no painel,
   * usa ela para o cliente antigo.
   */
  if (
    playlists.length ===
    1
  ) {

    return playlists[0];
  }


  return null;
}


/*
 * ==========================================
 * REPARO AUTOMÁTICO DO BANCO
 * ==========================================
 *
 * Corrige clientes antigos que ficaram
 * sem playlistIds.
 *
 * Isto também faz a URL antiga voltar
 * a aparecer no botão EDITAR CLIENTE.
 */

await store.mutate(
  data => {

    data.clients =
      Array.isArray(
        data.clients
      )
        ? data.clients
        : [];


    data.playlists =
      Array.isArray(
        data.playlists
      )
        ? data.playlists
        : [];


    data.pendingDevices =
      Array.isArray(
        data.pendingDevices
      )
        ? data.pendingDevices
        : [];


    data.audit =
      Array.isArray(
        data.audit
      )
        ? data.audit
        : [];


    data.appearance =
      data.appearance ||
      {

        bannerUrl:
          '',

        wallpaperUrl:
          '',

        supportMessage:
          'Use apenas conteúdo autorizado.'
      };


    /*
     * Normaliza fontes.
     */
    data.playlists
      .forEach(
        playlist => {

          playlist.name =
            String(
              playlist.name ||
              'Fonte'
            );


          playlist.url =
            cleanUrl(
              playlist.url
            );


          playlist.xmltvUrl =
            cleanUrl(
              playlist.xmltvUrl
            );


          playlist.enabled =
            playlist.enabled !==
            false;


          playlist.expiresAt =
            playlist.expiresAt ||
            null;


          playlist.sourceType =
            String(

              playlist.sourceType ||

              (
                String(
                  playlist.url
                )
                  .includes(
                    '/get.php?'
                  )
                    ? 'XTREAM'
                    : 'M3U'
              )
            )
              .toUpperCase();
        }
      );


    /*
     * Normaliza clientes e
     * restaura vínculos antigos.
     */
    data.clients
      .forEach(
        client => {

          const mac =
            normalizeMac(

              client.macAddress ||

              client.deviceId
            );


          if (
            mac.length ===
            12
          ) {

            client.macAddress =
              formatMac(
                mac
              );
          }


          let ids =
            normalizePlaylistIds(
              client,
              data.playlists
            );


          /*
           * Se ficou sem vínculo,
           * procura a lista antiga.
           */
          if (
            ids.length ===
            0
          ) {

            const legacy =
              findLegacyPlaylistForClient(
                client,
                data.playlists
              );


            if (
              legacy
            ) {

              ids = [
                legacy.id
              ];
            }
          }


          client.playlistIds =
            ids;


          client.enabled =
            client.enabled !==
            false;


          client.expiresAt =
            client.expiresAt ||
            null;
        }
      );
  }
);


/*
 * ==========================================
 * ADMIN
 * ==========================================
 */

const adminNeedsSync =

  !store.data.admin ||

  store.data.admin.user !==
    config.adminUser ||

  !passwordMatches(
    config.adminPassword,
    store.data.admin.hash
  );


if (
  adminNeedsSync
) {

  await store.mutate(
    data => {

      data.admin = {

        user:
          config.adminUser,

        hash:
          passwordHash(
            config.adminPassword
          )
      };
    }
  );
}


/*
 * ==========================================
 * RESPOSTA JSON
 * ==========================================
 */

const securityHeaders = {
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY',
  'referrer-policy': 'no-referrer',
  'permissions-policy': 'camera=(), microphone=(), geolocation=()',
  'strict-transport-security': 'max-age=31536000; includeSubDomains'
};


const json =
  (
    res,
    status,
    data,
    extraHeaders = {}
  ) => {

    res.writeHead(
      status,
      {

        'content-type':
          'application/json; charset=utf-8',

        'cache-control':
          'no-store',

        ...securityHeaders,

        ...extraHeaders
      }
    );


    res.end(
      JSON.stringify(
        data
      )
    );
  };


/*
 * ==========================================
 * LIMITAÇÃO DE TENTATIVAS
 * ==========================================
 */

const rateBuckets =
  new Map();


const rateBucketCleanup =
  setInterval(
    () => {
      const now = Date.now();

      for (const [key, bucket] of rateBuckets) {
        if (bucket.resetAt <= now) {
          rateBuckets.delete(key);
        }
      }
    },
    10 * 60 * 1000
  );

rateBucketCleanup.unref();


const requestIp =
  req =>
    String(
      req.headers['x-forwarded-for'] ||
      req.socket.remoteAddress ||
      'unknown'
    )
      .split(',')[0]
      .trim()
      .slice(0, 80);


const enforceRateLimit =
  (req, res, scope, limit, windowMs) => {

    const now = Date.now();
    const key = `${scope}:${requestIp(req)}`;
    let bucket = rateBuckets.get(key);


    if (!bucket || bucket.resetAt <= now) {
      bucket = {
        count: 0,
        resetAt: now + windowMs
      };
    }


    bucket.count += 1;
    rateBuckets.set(key, bucket);


    if (bucket.count <= limit) {
      return {
        allowed: true,
        key
      };
    }


    const retryAfter =
      Math.max(
        1,
        Math.ceil((bucket.resetAt - now) / 1000)
      );


    json(
      res,
      429,
      {
        error:
          'Muitas tentativas. Aguarde e tente novamente.'
      },
      {
        'retry-after': String(retryAfter)
      }
    );


    return {
      allowed: false,
      key
    };
  };


/*
 * ==========================================
 * BODY
 * ==========================================
 */

const body =
  async req => {

    const chunks = [];

    let total = 0;


    for await (
      const chunk of req
    ) {

      chunks.push(
        chunk
      );


      total +=
        chunk.length;


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
      !chunks.length
    ) {

      return {};
    }


    return JSON.parse(

      Buffer
        .concat(
          chunks
        )
        .toString(
          'utf8'
        )
    );
  };


/*
 * ==========================================
 * TOKEN
 * ==========================================
 */

const token =
  req =>

    verifyToken(

      String(
        req.headers
          .authorization ||
        ''
      )
        .replace(
          /^Bearer /,
          ''
        ),

      config.secret
    );


/*
 * ==========================================
 * PLAYLIST SEGURA PARA APK
 * ==========================================
 */

const safePlaylist =
  playlist => ({

    id:
      playlist.id,

    name:
      playlist.name,

    url:
      playlist.url,

    xmltvUrl:
      playlist.xmltvUrl ||
      '',

    enabled:
      playlist.enabled,

    expiresAt:
      playlist.expiresAt ||
      null
  });


/*
 * ==========================================
 * LOCALIZAR CLIENTE PELO MAC
 * ==========================================
 */

function findClientByMac(
  macAddress
) {

  const target =
    normalizeMac(
      macAddress
    );


  return store.data.clients
    .find(
      client =>

        normalizeMac(

          client.macAddress ||

          client.deviceId

        ) ===
        target
    );
}


/*
 * ==========================================
 * LOCALIZAR MAC PENDENTE
 * ==========================================
 */

function findPendingMac(
  macAddress
) {

  const target =
    normalizeMac(
      macAddress
    );


  return (
    store.data
      .pendingDevices ||
    []
  )
    .find(
      device =>

        normalizeMac(
          device.macAddress
        ) ===
        target
    );
}


/*
 * ==========================================
 * REGISTRAR MAC DO APK
 * ==========================================
 */

async function rememberPendingDevice(
  req,
  macAddress
) {

  const target =
    normalizeMac(
      macAddress
    );


  const now =
    new Date();


  const pending =
    findPendingMac(
      target
    );


  /*
   * Evita salvar no Supabase
   * a cada poucos segundos.
   */
  if (
    pending
  ) {

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


  await store.mutate(
    data => {

      data.pendingDevices =
        Array.isArray(
          data.pendingDevices
        )
          ? data.pendingDevices
          : [];


      const existing =
        data.pendingDevices
          .find(
            device =>

              normalizeMac(
                device.macAddress
              ) ===
              target
          );


      if (
        existing
      ) {

        existing.lastSeenAt =
          now.toISOString();


        existing.userAgent =
          String(

            req.headers[
              'user-agent'
            ] ||

            ''
          );


        return;
      }


      data.pendingDevices
        .unshift({

          id:
            id(),

          macAddress:
            formatMac(
              target
            ),

          firstSeenAt:
            now.toISOString(),

          lastSeenAt:
            now.toISOString(),

          userAgent:
            String(

              req.headers[
                'user-agent'
              ] ||

              ''
            )
        });


      data.pendingDevices =
        data.pendingDevices
          .slice(
            0,
            500
          );
    }
  );
}


/*
 * ==========================================
 * NORMALIZAR FONTE
 * ==========================================
 */

function normalizeSource(
  data,
  fallbackName =
    'Fonte'
) {

  const url =
    cleanUrl(
      data?.url
    );


  if (
    !isHttpUrl(
      url
    )
  ) {

    throw new Error(
      'URL HTTP(S) obrigatória'
    );
  }


  return {

    name:
      String(

        data?.name ||

        fallbackName
      ),

    url,

    xmltvUrl:
      cleanUrl(
        data?.xmltvUrl
      ),

    sourceType:
      String(

        data?.sourceType ||

        'M3U'
      )
        .toUpperCase(),

    xtreamServer:
      cleanUrl(
        data?.xtreamServer
      ),

    xtreamUsername:
      String(
        data?.xtreamUsername ||
        ''
      ),

    xtreamPassword:
      String(
        data?.xtreamPassword ||
        ''
      ),

    enabled:
      data?.enabled !==
      false,

    expiresAt:
      data?.expiresAt ||
      null
  };
}


/*
 * ==========================================
 * VERIFICAR MAC REPETIDO
 * ==========================================
 */

function uniqueMacConflict(
  macAddress,
  ignoreClientId =
    ''
) {

  const target =
    normalizeMac(
      macAddress
    );


  return store.data.clients
    .find(
      client =>

        client.id !==
          ignoreClientId &&

        normalizeMac(

          client.macAddress ||

          client.deviceId

        ) ===
        target
    );
}


/*
 * ==========================================
 * API
 * ==========================================
 */

async function api(
  req,
  res,
  path
) {

  /*
   * SAÚDE
   */
  if (
    req.method ===
      'GET' &&

    path ===
      '/api/health'
  ) {

    return json(
      res,
      200,
      {

        ok:
          true,

        service:
          'lpsm-control'
      }
    );
  }


  /*
   * ========================================
   * LOGIN ADMIN
   * ========================================
   */

  if (
    req.method ===
      'POST' &&

    path ===
      '/api/admin/login'
  ) {

    const loginLimit =
      enforceRateLimit(
        req,
        res,
        'admin-login',
        8,
        10 * 60 * 1000
      );


    if (!loginLimit.allowed) {
      return;
    }

    const data =
      await body(
        req
      );


    const suppliedUser =
      String(data.user || '')
        .slice(0, 128);

    const suppliedPassword =
      String(data.password || '')
        .slice(0, 256);

    const passwordIsValid =
      passwordMatches(
        suppliedPassword,
        store.data.admin.hash
      );


    if (

      suppliedUser !==
        store.data.admin.user ||

      !passwordIsValid

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


    rateBuckets.delete(
      loginLimit.key
    );


    return json(
      res,
      200,
      {

        token:
          signToken(

            {
              role:
                'admin'
            },

            config.secret,

            8 *
            3600
          )
      }
    );
  }


  /*
   * ========================================
   * APK -> ATIVAÇÃO
   * ========================================
   */

  if (
    req.method ===
      'POST' &&

    path ===
      '/api/device/activate'
  ) {

    if (
      !enforceRateLimit(
        req,
        res,
        'device-activate',
        60,
        60 * 1000
      ).allowed
    ) {

      return;
    }

    const data =
      await body(
        req
      );


    const macAddress =
      normalizeMac(
        data.macAddress
      );


    if (
      macAddress.length !==
      12
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
     * MAC ainda não cadastrado.
     * Continua aparecendo como
     * sugestão no painel.
     */
    if (
      !client
    ) {

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


    if (
      !active(
        client
      )
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


    markClientOnline(
      client
    );


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

            30 *
            86400
          )
      }
    );
  }


  /*
   * ========================================
   * PRESENÇA DO APK
   * ========================================
   *
   * O aplicativo envia este sinal enquanto está aberto.
   * O servidor calcula OFFLINE automaticamente quando o
   * sinal fica mais antigo que a janela configurada.
   */
  if (
    req.method ===
      'POST' &&

    path ===
      '/api/device/heartbeat'
  ) {

    const deviceToken =
      token(
        req
      );


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
        .find(
          item =>
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


    const lastSeenAt =
      markClientOnline(
        client
      );


    return json(
      res,
      200,
      {
        online:
          true,

        lastSeenAt,

        nextHeartbeatSeconds:
          15
      }
    );
  }


  /*
   * ========================================
   * CONFIGURAÇÃO DO APK
   * ========================================
   */

  if (
    req.method ===
      'GET' &&

    path ===
      '/api/device/config'
  ) {

    const deviceToken =
      token(
        req
      );


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
        .find(
          item =>

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

      !active(
        client
      )

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


    markClientOnline(
      client
    );


    /*
     * Procura as listas vinculadas.
     */
    let ids =
      normalizePlaylistIds(

        client,

        store.data.playlists
      );


    /*
     * Última tentativa de reparo
     * para cliente muito antigo.
     */
    if (
      !ids.length
    ) {

      const legacy =
        findLegacyPlaylistForClient(

          client,

          store.data.playlists
        );


      if (
        legacy
      ) {

        ids = [
          legacy.id
        ];


        await store.mutate(
          data => {

            const current =
              data.clients
                .find(
                  item =>
                    item.id ===
                    client.id
                );


            if (
              current
            ) {

              current.playlistIds =
                ids;
            }
          }
        );
      }
    }


    const allowed =
      new Set(
        ids.map(
          String
        )
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
          store.data.appearance ||
          {},


        playlists:
          store.data.playlists

            .filter(
              playlist =>

                allowed.has(
                  String(
                    playlist.id
                  )
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
   * ========================================
   * ADMIN DAQUI PARA BAIXO
   * ========================================
   */

  const admin =
    token(
      req
    );


  if (

    !admin ||

    admin.role !==
      'admin'

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
   * ========================================
   * ESTADO DO PAINEL
   * ========================================
   */

  if (
    req.method ===
      'GET' &&

    path ===
      '/api/admin/state'
  ) {

    return json(
      res,
      200,
      {

        ...store.data,

        clients:
          store.data.clients
            .map(
              client => ({

                ...client,

                ...presenceForClient(
                  client
                )
              })
            ),

        pendingDevices:
          store.data
            .pendingDevices ||
          [],

        presenceWindowSeconds:
          DEVICE_ONLINE_WINDOW_MS /
          1000,

        admin:
          undefined
      }
    );
  }


  /*
   * ========================================
   * APARÊNCIA
   * ========================================
   */

  if (
    req.method ===
      'PUT' &&

    path ===
      '/api/admin/appearance'
  ) {

    const data =
      await body(
        req
      );


    await store.mutate(
      state => {

        state.appearance = {

          ...state.appearance,

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


        state.audit
          .unshift({

            id:
              id(),

            at:
              new Date()
                .toISOString(),

            action:
              'appearance.update'
          });


        state.audit =
          state.audit
            .slice(
              0,
              200
            );
      }
    );


    return json(
      res,
      200,
      store.data.appearance
    );
  }


  /*
   * ========================================
   * CRIAR FONTE
   * ========================================
   */

  if (
    req.method ===
      'POST' &&

    path ===
      '/api/admin/playlists'
  ) {

    const data =
      await body(
        req
      );


    let normalized;


    try {

      normalized =
        normalizeSource(

          data,

          'Fonte'
        );

    } catch (
      error
    ) {

      return json(
        res,
        400,
        {

          error:
            error.message
        }
      );
    }


    const playlist = {

      id:
        id(),

      ...normalized
    };


    await store.mutate(
      state => {

        state.playlists
          .push(
            playlist
          );


        state.audit
          .unshift({

            id:
              id(),

            at:
              new Date()
                .toISOString(),

            action:
              'playlist.create',

            detail:
              playlist.name
          });


        state.audit =
          state.audit
            .slice(
              0,
              200
            );
      }
    );


    return json(
      res,
      201,
      playlist
    );
  }


  /*
   * ========================================
   * CRIAR CLIENTE
   * ========================================
   *
   * AGORA O MAC NÃO PRECISA ESTAR
   * NA FILA DE ESPERA.
   *
   * PODE SER DIGITADO MANUALMENTE.
   */

  if (
    req.method ===
      'POST' &&

    path ===
      '/api/admin/clients'
  ) {

    const data =
      await body(
        req
      );


    const macAddress =
      normalizeMac(

        data.macAddress ||

        data.deviceId
      );


    if (
      macAddress.length !==
      12
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
     * Só impede MAC duplicado.
     */
    if (
      uniqueMacConflict(
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


    defaultExpiry
      .setFullYear(

        defaultExpiry
          .getFullYear() +
        1
      );


    const playlistIds =
      Array.isArray(
        data.playlistIds
      )
        ? [
            ...new Set(

              data.playlistIds
                .map(
                  String
                )
            )
          ]
        : [];


    let createdPlaylist =
      null;


    /*
     * Fonte criada junto
     * com o cliente.
     */
    if (
      data.inlinePlaylist
        ?.url
    ) {

      let normalized;


      try {

        normalized =
          normalizeSource(

            data.inlinePlaylist,

            `Fonte de ${
              String(
                data.name ||
                'Cliente'
              )
            }`
          );

      } catch (
        error
      ) {

        return json(
          res,
          400,
          {

            error:
              error.message
          }
        );
      }


      createdPlaylist = {

        id:
          id(),

        ...normalized,

        /*
         * Nome interno automático.
         * Não aparece para renomear.
         */
        name:
          `Fonte de ${
            String(
              data.name ||
              'Cliente'
            )
          }`,

        enabled:
          true,

        expiresAt:

          data.expiresAt ||

          normalized.expiresAt ||

          defaultExpiry
            .toISOString()
      };


      playlistIds
        .unshift(
          createdPlaylist.id
        );
    }


    const client = {

      id:
        id(),

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
            .toString(
              36
            )
            .slice(
              2,
              8
            )
        )
          .toUpperCase(),

      playlistIds:
        [
          ...new Set(
            playlistIds
          )
        ],

      enabled:
        data.enabled !==
        false,

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
         * Se o MAC estava na fila,
         * remove depois do cadastro.
         */
        state.pendingDevices =
          (
            state.pendingDevices ||
            []
          )
            .filter(
              device =>

                normalizeMac(
                  device.macAddress
                ) !==
                macAddress
            );


        state.audit
          .unshift({

            id:
              id(),

            at:
              new Date()
                .toISOString(),

            action:
              'client.create',

            detail:
              client.name
          });


        state.audit =
          state.audit
            .slice(
              0,
              200
            );
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


  /*
   * ========================================
   * CLIENTE OU FONTE POR ID
   * ========================================
   */

  const match =
    path.match(
      /^\/api\/admin\/(clients|playlists)\/([^/]+)$/
    );


  /*
   * ========================================
   * EDITAR
   * ========================================
   */

  if (

    match &&

    req.method ===
      'PUT'

  ) {

    const [
      ,
      kind,
      itemId
    ] = match;


    const data =
      await body(
        req
      );


    const collection =
      store.data[
        kind
      ];


    const existing =
      collection
        .find(
          item =>
            item.id ===
            itemId
        );


    if (
      !existing
    ) {

      return json(
        res,
        404,
        {

          error:
            'Não encontrado'
        }
      );
    }


    /*
     * ======================================
     * EDITAR MAC
     * ======================================
     *
     * AGORA NÃO PRECISA ESTAR PENDENTE.
     */

    if (

      kind ===
        'clients' &&

      'macAddress' in
        data

    ) {

      const macAddress =
        normalizeMac(
          data.macAddress
        );


      if (
        macAddress.length !==
        12
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
       * Continua impedindo MAC
       * de outro cliente.
       */
      if (
        uniqueMacConflict(

          macAddress,

          itemId
        )
      ) {

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


    /*
     * Valida URL da fonte.
     */
    if (

      kind ===
        'playlists' &&

      'url' in data &&

      !isHttpUrl(
        data.url
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


    let updated;


    await store.mutate(
      state => {

        const index =
          state[
            kind
          ]
            .findIndex(
              item =>
                item.id ===
                itemId
            );


        if (
          index <
          0
        ) {

          return;
        }


        /*
         * Campos permitidos.
         */
        const allowed =

          kind ===
            'clients'

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

                'expiresAt',

                'sourceType',

                'xtreamServer',

                'xtreamUsername',

                'xtreamPassword'
              ];


        for (
          const field of
          allowed
        ) {

          if (
            !(
              field in
              data
            )
          ) {

            continue;
          }


          if (
            field ===
            'macAddress'
          ) {

            state[
              kind
            ][
              index
            ][
              field
            ] =
              formatMac(
                data[
                  field
                ]
              );

          } else if (
            field ===
            'playlistIds'
          ) {

            state[
              kind
            ][
              index
            ][
              field
            ] =

              Array.isArray(
                data[
                  field
                ]
              )

                ? [
                    ...new Set(

                      data[
                        field
                      ]
                        .map(
                          String
                        )
                    )
                  ]

                : [];

          } else {

            state[
              kind
            ][
              index
            ][
              field
            ] =
              data[
                field
              ];
          }
        }


        updated =
          state[
            kind
          ][
            index
          ];


        /*
         * Se alterou o MAC,
         * remove esse MAC da
         * fila de pendentes.
         */
        if (

          kind ===
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
            )
              .filter(
                device =>

                  normalizeMac(
                    device.macAddress
                  ) !==
                  newMac
              );
        }


        state.audit
          .unshift({

            id:
              id(),

            at:
              new Date()
                .toISOString(),

            action:
              `${kind}.update`,

            detail:
              itemId
          });


        state.audit =
          state.audit
            .slice(
              0,
              200
            );
      }
    );


    return json(
      res,
      200,
      updated
    );
  }


  /*
   * ========================================
   * EXCLUIR
   * ========================================
   */

  if (

    match &&

    req.method ===
      'DELETE'

  ) {

    const [
      ,
      kind,
      itemId
    ] = match;


    await store.mutate(
      state => {

        state[
          kind
        ] =
          state[
            kind
          ]
            .filter(
              item =>
                item.id !==
                itemId
            );


        /*
         * Ao apagar uma fonte,
         * remove o ID dos clientes.
         */
        if (
          kind ===
          'playlists'
        ) {

          state.clients
            .forEach(
              client => {

                client.playlistIds =
                  (
                    client.playlistIds ||
                    []
                  )
                    .filter(
                      playlistId =>

                        String(
                          playlistId
                        ) !==
                        String(
                          itemId
                        )
                    );
              }
            );
        }


        state.audit
          .unshift({

            id:
              id(),

            at:
              new Date()
                .toISOString(),

            action:
              `${kind}.delete`,

            detail:
              itemId
          });


        state.audit =
          state.audit
            .slice(
              0,
              200
            );
      }
    );


    return json(
      res,
      200,
      {

        ok:
          true
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


/*
 * ==========================================
 * ARQUIVOS DO PAINEL
 * ==========================================
 */

const mime = {

  '.html':
    'text/html; charset=utf-8',

  '.js':
    'text/javascript; charset=utf-8',

  '.css':
    'text/css; charset=utf-8',

  '.svg':
    'image/svg+xml',

  '.png':
    'image/png',

  '.jpg':
    'image/jpeg',

  '.jpeg':
    'image/jpeg',

  '.webp':
    'image/webp'
};


/*
 * ==========================================
 * SERVIDOR
 * ==========================================
 */

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


        /*
         * API
         */
        if (
          url.pathname
            .startsWith(
              '/api/'
            )
        ) {

          if (
            !enforceRateLimit(
              req,
              res,
              'api-global',
              600,
              60 * 1000
            ).allowed
          ) {

            return;
          }

          return await api(

            req,

            res,

            url.pathname
          );
        }


        /*
         * ARQUIVOS ESTÁTICOS
         */
        const rel =

          url.pathname ===
          '/'

            ? 'index.html'

            : url.pathname
                .slice(
                  1
                );


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
          file !== publicRoot &&
          !file.startsWith(
            `${publicRoot}${sep}`
          )
        ) {

          res.writeHead(
            403
          );

          return res.end();
        }


        const data =
          await readFile(
            file
          );


        const extension =
          extname(
            file
          );

        const isPanelShell =
          rel === 'index.html' ||
          rel === 'sw.js';

        const isVersionedAsset =
          url.searchParams.has('v') &&
          (
            extension === '.js' ||
            extension === '.css'
          );

        res.writeHead(
          200,
          {

            'content-type':

              mime[
                extension
              ] ||

              'application/octet-stream',

            'cache-control':
              isPanelShell
                ? 'no-cache, must-revalidate'
                : isVersionedAsset
                  ? 'public, max-age=31536000, immutable'
                  : 'public, max-age=3600',

            ...securityHeaders,

            'content-security-policy':
              "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; connect-src 'self'; worker-src 'self'"
          }
        );


        res.end(
          data
        );

      } catch (
        error
      ) {

        if (
          error.code ===
          'ENOENT'
        ) {

          res.writeHead(
            404
          );


          res.end(
            'Não encontrado'
          );


          return;
        }


        console.error(
          error
        );


        const payloadTooLarge =
          error.message ===
          'Payload muito grande';


        json(
          res,
          payloadTooLarge
            ? 413
            : 500,
          {

            error:
              payloadTooLarge
                ? error.message
                : 'Erro interno'
          }
        );
      }
    }
  );


server.listen(
  config.port,
  () => {

    console.log(
      `LPSM Control em http://localhost:${config.port}`
    );
  }
);


export {
  server
};
