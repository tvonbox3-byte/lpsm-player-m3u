# LPSM 2.2.35 - Build 61

## Catálogo maior de Filmes e Séries

- Remove o teto fixo de 60.000 itens da carga principal.
- O limite agora se adapta à memória do aparelho: de 90.000 a 180.000 itens.
- Reserva apenas 5% para TV ao vivo e prioriza Filmes/Séries quando a lista é muito grande.
- Preserva categorias de filmes encontradas no fim da M3U, mesmo quando o teto de memória já foi alcançado.
- Aumenta a diversidade de séries mantidas em listas gigantes.
- Cache passa a aceitar até 180.000 itens e muda para o formato v8.
- A primeira abertura após atualizar refaz o cache; depois volta ao cache de 24 horas.

A proteção por memória evita usar 180 mil itens em boxes fracas: nelas o app usa um limite menor automaticamente.
