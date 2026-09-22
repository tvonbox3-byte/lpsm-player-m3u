# LPSM 2.2.35 Build 63

Correções sem remover recursos das Builds 59-62:

- Séries preservam todas as categorias de origem: uma mesma série pode aparecer em mais de uma pasta sem duplicar em "Tudo".
- Quando o catálogo atinge o limite de memória, o parser reserva presença para categorias de séries que aparecem mais tarde.
- Canais HLS (`.m3u8`) com `tvg-id`/perfil de canal permanecem em TV ao Vivo, mesmo quando o fornecedor usa uma pasta chamada "Séries".
- Contagem visível no topo do catálogo: `Tudo (N)` ou `Categoria (N)`, além dos números na coluna esquerda.
- Em TV ao Vivo, categorias contendo **A Fazenda** e **Big Brother/BBB** ficam no topo quando existirem; depois vêm Jogos de Hoje, RS/RBS e canais abertos.
- Cache v10 força apenas uma reconstrução após instalar a Build 63 para aplicar a nova classificação; depois continua com validade de 24 horas.

A grade, player, favoritos, PIN adulto, rádios, painel e demais recursos existentes foram preservados.
