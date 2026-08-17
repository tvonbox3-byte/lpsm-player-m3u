# LPSM 2.2.23 Build 46

- YouTube permanece dentro do LPSM em WebView modo quiosque.
- Bloqueia intent://, market://, navegador externo, popups e destinos fora do YouTube.
- YouTube foi movido para uma segunda linha na Home para não alargar os botões.
- App envia nome/grupo/tipo/URL do conteúdo atual no heartbeat.
- Painel exibe “Assistindo agora” e botão “Ver agora” para clientes online.
- Monitor abre em página separada e tenta reproduzir o mesmo stream.
- A URL do stream não é enviada no estado geral do painel; só no endpoint autenticado do monitor.
- O monitor reproduz a mesma fonte, não captura a tela física do cliente.
