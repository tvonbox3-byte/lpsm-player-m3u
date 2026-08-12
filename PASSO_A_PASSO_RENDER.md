# Publicar o LPSM M3U no serviço Render já existente

O objetivo é reaproveitar o serviço que já possui o endereço:

`https://lpsm-backend.onrender.com`

Assim o aplicativo Android deste pacote já aponta para o endereço certo.

## 1. GitHub

Crie um repositório novo chamado `lpsm-player-m3u` e envie **todo o conteúdo desta pasta** para a branch `main`.

Não envie arquivos `.env`, chaves de assinatura ou arquivos JSON reais da pasta `backend/data`.

## 2. Render — trocar a origem do serviço existente

No serviço `lpsm-backend`:

- Settings → Source → Edit
- escolha o novo repositório `tvonbox3-byte/lpsm-player-m3u`
- Branch: `main`
- Root Directory: `backend`
- Build Command: `npm install`
- Start Command: `npm start`

## 3. Environment Variables

Defina:

- `ADMIN_USER` = `admin`
- `ADMIN_PASSWORD` = uma senha nova e forte
- `TOKEN_SECRET` = um valor aleatório longo (mínimo 32 bytes)

Depois salve e faça o deploy.

## 4. Testes

Abra:

- `https://lpsm-backend.onrender.com/api/health` → deve retornar JSON com `ok: true`.
- `https://lpsm-backend.onrender.com` → deve abrir o painel **LPSM Control** para listas M3U.

## 5. Android

O projeto Android já está configurado com:

`https://lpsm-backend.onrender.com`

Abra a pasta `android` no Android Studio, use JDK 17 + SDK 35 e gere o APK com `assembleDebug`.

## Aviso sobre o plano Free do Render

O backend atual grava clientes e listas em JSON. Em instâncias sem disco persistente, esses dados podem ser perdidos em reinícios/deploys. Para uso definitivo com clientes, migre a persistência para PostgreSQL/Supabase ou outro banco persistente antes de depender do serviço em produção.
