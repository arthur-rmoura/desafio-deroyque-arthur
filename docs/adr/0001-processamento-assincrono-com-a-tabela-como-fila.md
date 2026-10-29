# ADR 0001 — Processamento assíncrono usando a própria tabela como fila

- **Status:** aceita
- **Data:** 2026-10-28

## Contexto

Depois de registrar uma solicitação, o serviço precisa chamar o provedor externo de ativação. O provedor
tem latência variável (de 400 ms a vários segundos em degradação) e pode falhar. Precisamos de:

1. tempo de resposta da API independente da latência do provedor;
2. nenhuma chamada HTTP com transação de banco aberta;
3. garantia de que toda solicitação gravada seja enviada, mesmo se a aplicação cair entre o commit e a chamada;
4. funcionar com várias instâncias ao mesmo tempo.

## Decisão

- O `POST` grava a solicitação como `PENDING`, com `next_attempt_at = agora`, e responde `201` em seguida.
- Logo após o commit, a solicitação é enfileirada em um pool de threads limitado (otimização de latência).
- Uma varredura periódica (`ActivationPoller`) busca as linhas com `next_attempt_at <= agora` e as envia.
  É a garantia de entrega: cobre fila cheia, queda da instância e retries agendados.
- Cada envio tem três passos, cada um com sua transação curta:
  1. **Reivindicar:** `PENDING|PROCESSING → PROCESSING`, `attempts++` e `next_attempt_at = agora + lease`,
     com lock otimista (`@Version`). Se duas instâncias tentarem ao mesmo tempo, só uma vence.
  2. **Chamar o provedor** (sem transação), enviando o id da solicitação como `Idempotency-Key`.
  3. **Gravar o resultado**, mas só se a tentativa ainda for a corrente (`attempts` igual ao reivindicado).
- Se a instância cair durante a chamada, o lease expira e a solicitação volta a ser elegível. A
  reconciliação de solicitações presas em `PROCESSING` acontece sem nenhum processo separado.

## Alternativas consideradas

- **Chamada síncrona dentro do POST:** a latência da API passa a seguir a do provedor e a disponibilidade da
  API passa a depender dele. Descartada.
- **Broker de mensagens (Kafka/SQS/RabbitMQ):** é a escolha natural em escala maior, mas exigiria um outbox
  para publicar a mensagem de forma atômica com o insert. Fica como evolução (ver README).
- **`SELECT ... FOR UPDATE SKIP LOCKED`:** reduziria reivindicações concorrentes em vão. Foi preterido por
  depender de recurso específico do banco (os testes rápidos usam H2). Com o volume esperado, o lock
  otimista basta.

## Consequências

- A latência da API independe do provedor; o consumidor acompanha o status pelo `GET`.
- A entrega é "pelo menos uma vez". A duplicidade no provedor é evitada pela chave de idempotência.
- O lease precisa ser maior que o tempo máximo de uma chamada (`connect-timeout + read-timeout`); a
  aplicação valida isso na inicialização.
