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


## Build 62 - Filmes e Séries

Reconhece mais formatos de categoria, evita canais ao vivo dentro de Séries, amplia o catálogo e deixa a grade de capas mais compacta/rápida.

## Build 63
Preserva múltiplas categorias de séries, exibe contagens do catálogo, evita canais HLS dentro de Séries e prioriza A Fazenda/Big Brother/BBB nas categorias ao vivo.

## Build 64 - preload de catálogo

A primeira abertura sem cache aguarda a leitura e indexação completa do catálogo antes de liberar a HOME. Com cache válido de 24 horas, o conteúdo abre já carregado. A classificação de Filmes/Séries voltou à base estável da Build 62, mantendo as correções de canais e a prioridade de realities/RS.
