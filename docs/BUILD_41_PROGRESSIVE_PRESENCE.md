# LPSM 2.2.23 build 41

## M3U progressiva
- A Home continua abrindo imediatamente.
- O parser libera uma primeira amostra com 800 itens, depois 4.000 e 12.000 itens enquanto continua processando a lista completa.
- TV Boxes lentas deixam de ficar com as seções totalmente vazias durante a leitura de listas grandes.
- A tela informa quantos itens já estão disponíveis e, no fim, quantos foram carregados.

## Presença no painel
- Heartbeat do APK: 10 segundos.
- Painel atualiza presença a cada 2 segundos enquanto estiver visível.
- Janela de online no backend: 30 segundos.
- A consulta de configuração/ativação continua marcando o aparelho online imediatamente no servidor.

## Segurança
- Tokens e a configuração do aparelho continuam armazenados no Android Keystore/EncryptedSharedPreferences quando suportado.
- Nenhuma VPN de evasão foi incorporada nesta build. Uma VPN real exige servidor/provedor e credenciais próprios e não deve ser usada para contornar bloqueios regulatórios ou acessar conteúdo não autorizado.
