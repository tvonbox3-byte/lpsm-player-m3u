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

Painel publicado: `https://lpsm-player-backend.onrender.com/`

## Android

O app deste pacote está apontando para:

`https://lpsm-player-backend.onrender.com`

Se o endereço do backend mudar, altere `API_BASE_URL` em `android/app/build.gradle.kts`.

## Segurança e finalidade

Este projeto não inclui listas de terceiros, proxy de streaming, VPN/WARP, DNS embutido nem mecanismos de evasão de bloqueios. Use somente conteúdo que você tenha autorização para distribuir ou reproduzir.

## Persistência e proteção

O serviço publicado usa Supabase quando `SUPABASE_URL` e `SUPABASE_SECRET_KEY` estão configurados. O arquivo JSON é apenas a alternativa local.

O painel aplica HTTPS pelo Render, tokens assinados, hash scrypt para a senha, sessão administrativa temporária, limitação de tentativas, cabeçalhos de segurança e cache do painel. O APK é assinado no GitHub Actions e, a partir da versão 2.2.16, também verifica o SHA-256 da atualização antes de abrir o instalador.

O plano gratuito do Render entra em repouso após um período sem acessos. Nas visitas seguintes, o navegador pode mostrar a interface armazenada enquanto o servidor desperta; a primeira visita após o repouso ainda pode levar cerca de um minuto.
