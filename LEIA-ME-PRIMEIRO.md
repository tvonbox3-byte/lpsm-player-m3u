# LPSM — informações principais

## Links

- Painel: https://lpsm-player-backend.onrender.com/
- Código: https://github.com/tvonbox3-byte/lpsm-player-m3u
- APK mais recente: https://github.com/tvonbox3-byte/lpsm-player-m3u/releases/latest/download/LPSM-Player.apk
- Atualizações: https://github.com/tvonbox3-byte/lpsm-player-m3u/actions

## Pastas

- `android` — aplicativo Android/Android TV.
- `backend` — painel web e API de ativação.
- `docs` — instruções técnicas e de publicação.
- `.github/workflows` — geração automática do APK assinado.
- `APK` — cópia do APK atual no pacote local organizado (não é enviada ao código-fonte).

## Publicação automática

Uma alteração enviada para a branch `main` publica o backend no Render. Quando houver alteração dentro de `android`, o GitHub Actions também gera um APK assinado e cria uma nova versão. Sempre aumente `versionCode` e `versionName` antes de publicar uma atualização do aplicativo.

## Segurança

- Nunca compartilhe `ADMIN_PASSWORD`, `TOKEN_SECRET`, `SUPABASE_SECRET_KEY` nem o arquivo de assinatura `.jks`.
- Use uma senha exclusiva no painel e troque-a imediatamente se alguém tiver acesso.
- Faça backup do Supabase.
- Use somente listas e conteúdo autorizados.
- O projeto não possui mecanismos de evasão de bloqueios regulatórios.

## Render gratuito

O servidor gratuito pode dormir depois de 15 minutos sem acesso. O cache local permite que o painel apareça imediatamente nas visitas seguintes, mas a API ainda precisa despertar. Para eliminar a espera inclusive na primeira visita, use uma instância Render que não durma ou hospede a interface separadamente como site estático.
