# LPSM 2.2.35 — Build 59

## Correção: app não depende do painel aberto

O backend está hospedado no plano gratuito do Render. Esse serviço pode entrar em repouso quando fica sem tráfego. Antes, abrir o painel web mantinha o backend ativo; com o painel fechado, o APK desistia antes do cold start terminar.

Nesta build:

- o próprio APK consulta `/api/health` e aguarda o backend acordar;
- o painel web não precisa permanecer aberto;
- o cache autorizado da M3U é restaurado antes da validação online;
- em aparelhos que já têm cache, a lista pode ser usada enquanto o backend sincroniza em segundo plano;
- conexões HTTP da API podem ser reaproveitadas em vez de forçar `Connection: close`;
- a cópia completa da playlist passa a ser considerada recente por 24 horas antes de uma nova atualização pesada;
- a versão visível continua 2.2.35; somente o `versionCode` passa para 59.

## Limitação do plano gratuito

Na primeira abertura após o backend entrar em repouso, um aparelho sem cache pode ainda precisar aguardar o cold start do servidor. Isso é uma limitação da hospedagem gratuita, não do painel. Depois que o app está aberto, o heartbeat do aparelho mantém tráfego no backend.
