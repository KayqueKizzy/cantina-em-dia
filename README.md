# Cantina em Dia

Aplicação web simples em Java para cadastrar o cardápio por data e registrar avaliações dos pratos. Os dados são salvos em um servidor etcd local usando o modelo chave-valor.

## Requisitos

- Java 21 (ou Java 17+)
- etcd v3 em execução em `http://127.0.0.1:2379`

## Como iniciar

1. Inicie o etcd em uma janela de terminal e mantenha-a aberta.
2. Execute `run.bat` nesta pasta.
3. Abra `http://localhost:8080` no navegador.

Se o etcd estiver em outro endereço, defina a variável `ETCD_URL` antes de executar o `run.bat`.

## Como os dados são organizados

- Pratos: `/cantina/menu/{data}/{id}`
- Avaliações: `/cantina/reviews/{data}/{pratoId}/{id}`

Cada par guarda os campos da entidade como valor serializado em JSON. As chaves organizadas por data permitem consultar os registros de um dia no etcd.

## Operações demonstradas

- Criar, listar, editar e excluir pratos.
- Criar, listar e excluir avaliações.
- Consultar por data e exibir notas dos pratos.
