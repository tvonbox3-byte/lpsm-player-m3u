# LPSM 2.2.23 - Fast Start para TV Box

Mudancas principais:

- A HOME nao espera mais a descompactacao/agrupamento da M3U.
- A configuracao local autorizada libera a interface imediatamente.
- Na primeira abertura, a HOME e liberada assim que o painel responde; a M3U segue em segundo plano.
- Cache da playlist passou de `cacheDir` para `filesDir`, reduzindo perdas automaticas do cache em TV Boxes com pouco armazenamento.
- Cache antigo e migrado automaticamente quando existir.
- Download, parse, indexacao e EPG continuam fora da thread da interface.
- Mantidas as melhorias de radios da 2.2.22.

Objetivo: separar tempo de abertura da interface do tempo de processamento de listas grandes.
