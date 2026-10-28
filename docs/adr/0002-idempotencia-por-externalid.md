# ADR 0002 — Idempotência da criação por `externalId`

- **Status:** aceita
- **Data:** 2026-10-28

## Contexto

Sistemas de origem fazem retry e podem enviar a mesma solicitação duas vezes quase ao mesmo tempo. O
serviço não pode criar dois registros com o mesmo `externalId`.

"Consultar se existe e, se não existir, inserir" não basta: duas requisições simultâneas podem ambas
consultar, não encontrar nada e ambas inserir. Nenhum nível de isolamento comum (READ COMMITTED, o padrão
do PostgreSQL) impede isso, porque não existe linha para bloquear.

## Decisão

- **Constraint única em `external_id`** no banco: é a garantia. O segundo insert falha, mesmo vindo de
  outra instância da aplicação.
- A consulta prévia continua existindo só como atalho para o caso comum (retry sequencial), evitando um
  insert que falharia.
- Quando o insert falha por violação de unicidade, a requisição é tratada como repetição: a solicitação
  vencedora é lida em uma nova transação e comparada com a recebida.

| Cenário | Resposta |
|---|---|
| `externalId` novo | `201 Created` + `Location` |
| Mesmo `externalId`, mesmo conteúdo e mesmo consumidor (retry) | `200 OK` com o registro existente e header `Idempotent-Replayed: true` |
| Mesmo `externalId`, conteúdo diferente | `409 Conflict` (Problem Details `duplicate-request`) |
| Mesmo `externalId` vindo de outro consumidor | `409 Conflict`, sem devolver o registro do outro consumidor |
| Duas requisições idênticas em paralelo | uma recebe `201`; a outra espera o commit da primeira (lock do índice único) e recebe `200` com o mesmo registro |

## Por que `200` no retry, e não `409`

O retry legítimo é a mesma intenção reenviada; o cliente precisa receber o resultado da operação, não um
erro. Com `409`, todo cliente teria que tratar "conflito" como sucesso e fazer um `GET` extra. O `200`
(em vez de `201`) e o header `Idempotent-Replayed` ainda permitem diferenciar os casos.

## Consequências

- A unicidade vale entre instâncias e sob qualquer concorrência, sem lock distribuído.
- O `externalId` é global, como pede o enunciado. Se sistemas de origem diferentes puderem gerar o mesmo
  identificador, a unicidade deveria passar a ser `(client_id, external_id)`; a mudança se resume à
  constraint e à consulta.
