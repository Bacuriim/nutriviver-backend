package br.com.nutriviver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Busca de receitas no Elasticsearch via API REST.
 * O índice é recriado a partir do Postgres a cada start (as receitas só mudam pelo data.sql).
 */
@Component
public class RecipeSearch {
    private static final Logger log = LoggerFactory.getLogger(RecipeSearch.class);
    private static final String INDEX = "recipes";

    // Analisador que ignora acentos e maiúsculas: "brocolis" encontra "Brócolis".
    private static final String INDEX_SETTINGS = """
            {"settings":{"analysis":{"analyzer":{"default":{"tokenizer":"standard",
              "filter":["lowercase","asciifolding"]}}}}}""";

    private final RestClient es;
    private final JdbcClient db;
    private final ObjectMapper json;
    private volatile boolean ready;

    public RecipeSearch(@Value("${elasticsearch.url}") String url, JdbcClient db, ObjectMapper json) {
        // Bonsai entrega a URL como https://usuario:senha@host; a credencial vira header Basic.
        URI uri = URI.create(url);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
        if (uri.getUserInfo() != null) {
            String[] cred = uri.getUserInfo().split(":", 2);
            builder.defaultHeaders(h -> h.setBasicAuth(cred[0], cred[1]));
        }
        this.es = builder.build();
        this.db = db;
        this.json = json;
    }

    /** O Elasticsearch pode subir depois do app, então tenta em segundo plano até conseguir. */
    @EventListener(ApplicationReadyEvent.class)
    public void indexInBackground() {
        Thread.ofVirtual().start(() -> {
            for (int attempt = 1; attempt <= 60 && !ready; attempt++) {
                try {
                    reindex();
                    ready = true;
                } catch (Exception e) {
                    log.warn("Elasticsearch indisponível (tentativa {}): {}", attempt, e.getMessage());
                    try {
                        Thread.sleep(10_000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        });
    }

    void reindex() throws Exception {
        try {
            es.delete().uri("/" + INDEX).retrieve().toBodilessEntity();
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound ignored) {
            // índice ainda não existe
        }
        es.put().uri("/" + INDEX).contentType(MediaType.APPLICATION_JSON).body(INDEX_SETTINGS)
                .retrieve().toBodilessEntity();

        List<Map<String, Object>> docs = db.sql("""
                SELECT r.id_recipe, r.name, r.description, r.meal_type,
                       COALESCE(string_agg(i.name, ' '), '') AS ingredients
                FROM recipe r
                LEFT JOIN recipe_ingredient ri ON ri.id_recipe = r.id_recipe
                LEFT JOIN ingredient i ON i.id_ingredient = ri.id_ingredient
                GROUP BY r.id_recipe""").query().listOfRows();

        StringBuilder bulk = new StringBuilder();
        for (Map<String, Object> d : docs) {
            bulk.append(json.writeValueAsString(Map.of("index", Map.of("_id", d.get("id_recipe")))))
                    .append('\n')
                    .append(json.writeValueAsString(Map.of(
                            "name", d.get("name"),
                            "description", d.get("description"),
                            "mealType", String.valueOf(d.get("meal_type")),
                            "ingredients", d.get("ingredients"))))
                    .append('\n');
        }
        // O _bulk responde 200 mesmo quando documentos falham; o erro real vem em "errors"/"items".
        JsonNode result = es.post().uri("/" + INDEX + "/_bulk?refresh=true").contentType(MediaType.APPLICATION_NDJSON)
                .body(bulk.toString().getBytes(StandardCharsets.UTF_8)).retrieve().body(JsonNode.class);
        if (result == null || result.path("errors").asBoolean()) {
            throw new IllegalStateException("Falha no _bulk: " + result);
        }
        log.info("Elasticsearch: {} receitas indexadas", docs.size());
    }

    /** Ids das receitas por relevância, ou null se o Elasticsearch não estiver pronto. */
    public List<Integer> search(String text) {
        if (!ready) {
            return null;
        }
        try {
            Map<String, Object> query = Map.of("size", 20, "query", Map.of("multi_match", Map.of(
                    "query", text,
                    "fields", List.of("name^3", "ingredients^2", "description", "mealType"),
                    "fuzziness", "AUTO",
                    // prefix_length 1: a primeira letra precisa bater ("frango" não traz "morango")
                    "prefix_length", 1)));
            JsonNode hits = es.post().uri("/" + INDEX + "/_search").contentType(MediaType.APPLICATION_JSON)
                    .body(json.writeValueAsBytes(query)).retrieve().body(JsonNode.class)
                    .path("hits").path("hits");
            List<Integer> ids = new ArrayList<>();
            hits.forEach(h -> ids.add(Integer.parseInt(h.path("_id").asText())));
            return ids;
        } catch (Exception e) {
            log.warn("Busca no Elasticsearch falhou: {}", e.getMessage());
            return null;
        }
    }
}
