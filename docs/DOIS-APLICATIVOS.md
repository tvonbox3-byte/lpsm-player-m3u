# LPSM: dois aplicativos

| Aplicativo | Conteúdo | Identificador Android | Versão |
|---|---|---|---|
| LPSM | Canais ao vivo e rádios | com.lpsm.player | 2.2.36 (67) |
| LPSM Filmes e Séries | Filmes e séries | com.lpsm.cinema | 1.0.0 (1) |

Os dois podem ser instalados no mesmo aparelho. O primeiro atualiza o LPSM existente, com a assinatura original. O segundo é uma instalação independente e apresenta seu próprio identificador para ativação no mesmo painel. As mesmas listas M3U podem ser vinculadas aos dois cadastros; cada aplicativo seleciona apenas os tipos que atende.

A divisão usa duas variantes de um único projeto Android para compartilhar correções de estabilidade e player. Não é necessário manter cópias divergentes do código. As variantes possuem instalação, favoritos, cache e autorização independentes. Os dados antigos do LPSM permanecem na instalação original; não são transferidos automaticamente para o novo app.

O parser descarta conteúdo da outra variante antes de ocupar o limite do catálogo. A cópia local antiga também é filtrada ao ser aberta. Menus, pesquisa, favoritos e navegação por controle usam o catálogo correspondente.

## Compilação assinada

Executar `assembleLiveRelease assembleCinemaRelease testLiveReleaseUnitTest testCinemaReleaseUnitTest lintLiveRelease lintCinemaRelease`, usando as quatro variáveis de assinatura já existentes. O fluxo de preparação no GitHub verifica o certificado original dos dois APKs e seus respectivos identificadores antes de entregar os arquivos.

- `LPSM-Player.apk`: atualização dos clientes de canais/rádios.
- `LPSM-Cinema.apk`: instalação do aplicativo de filmes/séries.

Não distribuir os arquivos locais com sufixo `unsigned`; servem somente para verificar a compilação sem a chave privada.

## Atualizações independentes

O aplicativo original usa `update.json` e a consulta ao painel existente. O aplicativo de filmes/séries usa exclusivamente `update-cinema.json` no GitHub Releases. Uma futura publicação deve conter os dois APKs e seus dois arquivos de atualização, com o SHA-256 de cada APK. Os fluxos preparados geram artefatos assinados, sem publicação automática.

As correções de recuperação de catálogo, tela ativa enquanto o app está aberto, retomada da prévia e redução do uso de memória do EPG são compartilhadas. Não há validação em TV Box física conectada neste ambiente.
