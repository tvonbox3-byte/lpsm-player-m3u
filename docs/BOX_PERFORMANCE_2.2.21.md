# LPSM 2.2.21 — correção de carregamento em TV Box

Esta revisão ataca o caso em que celulares abrem rapidamente, mas algumas TV Boxes ficam muito tempo em **Carregando**.

Mudanças:

- reduz os timeouts do painel e da playlist para evitar espera de até 90 segundos por uma única leitura;
- usa apenas 2 workers em aparelhos Android classificados como low-RAM, evitando disputa de CPU/RAM;
- elimina a segunda leitura/descompactação do mesmo cache durante a inicialização;
- elimina a segunda indexação do cache quando a Home já foi aberta pelo modo rápido;
- em low-RAM, dá 900 ms para a Home terminar a primeira renderização antes de iniciar a atualização pesada da M3U;
- mantém cache, categorias, filmes, séries, TV ao vivo, EPG e atualização em segundo plano.

Versão Android: `2.2.21` / `versionCode 38`.
