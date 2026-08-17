# LPSM 2.2.23 - Build 42

## Correcao: controle remoto / favoritos em TV Box

- Mantem a versao visivel 2.2.23.
- Sobe somente `versionCode` para 42.
- Remove a recriacao completa da tela (`render()`) imediatamente depois de favoritar.
- Atualiza somente o item favoritado no proximo ciclo da interface.
- Protege a operacao de favorito contra falhas de firmwares Android TV antigos.
- Persiste o favorito antes de continuar o evento do controle remoto.
- Mantem as correcoes de M3U progressiva e presenca online da build 41.
