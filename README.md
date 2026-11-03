# Notification Simulator

Simulador HTTP de envio de notificações utilizado nos experimentos do OrderLab, projeto de TCC em Engenharia de Software sobre a extração gradual de serviços de uma aplicação monolítica.

A aplicação representa um provedor externo de notificações. Ela recebe solicitações, aguarda um atraso configurável e responde com sucesso ou falha controlada. Não envia e-mails reais.

## Funcionalidades implementadas

- Endpoint síncrono para receber notificações.
- Validação do destinatário, conteúdo e chave de idempotência.
- Atraso artificial configurável.
- Resposta de falha controlada com HTTP 503.
- Controle de idempotência em memória, com sincronização por chave.
- Logs de recebimento, conclusão, falha simulada e repetição de notificações já concluídas.

## Tecnologias

- Java 21;
- Spring Boot 4.1.2;
- Spring Web MVC;
- Jakarta Validation;
- Spring Boot Actuator e Micrometer Prometheus;
- Docker, com Dockerfile para compilação e execução;
- Lombok e SLF4J;
- Maven, com Maven Wrapper incluído no projeto.

A aplicação não utiliza banco de dados, JPA ou Flyway.

## Organização

```text
src/main/java/com/viratech
├── NotificationSimulatorApplication.java
├── controller
│   └── NotificationController.java
├── dto
│   └── NotificationRequest.java
└── service
    └── NotificationSimulatorService.java
```

| Componente | Responsabilidade |
| --- | --- |
| `NotificationController` | Receber e validar a solicitação HTTP |
| `NotificationRequest` | Transportar e validar destinatário e conteúdo |
| `NotificationSimulatorService` | Controlar atraso, falha simulada e idempotência |
| `DeliveryState` | Classe interna que mantém o payload e a indicação de conclusão para cada chave |

## Configuração

Arquivo `src/main/resources/application.yaml`:

```yaml
server:
  port: 9091

spring:
  application:
    name: notification-simulator

simulator:
  delay-ms: 1000
  fail-enabled: false

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
```

| Propriedade | Configuração incluída | Comportamento |
| --- | --- | --- |
| `server.port` | `9091` | Porta HTTP |
| `simulator.delay-ms` | `1000` | Pausa artificial de aproximadamente um segundo |
| `simulator.fail-enabled` | `false` | Quando `true`, provoca HTTP 503 nas tentativas ainda não concluídas |

O serviço rejeita atraso negativo na inicialização. Se `simulator.delay-ms` estiver ausente, o código utiliza `0`. Já `simulator.fail-enabled` deve ser fornecido: o construtor atual não define valor alternativo para essa propriedade.

Alterações no arquivo de configuração exigem reinício. Reiniciar também elimina o estado de idempotência mantido em memória.

## Execução integrada pelo Docker Compose do OrderLab

O Compose está no repositório do OrderLab e inicia PostgreSQL, OrderLab, este simulador, Prometheus e Grafana. Não existe Compose próprio neste repositório. O contexto configurado é `../notification-simulator`, portanto as pastas dos projetos devem estar lado a lado, ou esse caminho deve ser ajustado. O contexto deve conter `pom.xml`, `src` e `Dockerfile`.

Pré-requisitos: Docker acessível no WSL/Linux, Compose com suporte a `build.entitlements` (2.27.1 ou posterior), Buildx/Bake e internet na primeira construção. Não é necessário instalar Java/Maven no host para esse modo.

### Preparar e iniciar o ambiente

Na pasta do **OrderLab**, configure o `.env` com as credenciais do banco, `GRAFANA_ADMIN_PASSWORD` e:

```dotenv
SIMULATOR_DELAY_MS=1000
SIMULATOR_FAIL_ENABLED=false
```

Confira também os arquivos `monitoring/prometheus.yml` e `monitoring/grafana/datasources.yml` no OrderLab. Eles precisam existir como arquivos, com nomes iguais aos definidos no Compose. O esquema do banco precisa estar preparado conforme o README do OrderLab.

Pare as versões Java abertas no IntelliJ para liberar as portas. Execute na pasta do OrderLab:

```bash
docker compose config --quiet
docker buildx bake -f docker-compose.yaml --allow=network.host --load orderlab notification-simulator
docker compose up -d --no-build
docker compose ps -a
```

O Compose atual usa as imagens `orderlab-app:latest` e `notification-simulator-app:latest`. A opção `--load` carrega as imagens construídas no Docker local. A autorização `network.host` acompanha a configuração de build adotada neste WSL para resolver DNS durante a construção; não altera a rede dos serviços em execução.

Os Dockerfiles geram o JAR com Maven 3.9/JDK 21 e executam com JRE 21. O build usa `-DskipTests`, que pula a execução dos testes, mas ainda compila os testes.

### Acessos e logs

| Recurso | Endereço pelo computador |
| --- | --- |
| Simulador: saúde | http://localhost:9091/actuator/health |
| Simulador: métricas | http://localhost:9091/actuator/prometheus |
| OrderLab | http://localhost:9090 |
| Prometheus: alvos | http://localhost:9092/targets |
| Grafana | http://localhost:3000 |

