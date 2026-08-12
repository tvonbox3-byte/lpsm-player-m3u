# API LPSM

Todos os corpos usam JSON. Rotas administrativas exigem `Authorization: Bearer <token>`.

- `POST /api/admin/login` — `{ user, password }`.
- `GET /api/admin/state` — estado do painel.
- `POST /api/admin/playlists` — cadastra `{ name, url, xmltvUrl?, expiresAt? }`.
- `PUT|DELETE /api/admin/playlists/:id` — altera/remove.
- `POST /api/admin/clients` — cadastra `{ name, deviceId, activationCode?, playlistIds, expiresAt? }`.
- `PUT|DELETE /api/admin/clients/:id` — altera/remove.
- `PUT /api/admin/appearance` — banner, papel de parede e mensagem.
- `POST /api/device/activate` — troca `{ deviceId, code }` por token.
- `GET /api/device/config` — configuração e listas autorizadas.

Datas devem estar em ISO 8601. A expiração e o estado ativo são verificados tanto na ativação quanto a cada sincronização.
