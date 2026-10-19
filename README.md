# NutriViver – Backend

API GraphQL do app [NutriViver](https://github.com/alanis0301/nutri-app-project).
Construída com Spring Boot 3 e Spring for GraphQL, compilada como binário nativo com GraalVM.
Usa PostgreSQL para os dados e Elasticsearch para a busca de receitas.

## Stack

| Peça | Tecnologia |
|---|---|
| API | Spring Boot 3.3 + Spring for GraphQL (GraphiQL em `/graphiql`) |
| Runtime | Binário nativo GraalVM 21 (`native-image`) |
| Banco | PostgreSQL (modelo do dbdiagram, `src/main/resources/schema.sql`) |
| Busca | Elasticsearch/OpenSearch: busca por nome, descrição e ingredientes, sem acento e com tolerância a erro de digitação |

## Rodar local

Requer Docker. O build nativo leva alguns minutos e usa ~6 GB de RAM.

```bash
docker compose up --build
```

- GraphiQL: http://localhost:8080/graphiql
- Endpoint: `POST http://localhost:8080/graphql`

## Exemplos

```graphql
{ recipes(mealType: "Almoço", maxPrepTime: 30) { id name prepTime difficulty } }

{ searchRecipes(text: "brocolis") { name ingredients { ingredient { name } } } }

mutation { signUp(name: "ana", password: "segredo") { id } }

mutation {
  updateProfile(userId: 1, goal: "Perder peso",
                preferences: ["Frango", "Ovo"], restrictions: ["Lactose"]) {
    goal { name } preferences { name } restrictions { name }
  }
}
```

## Variáveis de ambiente

| Variável | Padrão | Uso |
|---|---|---|
| `PORT` | `8080` | Porta HTTP |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/nutriviver` | JDBC do Postgres (Neon: adicionar `?sslmode=require`) |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `postgres` / `postgres` | Credenciais do Postgres |
| `ELASTICSEARCH_URL` | `http://localhost:9200` | URL do Elasticsearch; aceita `https://usuario:senha@host` |

## Deploy

O push na `main` dispara o workflow `.github/workflows/docker.yml`. Ele compila a imagem nativa e publica em `ghcr.io/bacuriim/nutriviver-backend:latest`.
O Render roda essa imagem. Se o secret `RENDER_DEPLOY_HOOK` estiver configurado, o workflow também dispara o redeploy.

- Banco: Neon (plano free)
- Busca: Bonsai Sandbox (plano free)
- API: Render (plano free; dorme após 15 min sem acesso e leva ~1 min para acordar)

## Diferenças em relação ao diagrama

- A tabela `user` virou `users`, porque `user` é palavra reservada no Postgres.
- `users.id_goal` foi adicionado para guardar a relação user–goal desenhada no diagrama. Cada usuário tem um objetivo.
- `recipe` ganhou `image_url`, `difficulty`, `meal_type` e `steps`, que o app exibe.
- A senha é guardada com hash BCrypt, nunca em texto puro.
