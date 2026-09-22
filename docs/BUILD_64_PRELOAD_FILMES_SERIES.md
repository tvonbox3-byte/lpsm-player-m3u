# LPSM 2.2.35 Build 64

Correções principais:

- Reverte a lógica agressiva da Build 63 que podia deixar Filmes e Séries presos/vazios em listas grandes.
- Mantém a correção dos canais HLS/24h para não cair em Séries.
- Na primeira carga sem cache, a HOME só abre depois que canais, filmes e séries foram lidos e indexados.
- Com cache válido de 24 horas, a HOME continua abrindo já preenchida.
- A indexação de Séries preserva várias categorias em uma única passagem, sem criar `groupBy` gigante em memória.
- Mantém a ordem de categorias de TV: A Fazenda, Big Brother/BBB, Jogos de Hoje, RS/RBS e canais abertos.
- Mantém as contagens visíveis por seção/categoria.

Observação: a primeira abertura desta build refaz o cache uma vez (formato v11).