```bash
docker compose logs -f notification-simulator
```

`Ctrl+C` sai dos logs sem parar o serviço. Na rede interna, o OrderLab acessa `http://notification-simulator:9091`. Pelo navegador/Postman, use `localhost:9091`.

O Prometheus coleta as métricas a cada 5 s. Seu alvo deve estar `UP`. No Grafana, use `admin` e a senha da primeira inicialização, selecione Prometheus em Explore e consulte:

```promql
up{job="notification-simulator"}
```

```promql
sum(jvm_memory_used_bytes{application="notification-simulator",area="heap"}) / 1024 / 1024
```

A primeira consulta deve retornar 1; a segunda mostra heap em MiB, não memória total do contêiner. A fonte de dados é provisionada, mas ainda não há dashboards provisionados. As métricas padrão não oferecem automaticamente contadores de perdas/duplicações do negócio; preserve os logs e reconcilie com as tarefas do OrderLab.

### Alterar cenários

Edite `SIMULATOR_DELAY_MS` e `SIMULATOR_FAIL_ENABLED` no `.env` do OrderLab e reaplique:

```bash
docker compose up -d --no-build notification-simulator
```

| Cenário | Atraso | Falha habilitada |
| --- | --- | --- |
| Normal | 1000 | false |
| HTTP 503 controlado | 1000 | true |
| Espera maior que o timeout de leitura do OrderLab | 10000 | false |

A recriação elimina a idempotência em memória. Para novos testes diretos, use chaves distintas. Uma alteração no `.env` não é aplicada apenas por `docker compose restart`.

Para testar indisponibilidade, pare o simulador, crie um pedido no OrderLab e inicie-o novamente:

```bash
docker compose stop notification-simulator
```

```bash
docker compose start notification-simulator
```

O reinício também elimina a memória de idempotência. Não faça essa ação durante uma rodada sem registrá-la como parte do cenário.

Após alterar código, dependências ou YAML interno do simulador, execute na pasta do OrderLab:

```bash
docker buildx bake -f docker-compose.yaml --allow=network.host --load notification-simulator
docker compose up -d --no-build notification-simulator
```

Para encerrar o ambiente preservando os volumes, use `docker compose down` na pasta do OrderLab. Não use `down -v` para uma parada normal. Fixe versões/digests das imagens antes da coleta oficial; os testes atuais são pilotos de preparação.

## Execução local

Pré-requisitos: JDK 21 e acesso à internet na primeira execução para baixar o Maven e as dependências.

Execute os comandos na pasta que contém `pom.xml` e os arquivos `mvnw`/`mvnw.cmd`. No arquivo enviado nesta revisão, essa pasta é a raiz `notification-simulator`.

### Windows — PowerShell

```powershell
.\mvnw.cmd spring-boot:run
```

### Linux / WSL

```bash
chmod +x mvnw
./mvnw spring-boot:run
```

### Gerar e executar o JAR

No Windows:

```powershell
.\mvnw.cmd clean package
java -jar target/notification-simulator-0.0.1-SNAPSHOT.jar
```

No Linux / WSL:

```bash
./mvnw clean package
java -jar target/notification-simulator-0.0.1-SNAPSHOT.jar
```

Também é possível escolher um cenário ao executar o JAR:

```bash
java -jar target/notification-simulator-0.0.1-SNAPSHOT.jar --simulator.delay-ms=1000 --simulator.fail-enabled=true
```

Endereço local: `http://localhost:9091`.

## API

### Receber uma notificação

```http
POST /api/v1/notifications
Content-Type: application/json
Idempotency-Key: 15
```

```json
{
  "consumerEmail": "cliente@example.com",
  "content": "Order: 10"
}
```

| Entrada | Restrição |
| --- | --- |
| Header `Idempotency-Key` | Obrigatório, não pode ser vazio ou conter apenas espaços; máximo de 100 caracteres |
| `consumerEmail` | Obrigatório, não vazio, formato de e-mail e máximo de 255 caracteres |
| `content` | Obrigatório, não vazio e máximo de 255 caracteres |

A chave é uma string; no OrderLab, seu valor é o identificador da `NotificationTask`, não necessariamente o ID do pedido.

### Respostas

| HTTP | Situação |
| --- | --- |
| `200 OK` | Envio simulado concluído ou repetição do mesmo payload para uma chave já concluída |
| `400 Bad Request` | Header ausente/inválido ou corpo inválido |
| `409 Conflict` | Chave já associada a um payload diferente |
| `503 Service Unavailable` | Falha simulada habilitada ou interrupção da thread durante a espera |

A resposta de sucesso não contém corpo. O primeiro sucesso só é devolvido após a espera e a conclusão simulada. O formato do corpo de erro segue o tratamento padrão do Spring Boot; não há um contrato personalizado de erros nesta versão.

### Exemplo com cURL — Linux / WSL

