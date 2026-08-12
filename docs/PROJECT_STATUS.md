# Estado da entrega

## Implementado

- Ativação por ID do dispositivo e código.
- Tokens assinados, senha administrativa com scrypt e preferências criptografadas no Android.
- Ativar/desativar e expirar clientes e listas.
- Vínculo de uma ou mais listas por cliente.
- Banner, papel de parede e mensagem configuráveis na API/painel.
- Parser M3U, classificação Live/VOD/Series, pesquisa e favoritos.
- XMLTV com programa atual quando `tvg-id` corresponde ao canal.
- Media3/ExoPlayer com HLS e controles nativos.
- Layout responsivo para celular, TV e controle remoto.
- Logs administrativos básicos e armazenamento persistente.
- Docker, HTTPS automático e documentação de publicação.

## Validação realizada neste ambiente

- Testes automatizados de hash e assinatura de token.
- Verificação de sintaxe do backend e do painel.
- Teste integrado real: login, criação de lista, criação de cliente, ativação e leitura da configuração.

## Dependência externa pendente

O ambiente de geração não possui Android SDK, JDK ou Gradle. Por isso o APK não foi produzido aqui. O código está pronto para sincronização no Android Studio; depois de instalar SDK 35 e JDK 17, gere com `assembleDebug` conforme `ANDROID.md`.

Antes de produção, substitua o armazenamento JSON por PostgreSQL se houver alta concorrência, adicione recuperação/rotação de senha e realize testes em modelos reais de Android TV/TV Box.
