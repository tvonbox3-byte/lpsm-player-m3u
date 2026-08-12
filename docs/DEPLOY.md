# Deploy do painel

## Requisitos

- Um domínio apontando para o servidor.
- Docker com Compose.
- Portas 80 e 443 acessíveis.

Copie `deploy/.env.example` para `deploy/.env`, troque todos os valores e execute, a partir de `deploy`:

```bash
docker compose up -d --build
```

Caddy solicitará e renovará o certificado HTTPS. Os dados ficam no volume `lpsm_data`. Faça backup periódico desse volume e teste a restauração.

## Operação segura

- Nunca mantenha as credenciais de desenvolvimento.
- Use um segredo de token aleatório com pelo menos 32 bytes.
- Restrinja o painel por firewall ou acesso corporativo quando possível.
- Atualize a imagem base e reconstrua regularmente.
- Use URLs M3U/XMLTV HTTPS de provedores autorizados. O backend não valida titularidade do conteúdo; essa responsabilidade permanece com o administrador.
- Para múltiplas instâncias ou operação em larga escala, substitua o armazenamento JSON por PostgreSQL e adicione limitação distribuída de requisições, rotação de tokens e observabilidade.

O painel serve os metadados de listas aos dispositivos autorizados, mas não faz proxy de streaming. Isso reduz custo e evita que o servidor seja usado como retransmissor.
