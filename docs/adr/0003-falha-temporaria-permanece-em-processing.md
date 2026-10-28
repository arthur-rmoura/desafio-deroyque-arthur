# ADR 0003 — Falha temporária mantém a solicitação em `PROCESSING`

- **Status:** aceita
- **Data:** 2026-10-28

## Contexto

O enunciado pergunta se, após uma falha temporária do provedor, a solicitação volta para `PENDING` ou
permanece em `PROCESSING` com contador de tentativas.

## Decisão

Permanece em `PROCESSING`, com `attempts` (tentativas feitas), `next_attempt_at` (quando tentar de novo)
e `failure_reason` (último erro, visível no `GET`). Ao atingir o máximo de tentativas, vai para `FAILED`
com o motivo `MAX_ATTEMPTS_EXCEEDED (último erro: ...)`.

## Motivos

- **Semântica para o consumidor:** depois da primeira tentativa, a solicitação já está sendo processada.
  Voltar a `PENDING` sugere que nada aconteceu, mas talvez o provedor até tenha ativado (timeout é ambíguo).
- **Máquina de estados sem retrocesso:** as transições são só para frente
  (`PENDING → PROCESSING → COMPLETED|FAILED`), mais simples de validar e de entender.
- **Mesmo mecanismo para retry e recuperação:** `next_attempt_at` serve tanto para o backoff quanto para o
  lease de uma tentativa em andamento. A varredura não precisa distinguir os dois casos.

## Consequências

- `PENDING` significa "ainda não foi enviada ao provedor"; `PROCESSING` significa "enviada pelo menos uma
  vez, aguardando resultado definitivo".
- Para saber quantas solicitações estão em retry, é preciso olhar `attempts > 1` ou `failure_reason`, não
  apenas o status.
