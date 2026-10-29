# Activation Service

Serviço de ativação de produtos de clientes. Ele registra solicitações de ativação, permite consultar o
status e faz a ativação por meio de um provedor externo.

**Stack:** Java 21 · Spring Boot 3.5 (Spring MVC, Spring Data JPA/Hibernate, Spring Security OAuth2
Resource Server, Actuator) · PostgreSQL · Flyway · Maven · Docker.

## Sumário

- [Como executar](#como-executar)
- [Visão geral da solução](#visão-geral-da-solução)
- [Decisões técnicas](#decisões-técnicas)
  - [API e domínio](#api-e-domínio)
  - [Estratégia contra duplicidade (item 3)](#estratégia-contra-duplicidade-item-3)
  - [Integração com o provedor (item 5)](#integração-com-o-provedor-item-5)
  - [Tratamento de erros (item 6)](#tratamento-de-erros-item-6)
  - [Segurança (item 7)](#segurança-item-7)
  - [Observabilidade](#observabilidade)
  - [Estratégia de testes (item 8)](#estratégia-de-testes-item-8)
- [Cenário de análise (item 10)](#cenário-de-análise-item-10)
- [Produção (item 11)](#produção-item-11)
- [Microsserviços e cloud](#microsserviços-e-cloud)
- [Pontos pendentes](#pontos-pendentes)
- [Melhorias futuras](#melhorias-futuras)
- [Uso de IA](#uso-de-ia)

---

## Como executar

### Pré-requisitos

- **JDK 21** (para build e testes locais)
- **Docker** (para o ambiente completo e para os testes com Testcontainers)

### Build e testes (um comando)

```bash
./mvnw verify          # Linux/macOS
mvnw.cmd verify        # Windows
```

Esse comando roda os testes unitários (`*Test`, surefire) e os de integração (`*IT`, failsafe). O teste de
concorrência contra PostgreSQL usa Testcontainers e é **ignorado automaticamente se não houver Docker**;
no CI ele sempre roda.

### Ambiente completo (aplicação + PostgreSQL + provedor simulado + OAuth2 simulado)

```bash
docker compose up --build
```

| Serviço | Endereço | Função |
|---|---|---|
| API | http://localhost:8080 | `/api/v1/requests`, Swagger em `/swagger-ui.html` |
| Actuator | http://localhost:8081/actuator | `health/liveness`, `health/readiness`, `prometheus` |
| Provedor simulado (WireMock) | http://localhost:8089 | `POST /provider/v1/activate` |
| OAuth2 simulado | http://localhost:8090/activation | emite JWT via *client credentials* |
| PostgreSQL | localhost:5432 | banco `activation` (usuário e senha `activation`) |

#### 1. Obter um token (fluxo *client credentials*)

```bash
TOKEN=$(curl -s -X POST http://localhost:8090/activation/token \
  -d grant_type=client_credentials \
  -d client_id=crm-system -d client_secret=qualquer \
  -d "scope=activation:read activation:write" | jq -r .access_token)
```

O `client_id` vira o `sub` do token e identifica o sistema consumidor. Com outro `client_id`, você
consegue ver o isolamento entre consumidores. Peça só `scope=activation:read` para ver o `403` na criação.

#### 2. Criar e consultar

```bash
curl -i -X POST http://localhost:8080/api/v1/requests \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}'

curl -s http://localhost:8080/api/v1/requests/<id> -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/requests?status=COMPLETED&page=0&size=20" -H "Authorization: Bearer $TOKEN"
```

O provedor simulado escolhe o comportamento pelo `productCode`, para demonstrar cada cenário:

| `productCode` | Provedor responde | Resultado esperado |
|---|---|---|
| qualquer outro | `200` | `COMPLETED` |
| `INVALID-PRODUCT` | `400` | `FAILED` (`failureReason: HTTP_400`) |
| `UNSTABLE-PRODUCT` | `500` | `PROCESSING` com retries e backoff; `FAILED` após 5 tentativas |
| `SLOW-PRODUCT` | `200` após 10 s | timeout de leitura (5 s) → retries → `FAILED` |
| `THROTTLED-PRODUCT` | `429` + `Retry-After: 30` | nova tentativa respeitando os 30 s |
| `UNAVAILABLE-PRODUCT` | `503` + `Retry-After: 10` | nova tentativa respeitando os 10 s |

#### 3. Ou pelo Postman

Importe [`activation-service.postman_collection.json`](activation-service.postman_collection.json), na raiz do
projeto (e, se quiser, o environment `postman/local.postman_environment.json`). Com o `docker compose` no ar, a collection
**obtém os tokens sozinha** (script de pre-request, *client credentials*) para três consumidores: `crm-system`
(leitura e escrita), `bi-system` (só leitura) e `billing-system` (outro consumidor). As 35 requisições estão
organizadas por cenário, cada uma com descrição e testes:

| Pasta | Cenários |
|---|---|
| 0. Preparação | nova rodada (gera `externalId` novos), token com inspeção dos claims |
| 1. Criação e duplicidade | 201, retry legítimo (200), conteúdo divergente (409), validações (400) |
| 2. Consulta e listagem | por id, 404, id inválido, filtros e paginação, limites |
| 3. Cenários do provedor | um par criar → consultar para cada comportamento (400, 500, timeout, 429, 503) |
| 4. Segurança | 401 sem token ou com token inválido, 403 por escopo, isolamento entre consumidores |
| 5. Operação | liveness, readiness, métricas Prometheus, OpenAPI |

Rode a collection inteira pelo *Collection Runner* (as pastas 2 e 4 usam a solicitação criada na 1) ou
requisição por requisição. Para repetir os cenários de criação, use *0. Preparação → Iniciar nova rodada*.

Também dá para rodar pela linha de comando com o [Newman](https://github.com/postmanlabs/newman) (Node 18+):

```bash
npx newman@6 run activation-service.postman_collection.json -e postman/local.postman_environment.json
```

Os cenários com retry (pasta 3) ainda estão em `PROCESSING` ao fim da rodada; repita as consultas após ~40 s
para ver o estado final.

### Executar só a aplicação pela IDE ou pelo Maven

Suba as dependências com `docker compose up postgres provider-mock oauth2-mock` e rode
`./mvnw spring-boot:run`. Os valores padrão do `application.yml` apontam para esses contêineres.

---

## Visão geral da solução

```
com.deroyque.activation
├── api             Controllers REST, DTOs (records) e tradução de exceções em Problem Details
├── application     Casos de uso: criação idempotente, despacho ao provedor, retry, varredura
├── domain          Entidade ActivationRequest (máquina de estados), status, repositório
├── infrastructure  Cliente HTTP do provedor, segurança, filtro de correlação, configuração
└── shared          Correlation id e fábrica de Problem Details
```

As regras de dependência são verificadas por teste (ArchUnit): o domínio não conhece as outras camadas, a
aplicação não conhece HTTP nem a infraestrutura (fala com o provedor por uma interface, `ActivationProvider`)
e a API não acessa repositórios diretamente.

Mantive poucas camadas de propósito, como pede o enunciado. O repositório Spring Data fica no domínio e
não existe "porta" para ele: trocar o JPA não é uma necessidade real. A única abstração criada foi a do
provedor externo, porque é a fronteira que muda (protocolo, autenticação) e que precisa ser simulada nos testes.

```
POST /api/v1/requests
   │  valida, grava PENDING (constraint única em external_id), responde 201
   ▼
[ActivationDispatcher] ◄── pool limitado (logo após o commit)  ◄── [ActivationPoller] (varredura a cada 5 s)
   │ 1. reivindica: PROCESSING, attempts++, lease (transação curta + @Version)
   │ 2. POST /provider/v1/activate  (sem transação; Idempotency-Key = id)
   │ 3. grava o resultado: COMPLETED | FAILED | PROCESSING com retry agendado (transação curta)
```

Registros de decisão (ADRs) em [`docs/adr`](docs/adr).

---

## Decisões técnicas

### API e domínio

- **Recursos modernos do Java:** records para DTOs, comandos e resultados; `sealed interface` +
  `switch` com pattern matching para o resultado do provedor (`ProviderResult`). O compilador garante que
  todo resultado é tratado.
- **DTOs separados da entidade JPA:** a API nunca serializa a entidade.
- **`201 Created` com `Location`**, datas em **UTC, ISO-8601** (`Instant`, colunas `timestamp with time zone`).
- **Regras de validação** (também publicadas no OpenAPI/Swagger):

  | Campo | Regra |
  |---|---|
  | `externalId`, `customerId`, `productCode` | obrigatório; até 64 caracteres; apenas letras, números e `. _ : -` |
  | `page` / `size` | `page >= 0`; `1 <= size <= 100` (padrão 20) |
  | `status` | um de `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` |

- **Transições controladas pelo domínio:** não há setters. `ActivationRequest` expõe `startAttempt`,
  `complete`, `fail` e `scheduleRetry`, que validam a transição e lançam `InvalidStatusTransitionException`
  quando ela é inválida (ex.: `COMPLETED` nunca volta a `PROCESSING`). Coberto por teste unitário sem Spring.
- **Falha temporária permanece em `PROCESSING`** com contador de tentativas e próxima tentativa agendada
  ([ADR 0003](docs/adr/0003-falha-temporaria-permanece-em-processing.md)). A resposta expõe
  `failureReason` com o último erro.
- **Escrita concorrente:** `@Version` (lock otimista). Duas instâncias não conseguem reivindicar a mesma
  tentativa nem sobrescrever o resultado uma da outra.

#### Listagem e índices

`GET /api/v1/requests?status=&customerId=&page=&size=` sempre filtra pelo consumidor autenticado e ordena
por `created_at desc`. Índices criados na migração:

| Índice | Atende |
|---|---|
| `(client_id, status, created_at desc)` | listagem por status: busca por faixa já na ordem pedida, sem sort |
| `(client_id, customer_id, created_at desc)` | listagem por cliente (e por cliente + status, filtrando o status sobre poucas linhas) |
| `unique (external_id)` | garantia contra duplicidade e busca por `externalId` |
| `(next_attempt_at)` | varredura de solicitações a enviar (estados finais têm valor nulo) |

Listar sem filtro usa o prefixo `client_id` dos índices, mas precisa ordenar. Se algum consumidor tiver
volume muito grande, valeria um `(client_id, created_at desc)`. Não criei esse índice por enquanto, porque
cada índice também encarece as escritas. No PostgreSQL, o índice da varredura poderia ser parcial
(`where next_attempt_at is not null`); mantive a migração compatível com o H2 dos testes.

### Estratégia contra duplicidade (item 3)

Detalhes em [ADR 0002](docs/adr/0002-idempotencia-por-externalid.md).

**Por que verificar e depois inserir não basta:** duas requisições simultâneas podem fazer a consulta antes
de qualquer uma inserir; as duas não encontram nada e as duas inserem. Nenhum isolamento usual impede isso,
porque não existe linha para bloquear.

**Abordagem:** a **constraint única em `external_id`** é a garantia, válida entre instâncias e sob qualquer
concorrência. A consulta prévia serve só de atalho para o retry comum. Se o insert falha por unicidade, a
requisição é tratada como repetição: lê a solicitação vencedora e compara o conteúdo.

| Cenário | Resposta |
|---|---|
| Mesmo `externalId`, mesmo conteúdo (retry legítimo) | **`200 OK`** com o registro existente e header `Idempotent-Replayed: true`. É a mesma intenção, então o cliente recebe o resultado e não um erro; o `200` (e não `201`) indica que nada foi criado. |
| Mesmo `externalId`, conteúdo diferente | **`409 Conflict`**: `"O externalId 'EXT-12345' já foi utilizado em uma solicitação com dados diferentes."` |
| Mesmo `externalId` de outro consumidor | **`409 Conflict`**, sem devolver dados do outro consumidor |
| Duas requisições idênticas em paralelo | uma recebe `201`; a outra espera o commit da primeira (lock do índice único no PostgreSQL), falha na unicidade e recebe `200` com o mesmo registro |

O teste `PostgresConcurrentCreationIT` dispara 16 criações simultâneas com o mesmo `externalId` contra
PostgreSQL real e verifica exatamente um registro, um `created=true` e o mesmo id em todas as respostas.

### Integração com o provedor (item 5)

Detalhes em [ADR 0001](docs/adr/0001-processamento-assincrono-com-a-tabela-como-fila.md).

**Assíncrono.** O `POST` grava a solicitação e responde `201` com `PENDING`; o envio ao provedor acontece em
background. Se fosse síncrono, a latência da API seguiria a do provedor (400 ms → 3 s em degradação), e um
provedor lento ocuparia threads do Tomcat e conexões até derrubar a própria API.

**Transação × chamada remota.** O envio tem três passos com transações curtas e separadas: reivindicar →
chamar (sem transação) → gravar o resultado. Nenhuma conexão de banco fica presa esperando o provedor.

**Garantia de envio mesmo com queda entre o commit e a chamada.** A própria tabela funciona como fila
(padrão *outbox*): toda solicitação nasce com `next_attempt_at = agora`. O despacho logo após o commit é só
uma otimização; uma varredura periódica envia qualquer solicitação vencida. Se a instância cair *durante*
a chamada, a solicitação fica em `PROCESSING` com um lease (`next_attempt_at = início + 30 s`); quando ele
expira, outra instância a retoma. Esse é o **processo de reconciliação** para solicitações presas em
`PROCESSING`, sem job separado.

**Timeouts explícitos.** Conexão (2 s) e leitura (5 s) são configuráveis por variável de ambiente
(`PROVIDER_CONNECT_TIMEOUT`, `PROVIDER_READ_TIMEOUT`) no `RestClient` (cliente HTTP do JDK). Na
inicialização, a aplicação valida que o lease é maior que o tempo máximo de uma chamada.

**Timeout é ambíguo.** O provedor pode ter ativado sem conseguir responder. Toda chamada envia
`Idempotency-Key: <id da solicitação>`, a mesma em todas as tentativas, para o provedor deduplicar.
Também vai o `X-Correlation-Id`. *Premissa:* o provedor suporta chave de idempotência; se não suportar,
antes de repetir após timeout seria preciso consultar o status da ativação nele.

**Limite de tentativas e backoff.** No máximo 5 tentativas, com backoff exponencial e *jitter*
(2 s, 4 s, 8 s... até 5 min, sorteado entre metade e o total do intervalo, para que solicitações que falharam
juntas não voltem juntas). Quando o provedor manda `Retry-After`, o valor dele é respeitado (limitado ao
máximo). Ao atingir o limite, a solicitação vai para **`FAILED`** com
`failureReason = "MAX_ATTEMPTS_EXCEEDED (último erro: HTTP_500)"` e um log de erro (alertável).

| Retorno do provedor | Novo status | Nova tentativa? | Justificativa |
|---|---|---|---|
| Sucesso (2xx) | `COMPLETED` | Não | Ativação confirmada. |
| HTTP 400 (e demais 4xx: 404, 409, 422...) | `FAILED` | Não | O provedor recusou a solicitação; repetir dá o mesmo resultado. |
| HTTP 401 / 403 | `PROCESSING` | Sim, com backoff | O problema é a credencial *deste serviço* (expirada, rotacionada), não a solicitação. Falhar o pedido do cliente por erro nosso seria errado; o log de erro alerta o time. |
| HTTP 408 | `PROCESSING` | Sim, com backoff | Timeout do lado do provedor. |
| HTTP 500 (e demais 5xx) | `PROCESSING` | Sim, com backoff | Erro transitório do provedor. |
| Timeout (conexão ou leitura) | `PROCESSING` | Sim, com backoff e a **mesma `Idempotency-Key`** | O de conexão garante que nada chegou ao provedor; o de leitura é ambíguo, e a chave de idempotência evita ativar duas vezes. |
| Falha de conexão | `PROCESSING` | Sim, com backoff | Provedor ou rede indisponível. |
| HTTP 429 / 503 com `Retry-After` | `PROCESSING` | Sim, **após o `Retry-After`** (segundos ou data HTTP) | O provedor pediu para esperar; insistir antes só piora a sobrecarga. |
| Limite de tentativas atingido | `FAILED` | Não | `MAX_ATTEMPTS_EXCEEDED`; fica visível no `GET` e na métrica `activation.requests.finished{status=FAILED}`. |

**Resiliência: o que não implementei, e por quê.** Não usei circuit breaker (Resilience4j). Com
processamento assíncrono, pool limitado e backoff com jitter, um provedor fora do ar não derruba a API nem
gera tempestade de chamadas: as solicitações só se acumulam em `PROCESSING` e são retomadas depois. O
circuit breaker passaria a valer se o volume tornasse caras as chamadas inúteis. Nesse caso, eu o colocaria
em volta do `HttpActivationProvider` e, com o circuito aberto, pausaria a varredura em vez de consumir
tentativas das solicitações.

### Tratamento de erros (item 6)

Todos os erros seguem **Problem Details (RFC 9457)**, `application/problem+json`, com `correlationId`
(o mesmo devolvido no header `X-Correlation-Id` e registrado em todos os logs da requisição). Nunca vão
para o consumidor stack trace, nome de classe ou mensagem interna.

| Situação | Status | `type` |
|---|---|---|
| Dados inválidos (corpo ou parâmetros) | 400 | `validation-error` (com a lista `errors[{field, message}]`) |
| JSON malformado ou ausente | 400 | `malformed-request` |
| Registro inexistente (ou de outro consumidor) | 404 | `not-found` |
| Solicitação duplicada com conteúdo diferente | 409 | `duplicate-request` |
| Sem token, token inválido ou expirado | 401 | `unauthorized` (com `WWW-Authenticate: Bearer`) |
| Escopo insuficiente, ou rota/método sem regra de autorização | 403 | `forbidden` |
| Erro inesperado | 500 | `internal-error` (detalhes só no log, com o mesmo `correlationId`) |

```json
{
  "type": "https://api.deroyque.example/problems/validation-error",
  "title": "Dados inválidos",
  "status": 400,
  "detail": "Um ou mais campos não passaram na validação.",
  "instance": "/api/v1/requests",
  "correlationId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "errors": [ { "field": "customerId", "message": "é obrigatório" } ]
}
```

**Erro de integração:** como o envio é assíncrono, falhas do provedor não viram erro HTTP para quem criou
a solicitação. Elas aparecem no status (`PROCESSING` em retry ou `FAILED`) e no `failureReason` do `GET`.

### Segurança (item 7)

**Implementado:** Spring Security como **OAuth 2.0 Resource Server (JWT)**.

- **Fluxo: Client Credentials.** A comunicação é entre sistemas, sem usuário. Cada sistema consumidor tem
  seu próprio `client_id` no servidor de autorização corporativo (Keycloak, Entra ID, Cognito, Okta...) e
  obtém tokens de vida curta. Em produção, eu preferiria autenticar o cliente com `private_key_jwt` ou mTLS
  em vez de *client secret*.
- **Validação do JWT:** assinatura com as chaves públicas do **JWKS** do servidor de autorização (cacheadas
  e trocadas pelo `kid` quando ele rotaciona as chaves), **emissor** (`iss`), **audiência** (`aud` deve
  conter `activation-service`, para que um token emitido para outra API não seja aceito aqui) e
  **expiração/validade** (`exp`, `nbf`). Tudo configurado em `spring.security.oauth2.resourceserver.jwt.*`
  por variáveis de ambiente.
- **Autorização por escopo:** `activation:write` cria (`POST`) e `activation:read` consulta (`GET`). Um
  sistema de BI, por exemplo, recebe só leitura.
- **Nega por padrão:** as regras ficam todas em `SecurityConfiguration`, e a última é
  `anyRequest().denyAll()`. Rota ou método sem regra explícita (um `DELETE`, por exemplo) é recusado com
  **403**, mesmo para um token com todos os escopos (**401** sem token). Assim, um endpoint novo só funciona
  depois que alguém declara qual escopo ele exige: esquecer a regra bloqueia o acesso em vez de abri-lo.
- **Isolamento entre consumidores (OWASP API1, BOLA):** o `sub` do token (o consumidor) é gravado na
  solicitação. Consulta e listagem só retornam solicitações do próprio consumidor; uma solicitação de outro
  responde **404** (não 403), para não revelar que o id existe.
- **Testes de 401 e 403 sem servidor de autenticação real** (`spring-security-test`, `jwt()`), além do
  isolamento testado ponta a ponta.
- Sessão *stateless*, CSRF desabilitado (sem cookie de sessão para proteger), Actuator em porta separada.

**Credencial do provedor externo:** a API key vem de `PROVIDER_API_KEY`, que em produção é injetada a
partir de um cofre de segredos (AWS Secrets Manager, Azure Key Vault, HashiCorp Vault) e nunca vai para o
repositório, a imagem ou os logs (`ProviderProperties.toString()` mascara o valor). **Rotação:** o provedor
aceita duas chaves válidas ao mesmo tempo; grava-se a nova no cofre, faz-se o rollout (as instâncias leem a
nova na inicialização, ou com refresh dinâmico via Spring Cloud) e só então a antiga é revogada. Se o
provedor usasse OAuth2, o serviço seria cliente *client credentials* dele, com tokens renovados
automaticamente pelo `OAuth2AuthorizedClientManager` do Spring Security.

**Limite de tamanho do corpo e de taxa: não implementados** (ver [Pontos pendentes](#pontos-pendentes)).

### Observabilidade

- **Actuator** na porta de gerenciamento `8081` (não exposta publicamente): `health/liveness` (só a JVM) e
  `health/readiness` (inclui o banco, para tirar do balanceamento uma instância que perdeu o banco sem
  reiniciá-la).
- **Correlation id** (`X-Correlation-Id`): aceito do chamador (se o formato for válido, para evitar
  injeção em log) ou gerado na borda; vai no MDC de todos os logs e no corpo dos erros, é gravado com a
  solicitação e reenviado ao provedor, inclusive nos retries feitos horas depois por outra instância.
- **Métricas Micrometer/Prometheus** (`/actuator/prometheus`):
  - latência da API: `http.server.requests` (histograma, por rota e status);
  - latência e taxa de erro do provedor: `http.client.requests{uri="/provider/v1/activate"}`;
  - resultado das chamadas ao provedor: `activation.provider.outcomes{outcome=success|permanent_failure|retryable_failure}`;
  - solicitações registradas e finalizadas por status: `activation.requests.received`,
    `activation.requests.finished{status=COMPLETED|FAILED}`;
  - pool de conexões (`hikaricp_*`), JVM, GC e threads (padrão do Spring Boot).
- **Logs estruturados em JSON** (formato ECS, nativo do Spring Boot 3.4+) ativados na imagem Docker por
  `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`; em desenvolvimento, texto legível com o correlation id.
- **Nenhum dado sensível em log:** tokens e API key nunca são registrados; os logs têm ids, status e motivos.

### Estratégia de testes (item 8)

A pirâmide prioriza testes rápidos para as regras e poucos testes de integração para o que só se prova com
as peças reais.

| Nível | Classe | O que garante |
|---|---|---|
| Unitário (sem Spring) | `ActivationRequestTest`, `ActivationStatusTest` | transições válidas e inválidas, lease, conteúdo igual |
| Unitário (Mockito) | `ActivationRequestServiceTest` | criação, retry, conflito, **corrida perdida na constraint única**, BOLA |
| Unitário (Mockito) | `ActivationDispatcherTest` | sucesso, falha permanente, retry com backoff, `Retry-After`, limite de tentativas, lease, resultado obsoleto descartado |
| Unitário | `RetryPolicyTest` | crescimento exponencial, jitter dentro dos limites, teto |
| Componente (WireMock) | `HttpActivationProviderTest` | headers enviados (idempotência, correlação, credencial), classificação de cada status, `Retry-After` em segundos e data, **timeout de leitura**, conexão recusada |
| Slice web (`@WebMvcTest`) | `ActivationRequestControllerTest` | contrato HTTP, validação, formato Problem Details, **401 sem token, 403 por escopo, rota sem regra negada mesmo com todos os escopos**, correlation id, nenhum detalhe interno no 500 |
| Arquitetura (ArchUnit) | `ArchitectureTest` | direção das dependências entre camadas |
| Integração (`@SpringBootTest`, H2 + Flyway + WireMock) | `ActivationFlowIT` | fluxo completo assíncrono, recusa, retry com a mesma chave de idempotência, timeout, duplicidade, isolamento entre consumidores |
| Integração (concorrência) | `H2ConcurrentCreationIT`, `PostgresConcurrentCreationIT` | 16 criações simultâneas → exatamente um registro; no PostgreSQL real via **Testcontainers** |

O H2 em modo PostgreSQL deixa a suíte rápida e executável sem Docker. O teste de concorrência também roda
no PostgreSQL, porque ali o comportamento de lock do banco real é o que importa.

---

## Cenário de análise (item 10)

> API 300 ms → 2,5 s · CPU 45% · memória 60% · algumas consultas 50 ms → 1,2 s · provedor 400 ms → 3 s

**O que eu investigaria primeiro.** CPU e memória em níveis normais indicam que a aplicação está
*esperando*, não trabalhando. A suspeita vai para recursos compartilhados: conexões do pool, threads do
servidor e locks. Antes de tudo, eu confirmaria o *quando* e o *onde*: em que momento a curva mudou
(deploy, pico de tráfego, crescimento de dados, incidente no provedor) e quais endpoints degradaram, olhando
p95/p99 por rota e não a média, que esconde a cauda.

**Como separar aplicação, banco e integração.**
- **Tracing distribuído / spans** por requisição: mostra quanto do tempo foi gasto no banco, no provedor ou
  em espera.
- **Tempo da consulta no banco × tempo visto pela aplicação:** se o `pg_stat_statements` mostra execução
  normal, mas a aplicação mede 1,2 s, o tempo está indo para *esperar uma conexão* (`hikaricp_connections_pending`,
  `hikaricp_connections_acquire`), não para executar a consulta. Se o próprio banco está lento:
  `EXPLAIN ANALYZE`, locks (`pg_locks`, `pg_stat_activity` com `idle in transaction`), I/O e vacuum.
- **Provedor:** `http.client.requests` por status, taxa de timeouts e retries. Se ele está lento para todos
  os clientes, o problema é dele.
- **Thread dump** em momento de lentidão: threads paradas em `HikariPool.getConnection` ou em leitura de
  socket do provedor apontam diretamente o gargalo.

**Logs e métricas úteis.** `http.server.requests` (p95/p99 por rota), `http.client.requests` do provedor,
`hikaricp_connections_active/pending/acquire/usage`, `tomcat_threads_busy`, `jvm_gc_pause`,
`jvm_threads_states`, contadores de retry e timeout (`activation.provider.outcomes`), slow query log do
PostgreSQL (`log_min_duration_statement`), `pg_stat_statements` e logs com correlation id e duração para
rastrear uma requisição lenta de ponta a ponta.

**Os três sintomas podem ter causa comum? Hipótese:** sim. O provedor fica lento (400 ms → 3 s) e a chamada
a ele acontece **com uma transação aberta**, ou seja, segurando uma conexão do pool (ou no caminho síncrono
da requisição). Cada requisição passa a prender uma conexão por 3 s, e com um pool de 10 conexões ele se
esgota rápido. A partir daí, *qualquer* requisição espera conexão: as consultas "ficam lentas" do ponto de
vista da aplicação, mesmo com o banco ocioso, e a API sobe para segundos. Se houver locks de linha na
transação, eles também ficam presos 3 s e travam outras escritas. CPU e memória continuam normais, porque
as threads só esperam. Retries sem backoff amplificam o efeito.

*Como confirmar:* `hikaricp_connections_pending > 0` e tempo de aquisição alto, coincidindo com a subida de
latência do provedor; `usage` das conexões próximo de 3 s; `pg_stat_activity` com muitas sessões
`idle in transaction`; tempo de execução no banco normal no `pg_stat_statements`; thread dump com threads
em `getConnection`. Uma hipótese alternativa é a de *crescimento de dados sem índice* (consultas lentas de
verdade, consumindo conexões por mais tempo), que se distingue pelo `EXPLAIN` e pelo tempo de execução no
próprio banco.

**Melhorias.** Tirar a chamada ao provedor da transação e do caminho síncrono (o que esta solução já faz);
timeouts curtos e explícitos; bulkhead (pool dedicado e limitado) e circuit breaker para o provedor; backoff
com jitter nos retries; corrigir consultas e índices (planos com `EXPLAIN`); ajustar o tamanho do pool só
depois de remover a causa (aumentar o pool só desloca o gargalo para o banco); paginação obrigatória;
rate limiting por consumidor.

**Como validar que a correção funcionou.** Reproduzir o cenário antes, em homologação, com teste de carga
e o provedor simulado com 3 s de latência. Publicar com canário e comparar canário × linha de base nas
mesmas métricas (p95/p99 da API, `hikaricp_connections_pending` em zero, erro 5xx). O sinal decisivo: a
latência da API deixa de acompanhar a do provedor. Por fim, acompanhar os SLOs por alguns dias, com alerta
de regressão.

---

## Produção (item 11)

A imagem (`Dockerfile`) é construída em múltiplos estágios: build com Maven e extração das camadas do jar
(as dependências ficam em cache), e uma imagem final **somente com JRE 21 (Alpine)**, executando com **usuário
não-root**. A configuração vem toda por **variáveis de ambiente**, a heap é ajustada ao limite do contêiner
(`-XX:MaxRAMPercentage=75`) e há **HEALTHCHECK**, logs JSON e **desligamento gracioso** (a JVM é o PID 1
e recebe o SIGTERM; o Tomcat para de aceitar requisições e conclui as em andamento; o pool de envio termina
as chamadas em curso; o que sobrar é retomado por outra instância pelo lease).

Em produção:
- **Orquestrador** (Kubernetes, ECS ou similar) com no mínimo 2 réplicas em zonas diferentes, *liveness* e
  *readiness probes* na porta 8081, requests/limits de CPU e memória, `terminationGracePeriodSeconds`
  maior que o `timeout-per-shutdown-phase` (30 s) e autoscaling por CPU ou latência.
- **Banco gerenciado** (RDS, Cloud SQL ou Azure Database for PostgreSQL) com backup, réplica e conexões
  limitadas por instância (`DB_POOL_SIZE`). As migrações Flyway rodam na subida; em times maiores, eu as
  rodaria como etapa separada do pipeline, antes do deploy, sempre com migrações compatíveis com a versão
  anterior (*expand/contract*) para permitir rolling update e rollback.
- **Segredos** (senha do banco, API key do provedor) vindos do cofre do provedor de cloud, injetados como
  variáveis de ambiente ou volume; nunca na imagem.
- **Exposição** por API gateway ou ingress com TLS, limite de tamanho de corpo e rate limiting; o Actuator
  fica acessível só para a rede interna e o Prometheus. Swagger desabilitável com `API_DOCS_ENABLED=false`.
- **Observabilidade:** logs JSON para a plataforma centralizada (ELK, Datadog, CloudWatch), scraping do
  Prometheus e alertas para p99 da API, taxa de `FAILED`, retries e solicitações paradas em `PROCESSING`.

*(Não criei manifests Kubernetes nem Terraform, conforme o enunciado.)*

## Microsserviços e cloud

- **Várias instâncias.** A unicidade é garantida pelo banco (constraint), não por memória, então vale com
  qualquer número de instâncias. O processamento também: cada tentativa é reivindicada com lock otimista,
  só uma instância chama o provedor, e o lease devolve a solicitação se a instância cair. A varredura pode
  rodar em todas as instâncias sem coordenação. Em volume muito alto, eu trocaria a reivindicação por
  `SELECT ... FOR UPDATE SKIP LOCKED` ou por um broker.
- **Configurações e segredos.** O que não é sensível fica em variáveis de ambiente ou ConfigMap, versionado
  junto com o deploy. O que é sensível fica no serviço de segredos do provedor de cloud, com acesso por
  identidade da carga (IRSA, Workload Identity), sem credencial estática.
- **Avisar outros microsserviços.** Usaria um **outbox transacional**: na mesma transação que grava
  `COMPLETED` ou `FAILED`, insere-se um evento `ActivationCompleted`/`ActivationFailed` em uma tabela
  `outbox`; um relay (ou CDC com Debezium) publica em um tópico (Kafka, SNS/SQS, Service Bus). Assim, nunca
  se perde nem se publica evento de algo que não foi gravado. Os consumidores deduplicam pelo id do evento,
  porque a entrega é "pelo menos uma vez".
- **CI/CD.** A cada PR: build, testes unitários e de integração (com Testcontainers), análise estática e de
  dependências vulneráveis (Sonar, OWASP/Dependabot). Na branch principal: build da imagem, scan (Trivy),
  publicação no registry com a tag do SHA, deploy automático em homologação (Helm/ArgoCD) com smoke tests,
  e promoção para produção com canário e rollback automático se os SLOs piorarem. O workflow em
  `.github/workflows/ci.yml` cobre a primeira parte: build, testes e build da imagem.

---

## Pontos pendentes

| Item | Situação | Como faria |
|---|---|---|
| Limite de tamanho do corpo | Não implementado (os campos já são limitados a 64 caracteres pela validação) | No gateway ou ingress (ex.: `client_max_body_size 16k`), que rejeita antes de chegar à aplicação; na aplicação, um filtro que rejeita `Content-Length` acima do limite com `413`. |
| Rate limiting | Não implementado | Por consumidor (`sub` do token) no API gateway; ou, na aplicação, Bucket4j com armazenamento compartilhado (Redis), respondendo `429` com `Retry-After`. |
| Circuit breaker | Descrito, não implementado | Resilience4j em volta do `HttpActivationProvider`, pausando a varredura com o circuito aberto (ver [Integração](#integração-com-o-provedor-item-5)). |
| Tracing distribuído (OpenTelemetry) | Correlation id próprio implementado; tracing não | Micrometer Tracing + exportador OTLP; o `traceparent` (W3C) seria propagado ao provedor, e o trace id gravado com a solicitação para ligar o processamento assíncrono ao trace original. |
| Evento de ativação concluída | Não implementado | Outbox transacional + tópico (ver [Microsserviços e cloud](#microsserviços-e-cloud)). |
| Reprocessamento manual de `FAILED` | Não implementado | Endpoint administrativo (escopo próprio) que reabre a solicitação com nova política de tentativas, registrando quem pediu. |
| Fluxo completo contra PostgreSQL | Só a concorrência roda no PostgreSQL | Rodar o `ActivationFlowIT` também com Testcontainers no CI. |

## Melhorias futuras

- Outbox e publicação de eventos de domínio; tracing com OpenTelemetry.
- Reivindicação com `FOR UPDATE SKIP LOCKED` e índice parcial no PostgreSQL, se o volume crescer.
- Testes de contrato com o provedor (Pact) e testes de mutação (PIT) nas regras de domínio.
- Cobertura com JaCoCo publicada no CI (como informação, não como meta).
- Dashboards e alertas por SLO (latência p99, taxa de `FAILED`, idade da solicitação mais antiga em `PROCESSING`).
- Política de retenção e arquivamento das solicitações finalizadas.
- Migração para Spring Boot 4 (escolhi o 3.5 por ser a versão indicada no enunciado e a mais adotada).

## Uso de IA

O desenvolvimento usou o **Claude Code** (assistente de programação da Anthropic) para:

- leitura e organização dos requisitos do enunciado;
- geração do código, dos testes, da configuração Docker/CI e desta documentação;
- execução iterativa do build e dos testes, com correção dos problemas encontrados.

As decisões de arquitetura e de comportamento (processamento assíncrono com a tabela como fila, semântica
de idempotência, classificação dos retornos do provedor, manutenção de `PROCESSING` em falhas temporárias)
foram discutidas, revisadas e validadas pelo autor, que responde por elas na conversa técnica.
