# Estado da entrega

## Implementado

- Ativação por ID do dispositivo e código.
- Tokens assinados, senha administrativa com scrypt e preferências criptografadas no Android.
- Limitação de tentativas no login/ativação, sessão administrativa temporária e cabeçalhos de segurança no painel.
- Atualizações Android assinadas e validação SHA-256 do APK a partir da versão 2.2.16.
- Ativar/desativar e expirar clientes e listas.
- Vínculo de uma ou mais listas por cliente.
- Banner, papel de parede e mensagem configuráveis na API/painel.
- Parser M3U, classificação Live/VOD/Series, pesquisa e favoritos.
- XMLTV com programa atual quando `tvg-id` corresponde ao canal.
- Media3/ExoPlayer com HLS e controles nativos.
- Layout responsivo para celular, TV e controle remoto.
- Logs administrativos básicos e armazenamento persistente.
- Render/Supabase, HTTPS automático e documentação de publicação.

## Validação realizada neste ambiente

- Testes automatizados de hash e assinatura de token.
- Verificação de sintaxe do backend e do painel.
- Teste integrado real: saúde, login, autorização administrativa, limitação após oito senhas inválidas e cabeçalhos de segurança.

## Publicação

O GitHub Actions gera e assina o APK de lançamento. O Render publica o backend automaticamente quando a branch `main` recebe uma atualização.

Antes de crescer para muitos clientes, use uma instância que não entre em repouso, mantenha a senha do Render forte e faça backup periódico do banco Supabase.
