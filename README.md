# Sistema de Comunicação Distribuído com ZeroMQ


# Diagrama de relações 

![Image](https://github.com/user-attachments/assets/666be52b-89c5-4e66-a700-976b346bae8a)

## 1. Padrões de Mensagem Utilizados

### 1.1 Request-Reply (Cliente ↔ Broker ↔ Server)

* **Utilização:** Operações de criar posts, seguir usuários, enviar mensagens
* **Padrão:** Cliente envia requisição → Broker encaminha → Server processa → Resposta retorna
* **Sockets:**

  * Cliente (REQ) → Broker (ROUTER/DEALER) → Server (REP)

### 1.2 Publish-Subscribe (Server → Proxy → Clientes)

* **Utilização:** Notificações de novos posts e mensagens privadas
* **Padrão:** Server publica → Proxy distribui → Clientes inscritos recebem
* **Sockets:**

  * Server (PUB) → Post Proxy (XSUB/XPUB) → Cliente (SUB)

### 1.3 Publish-Subscribe para Replicação (Server ↔ Proxy ↔ Servers)

* **Utilização:** Sincronização de dados entre servers
* **Padrão:** Server origem publica → Proxy distribui → Servers destino recebem
* **Sockets:**

  * Server (PUB) → Replication Proxy (XSUB/XPUB) → Servers (SUB)
* **Observação:** Basicamente todos os servers são publicadores e estão inscritos em todos os servers.

---

## 2. Estrutura dos Dados nas Mensagens

### 2.1 Mensagens Request-Reply

**Requisição do Cliente:**

```json
{
  "type": "create_post|follow_user|send_private_message|get_all_posts|get_following",
  "user_id": "string",
  "content": "string",
  "request_id": "uuid",
  "client_timestamp": 1234567890
}
```

**Resposta do Server:**

```json
{
  "status": "success|error",
  "message": "string",
  "server_id": "string",
  "post": {...},
  "posts": [...],
  "following": [...]
}
```

### 2.2 Mensagens Publish-Subscribe

**Post Notification:**

```json
{
  "type": "new_post",
  "post": {
    "id": "uuid",
    "user_id": "string",
    "content": "string",
    "server_id": "string",
    "created_at": 1234567890,
    "client_timestamp": 1234567890
  },
  "server_timestamp": 1234567890
}
```

**Private Message Notification:**

```json
{
  "type": "private_message",
  "sender_id": "string",
  "content": "string",
  "created_at": 1234567890,
  "client_timestamp": 1234567890,
  "server_timestamp": 1234567890
}
```

### 2.3 Mensagens de Replicação

```json
{
  "type": "post|message|follow",
  "payload": {...},
  "source_server": "string",
  "timestamp": 1234567890,
  "id": "uuid"
}
```

---

## 3. Relógios Lógicos

### 3.1 Implementação nos Clientes (Java)

* **Tipo:** Relógio físico incrementado logicamente
* **Incremento:** +1 segundo a cada operação
* **Sincronização:** Ajuste quando recebe timestamp futuro do Server

### 3.2 Implementação nos Servers (JavaScript)

* **Tipo:** Timestamp físico (`Date.now()`)
* **Replicação:** Mantém timestamps originais na replicação

---

## 4. Funcionalidades Implementadas

### 4.1 Posts Públicos

* Criação com timestamp lógico
* Replicação automática entre Servers
* Notificação para seguidores via pub-sub

### 4.2 Sistema de Seguidores

* SUB a posts de usuários seguidos

### 4.3 Mensagens Privadas

* Entrega direta via notificação pub-sub
* Armazenamento persistente nos Servers
* Replicação para alta disponibilidade

### 4.4 Logs Detalhados

* **Clientes:** Logs em arquivos individuais com timestamps lógicos
* **Servers:** Logs de operações e replicações
* **Proxies:** Logs de operações

---

## 5. Replicação de Dados e Balanceamento

* Replicação ativa via pub-sub
* **Recuperação:** Dados persistidos em arquivos JSON
* **Broker:** Round-robin automático entre 3 Servers

---

## 6. Tecnologias e Linguagens

* **Cliente:** Java (ZeroMQ + JSON)
* **Server:** JavaScript/Node.js (ZeroMQ)
* **Proxies:** Python (ZeroMQ)
* **Broker:** Python (ZeroMQ)
* **Comunicação:** ZeroMQ com padrões REQ/REP, PUB/SUB, ROUTER/DEALER

````markdown
## 7. Dependências e Requisitos de Execução

### 7.1 Java (Cliente)  
**Versão:** Java 8 ou superior  

```xml
<!-- Maven Dependencies (pom.xml) -->
<dependencies>
    <dependency>
        <groupId>org.zeromq</groupId>
        <artifactId>jeromq</artifactId>
        <version>0.5.3</version>
    </dependency>
    <dependency>
        <groupId>org.json</groupId>
        <artifactId>json</artifactId>
        <version>20230227</version>
    </dependency>
</dependencies>
````

### 7.2 Node.js (Servidor)

**Versão:** Node.js 14 ou superior

```json
// package.json
{
  "dependencies": {
    "zeromq": "^6.0.0-beta.19",
    "uuid": "^9.0.0"
  }
}
```

**Instalação:**

```bash
npm install zeromq uuid
```

### 7.3 Python (Broker e Proxies)

**Versão:** Python 3.7 ou superior

**Instalação via pip:**

```bash
pip install pyzmq
```

### Portas Utilizadas

* **5555:** Broker (clientes conectam)
* **5556:** Broker (servidores conectam)
* **5557:** Post Proxy (servidores publicam)
* **5558:** Post Proxy (clientes subscrevem)
* **6000:** Replication Proxy (servidores publicam)
* **6001:** Replication Proxy (servidores subscrevem)

