# Build 66 - Painel leve / app independente

Diagnóstico: o painel recarregava `/api/admin/state` inteiro a cada 2 segundos.
Esse endpoint inclui clientes, fontes, aparência, auditoria e presença, e o navegador
reconstruía grande parte do painel em cada ciclo. Em uma instância pequena do Render,
isso pode competir por CPU/rede com as requisições dos aparelhos.

Mudanças:
- Novo `GET /api/admin/presence`, que retorna somente presença e "assistindo agora".
- O painel carrega `/api/admin/state` completo apenas quando necessário.
- Presença passa a atualizar a cada 5 segundos pelo endpoint leve.
- Ao voltar para a aba do painel, a presença atualiza imediatamente.
- O heartbeat do APK continua separado e não grava no Supabase a cada sinal.
- Nenhuma lógica de canais, filmes, séries, player ou cache Android foi alterada nesta correção.

Objetivo: abrir/deixar o painel aberto não deve interferir no funcionamento do aplicativo.
