package br.com.nutriviver;

import br.com.nutriviver.Types.Category;
import br.com.nutriviver.Types.Ingredient;
import br.com.nutriviver.Types.Named;
import br.com.nutriviver.Types.Recipe;
import br.com.nutriviver.Types.RecipeIngredient;
import br.com.nutriviver.Types.User;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class GraphqlController {
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();

    private static final RowMapper<Recipe> RECIPE = (rs, n) -> new Recipe(
            rs.getInt("id_recipe"), rs.getString("name"), rs.getString("description"), rs.getDouble("prep_time"),
            rs.getString("image_url"), rs.getString("difficulty"), rs.getString("meal_type"),
            // data.sql guarda os passos separados por "\n" literal
            rs.getString("steps") == null ? List.of() : Arrays.asList(rs.getString("steps").split("\\\\n")));

    private static final RowMapper<User> USER = (rs, n) -> new User(
            rs.getInt("id_user"), rs.getString("name"), (Integer) rs.getObject("id_goal"));

    private final JdbcClient db;
    private final RecipeSearch search;

    public GraphqlController(JdbcClient db, RecipeSearch search) {
        this.db = db;
        this.search = search;
    }

    // ---------- Receitas ----------

    @QueryMapping
    public List<Recipe> recipes(@Argument String mealType, @Argument Double maxPrepTime,
                                @Argument List<String> ingredients) {
        StringBuilder sql = new StringBuilder("SELECT * FROM recipe r WHERE TRUE");
        Map<String, Object> params = new HashMap<>();
        if (mealType != null) {
            sql.append(" AND r.meal_type = :mealType");
            params.put("mealType", mealType);
        }
        if (maxPrepTime != null) {
            sql.append(" AND r.prep_time <= :maxPrepTime");
            params.put("maxPrepTime", maxPrepTime);
        }
        if (ingredients != null && !ingredients.isEmpty()) {
            sql.append("""
                     AND EXISTS (SELECT 1 FROM recipe_ingredient ri
                                 JOIN ingredient i ON i.id_ingredient = ri.id_ingredient
                                 WHERE ri.id_recipe = r.id_recipe AND i.name IN (:ingredients))""");
            params.put("ingredients", ingredients);
        }
        sql.append(" ORDER BY r.prep_time");
        return db.sql(sql.toString()).params(params).query(RECIPE).list();
    }

    @QueryMapping
    public Recipe recipe(@Argument int id) {
        return db.sql("SELECT * FROM recipe WHERE id_recipe = ?").param(id).query(RECIPE).optional().orElse(null);
    }

    @QueryMapping
    public List<Recipe> searchRecipes(@Argument String text) {
        List<Integer> ids = search.search(text);
        if (ids == null) {
            // ponytail: fallback em ILIKE enquanto o Elasticsearch não sobe; sem relevância nem tolerância a erro de digitação
            return db.sql("""
                    SELECT DISTINCT r.* FROM recipe r
                    LEFT JOIN recipe_ingredient ri ON ri.id_recipe = r.id_recipe
                    LEFT JOIN ingredient i ON i.id_ingredient = ri.id_ingredient
                    WHERE r.name ILIKE :q OR r.description ILIKE :q OR i.name ILIKE :q""")
                    .param("q", "%" + text + "%").query(RECIPE).list();
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Recipe> found = new ArrayList<>(db.sql("SELECT * FROM recipe WHERE id_recipe IN (:ids)")
                .param("ids", ids).query(RECIPE).list());
        found.sort(Comparator.comparingInt(r -> ids.indexOf(r.id())));
        return found;
    }

    @SchemaMapping(typeName = "Recipe", field = "ingredients")
    public List<RecipeIngredient> recipeIngredients(Recipe recipe) {
        return db.sql("""
                SELECT i.id_ingredient, i.name, c.id_category, c.name AS category_name,
                       ri.quantity, ri.measure_unity
                FROM recipe_ingredient ri
                JOIN ingredient i ON i.id_ingredient = ri.id_ingredient
                JOIN category c ON c.id_category = i.category
                WHERE ri.id_recipe = ?""").param(recipe.id())
                .query((rs, n) -> new RecipeIngredient(
                        new Ingredient(rs.getInt("id_ingredient"), rs.getString("name"),
                                new Category(rs.getInt("id_category"), rs.getString("category_name"))),
                        rs.getDouble("quantity"), rs.getString("measure_unity")))
                .list();
    }

    // ---------- Catálogos ----------

    @QueryMapping
    public List<Category> categories() {
        return db.sql("SELECT id_category, name FROM category ORDER BY name")
                .query((rs, n) -> new Category(rs.getInt(1), rs.getString(2))).list();
    }

    @QueryMapping
    public List<Ingredient> ingredients() {
        return db.sql("""
                SELECT i.id_ingredient, i.name, c.id_category, c.name FROM ingredient i
                JOIN category c ON c.id_category = i.category ORDER BY i.name""")
                .query((rs, n) -> new Ingredient(rs.getInt(1), rs.getString(2),
                        new Category(rs.getInt(3), rs.getString(4)))).list();
    }

    @QueryMapping
    public List<Named> goals() {
        return named("SELECT id_goal, goal_name FROM goal ORDER BY id_goal");
    }

    @QueryMapping
    public List<Named> preferences() {
        return named("SELECT id_preference, preference_name FROM preference ORDER BY id_preference");
    }

    @QueryMapping
    public List<Named> restrictions() {
        return named("SELECT id_restriction, restriction_name FROM restriction ORDER BY id_restriction");
    }

    // ---------- Usuário ----------

    @QueryMapping
    public User user(@Argument int id) {
        return findUser(id);
    }

    @MutationMapping
    public User signUp(@Argument String name, @Argument String password) {
        if (name.isBlank() || password.length() < 4) {
            throw new IllegalArgumentException("Nome obrigatório e senha com no mínimo 4 caracteres");
        }
        if (db.sql("SELECT COUNT(*) FROM users WHERE name = ?").param(name.trim()).query(Long.class).single() > 0) {
            throw new IllegalArgumentException("Usuário já existe");
        }
        int id = db.sql("INSERT INTO users (name, password) VALUES (?, ?) RETURNING id_user")
                .params(name.trim(), BCRYPT.encode(password)).query(Integer.class).single();
        return findUser(id);
    }

    @MutationMapping
    public User login(@Argument String name, @Argument String password) {
        return db.sql("SELECT id_user, password FROM users WHERE name = ?").param(name.trim())
                .query((rs, n) -> BCRYPT.matches(password, rs.getString("password")) ? rs.getInt("id_user") : null)
                .optional()
                .map(id -> id == null ? null : findUser(id))
                .orElse(null);
    }

    @Transactional
    @MutationMapping
    public User updateProfile(@Argument int userId, @Argument String goal,
                              @Argument List<String> preferences, @Argument List<String> restrictions) {
        if (findUser(userId) == null) {
            throw new IllegalArgumentException("Usuário não encontrado");
        }
        if (goal != null) {
            int updated = db.sql("UPDATE users SET id_goal = (SELECT id_goal FROM goal WHERE goal_name = ?) "
                    + "WHERE id_user = ? AND EXISTS (SELECT 1 FROM goal WHERE goal_name = ?)")
                    .params(goal, userId, goal).update();
            if (updated == 0) {
                throw new IllegalArgumentException("Objetivo inválido: " + goal);
            }
        }
        if (preferences != null) {
            replaceLinks(userId, preferences, "preference", "preference_name", "user_preference", "id_preference");
        }
        if (restrictions != null) {
            replaceLinks(userId, restrictions, "restriction", "restriction_name", "user_restriction", "id_restriction");
        }
        return findUser(userId);
    }

    @SchemaMapping(typeName = "User", field = "goal")
    public Named userGoal(User user) {
        return user.goalId() == null ? null
                : named("SELECT id_goal, goal_name FROM goal WHERE id_goal = " + user.goalId()).get(0);
    }

    @SchemaMapping(typeName = "User", field = "preferences")
    public List<Named> userPreferences(User user) {
        return named("SELECT p.id_preference, p.preference_name FROM preference p "
                + "JOIN user_preference up ON up.id_preference = p.id_preference WHERE up.id_user = " + user.id());
    }

    @SchemaMapping(typeName = "User", field = "restrictions")
    public List<Named> userRestrictions(User user) {
        return named("SELECT r.id_restriction, r.restriction_name FROM restriction r "
                + "JOIN user_restriction ur ON ur.id_restriction = r.id_restriction WHERE ur.id_user = " + user.id());
    }

    // ---------- Auxiliares ----------

    private User findUser(int id) {
        return db.sql("SELECT id_user, name, id_goal FROM users WHERE id_user = ?").param(id)
                .query(USER).optional().orElse(null);
    }

    /** Só recebe SQL fixo deste arquivo (ids inteiros concatenados, nunca texto do cliente). */
    private List<Named> named(String sql) {
        return db.sql(sql).query((rs, n) -> new Named(rs.getInt(1), rs.getString(2))).list();
    }

    /** Troca os vínculos do usuário (user_preference / user_restriction), criando nomes novos se preciso. */
    private void replaceLinks(int userId, List<String> names, String table, String nameCol,
                              String linkTable, String idCol) {
        db.sql("DELETE FROM " + linkTable + " WHERE id_user = ?").param(userId).update();
        for (String name : names) {
            String n = name.trim();
            if (n.isEmpty()) {
                continue;
            }
            db.sql("INSERT INTO " + table + " (" + nameCol + ") VALUES (?) ON CONFLICT DO NOTHING").param(n).update();
            db.sql("INSERT INTO " + linkTable + " (id_user, " + idCol + ") SELECT ?, " + idCol + " FROM " + table
                    + " WHERE " + nameCol + " = ? ON CONFLICT DO NOTHING").params(userId, n).update();
        }
    }
}
