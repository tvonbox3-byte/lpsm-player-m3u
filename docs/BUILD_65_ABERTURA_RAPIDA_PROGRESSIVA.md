# LPSM PLAYER 2.2.35 - Build 65

## Abertura rápida sem loading infinito

A Build 64 esperava a leitura e indexação do catálogo completo antes de liberar a interface. Em listas muito grandes isso podia manter a TV Box presa em "Carregando" por vários minutos.

A Build 65 volta a liberar a interface de forma progressiva, mas sem reconstruir índices continuamente:

- primeira amostra liberada a partir de ~80 canais ou ~120 itens;
- Filmes e Séries recebem uma atualização quando cada seção começa a ter conteúdo;
- a amostra rápida é limitada a poucos milhares de itens para não travar CPU/RAM;
- o catálogo completo continua carregando em segundo plano e substitui a amostra ao terminar;
- cache válido de 24 horas continua abrindo imediatamente já preenchido;
- nenhuma alteração no player/canais que já estavam funcionando na Build 64.

Observação: o catálogo completo pode levar mais tempo dependendo do tamanho da M3U e da velocidade do servidor. O objetivo desta build é não deixar o usuário preso na tela de carregamento enquanto isso acontece.
