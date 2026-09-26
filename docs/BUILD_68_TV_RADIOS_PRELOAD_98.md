# Build 68 — TV ao vivo + Rádios / pré-carga 98%

- Mantém somente TV ao vivo e Rádios no LPSM.
- Filmes e Séries continuam fora para manter TV Boxes leves.
- Sem cache, não abre mais com uma amostra de 80 canais: prepara praticamente todo o bloco LIVE antes de liberar a tela.
- Em M3U padrão, encerra a varredura após 4.000 itens não-LIVE consecutivos depois dos canais, evitando percorrer catálogos gigantes de VOD/Séries.
- Com cache válido de 24h abre imediatamente com a lista salva.
- Rádios voltam à navegação e mantêm cache/reconexão automática já existentes.
- Voltar a partir de Rádios retorna para TV ao vivo.
