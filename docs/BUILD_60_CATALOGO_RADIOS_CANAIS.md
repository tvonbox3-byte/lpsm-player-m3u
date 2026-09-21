# LPSM 2.2.35 — Build 60

Correções desta build:

- Filmes e Séries: leitura mais robusta de `group-title`, `stream-type`, `type` e URLs de VOD/Séries.
- Corrige um caso em que uma categoria pequena misturando filme e episódio podia transformar filme em série.
- Várias listas no mesmo MAC: a primeira URL não pode mais consumir sozinha todo o limite de catálogo; o espaço é dividido entre as listas restantes para TV/Filmes/Séries serem lidos.
- Cache M3U sobe para formato v7, forçando uma leitura limpa após as correções de classificação.
- Canais aparecem mais cedo: primeira entrega progressiva a partir de cerca de 300 canais/500 itens e prévia ao focar reduzida de 450 ms para 300 ms.
- Rádio: buffer dedicado maior e reconexão automática em até 4 tentativas quando a emissora pública oscila.
- A mensagem de status agora informa quantos canais, filmes e episódios foram realmente identificados.

Versão visível permanece `2.2.35`; `versionCode` = `60`.