```bash
curl -i -X POST http://localhost:9091/api/v1/notifications \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 15' \
  -d '{"consumerEmail":"cliente@example.com","content":"Order: 10"}'
```

No Postman, utilize o mesmo método, URL, headers e corpo JSON.

## Idempotência e concorrência

O serviço mantém um `ConcurrentHashMap` com um `DeliveryState` por chave. O payload é associado à chave na primeira solicitação válida que chega ao serviço, mesmo se a tentativa depois falhar.

- Mesma chave e mesmo payload, após sucesso: retorna 200 e registra `DUPLICATE`, sem novo atraso ou efeito de conclusão.
- Mesma chave e payload diferente: retorna 409.
- Mesma chave e mesmo payload, após falha: permite outra tentativa; se `fail-enabled` continuar verdadeiro, ela falhará novamente.
- Chamadas simultâneas da mesma chave: são processadas sob o mesmo bloqueio; se a primeira concluir, a seguinte será reconhecida como duplicada.
- Chaves diferentes: podem ser processadas concorrentemente, conforme os recursos disponíveis.

Não existe armazenamento durável ou compartilhamento de estado entre instâncias. Reiniciar a aplicação apaga as chaves e o contador de chamadas. Não há expiração ou limpeza automática das entradas: o consumo de memória cresce com o número de chaves distintas.

## Logs

Exemplos:

```text
event=RECEIVED key=15 call=1 at=...
event=COMPLETED key=15 at=...
event=DUPLICATE key=15 at=...
event=FAILED key=16 at=...
```

| Evento | Significado |
| --- | --- |
| `RECEIVED` | Solicitação entrou no serviço; `call` é o contador global de chamadas dessa instância |
| `COMPLETED` | Efeito de envio simulado foi concluído |
| `DUPLICATE` | Solicitação repetida para uma chave já concluída |
| `FAILED` | Falha provocada por `fail-enabled` |

Payloads rejeitados pelo controller não geram `RECEIVED`. Conflitos de chave e interrupções não possuem um evento específico de falha no código atual. Os logs são evidências da execução, mas não existe endpoint de consulta, exportação ou reset nesta versão.

## Integração com o OrderLab

Configure no OrderLab, na raiz do YAML:

```yaml
notification:
  simulator:
    base-url: http://localhost:9091
```

O `NotificationClient` deve enviar o JSON documentado acima e utilizar:

```java
.uri("/api/v1/notifications")
.header("Idempotency-Key", taskId.toString())
```

Quando os processos estiverem em máquinas, containers ou ambientes de rede diferentes, ajuste o endereço: `localhost` sempre representa o ambiente de quem executa a chamada.

Na variante A, o OrderLab aguarda a resposta do simulador antes de responder à criação do pedido. O simulador não altera diretamente o PostgreSQL nem o estado das tarefas do OrderLab; essas atualizações pertencem ao monólito.

## Cenários de verificação

| Cenário | Configuração ou ação | Resultado esperado |
| --- | --- | --- |
| Sucesso | `delay-ms=1000`, `fail-enabled=false` | 200 após aproximadamente um segundo mais os demais custos |
| Falha controlada | `delay-ms=1000`, `fail-enabled=true` | 503 após a espera |
| Duplicação | Repetir chave e corpo após uma conclusão | 200 e evento `DUPLICATE`, sem nova conclusão |
| Conflito | Reutilizar chave alterando e-mail ou conteúdo | 409 |
| Entrada inválida | E-mail inválido, conteúdo vazio ou chave ausente | 400 |
| Timeout no OrderLab | Atraso de 10000 ms e read timeout de 5000 ms no cliente | OrderLab pode registrar falha antes da conclusão no simulador |
| Indisponibilidade | Parar o simulador e criar pedido no OrderLab | Falha de conexão no OrderLab |

O timeout do cliente não cancela automaticamente o processamento remoto. Portanto, uma tarefa pode estar `FAILED` no OrderLab e ter evento `COMPLETED` neste simulador. Compare os registros pela chave antes de concluir que houve perda.

Para cenários novos, reinicie e confira as propriedades efetivas. Uma chave já concluída retorna sucesso mesmo com `fail-enabled=true`, pois a verificação de duplicidade acontece antes da simulação da falha.

## Uso no experimento

O mesmo simulador será utilizado nas variantes A, B e C do OrderLab. Registre a versão do código, propriedades, recursos disponíveis e configuração de logs de cada execução.

O atraso artificial representa espera externa. O esforço computacional local da notificação pertence ao OrderLab ou ao serviço extraído, conforme a variante.

Antes de restaurar IDs do banco do OrderLab para uma nova rodada, reinicie o simulador para evitar colisões com chaves antigas. Durante uma rodada, preserve seu estado e logs. Reinícios no meio da rodada eliminam a memória de idempotência e precisam ser tratados como parte explícita do cenário.

Não conclua que o OrderLab saturou sem verificar o simulador: suas threads, logs e armazenamento em memória também consomem recursos e podem limitar a carga. Para as medições definitivas, serão necessários registros exportáveis e um procedimento documentado de início e término de cada execução experimental.
