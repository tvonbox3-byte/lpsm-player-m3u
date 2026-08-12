# LPSM — Player M3U para Android / Android TV

Projeto para reprodução de **listas M3U próprias ou autorizadas**, com aplicativo Android/Android TV e painel administrativo para ativação de dispositivos.

## Componentes

- `android/` — Kotlin + Media3/ExoPlayer; TV ao vivo, filmes, séries, categorias, busca, favoritos e XMLTV/EPG.
- `backend/` — painel web + API de ativação, clientes, listas e aparência.
- `docs/` — documentação técnica.
- `render.yaml` — configuração opcional de deploy no Render.
- `PASSO_A_PASSO_RENDER.md` — instruções para reutilizar o serviço Render já existente.

## Painel

O backend usa as variáveis de ambiente:

- `ADMIN_USER`
- `ADMIN_PASSWORD`
- `TOKEN_SECRET`

Em desenvolvimento local, sem variáveis configuradas, os padrões são `admin` / `admin123`. Não use a senha padrão em produção.

## Android

O app deste pacote está apontando por padrão para:

`https://lpsm-backend.onrender.com`

Se o endereço do backend mudar, altere `API_BASE_URL` em `android/app/build.gradle.kts`.

## Segurança e finalidade

Este projeto não inclui listas de terceiros, proxy de streaming, VPN/WARP, DNS embutido nem mecanismos de evasão de bloqueios. Use somente conteúdo que você tenha autorização para distribuir ou reproduzir.

## Persistência

O backend atual usa arquivo JSON. Para produção, principalmente em hospedagem sem disco persistente, use PostgreSQL/Supabase ou outro armazenamento persistente.
