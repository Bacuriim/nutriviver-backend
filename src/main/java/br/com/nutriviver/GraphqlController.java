package br.com.nutriviver;

import br.com.nutriviver.NutriviverApplication.BadInput;
import br.com.nutriviver.NutriviverApplication.NotLoggedIn;
import br.com.nutriviver.Types.AuthPayload;
import br.com.nutriviver.Types.Category;
import br.com.nutriviver.Types.DayStat;
import br.com.nutriviver.Types.Ingredient;
import br.com.nutriviver.Types.Named;
import br.com.nutriviver.Types.PlanItem;
import br.com.nutriviver.Types.Recipe;
import br.com.nutriviver.Types.RecipeIngredient;
import br.com.nutriviver.Types.Stats;
import br.com.nutriviver.Types.User;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class GraphqlController {
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private static final List<String> MEAL_TYPES = List.of("Café da manhã", "Almoço", "Jantar", "Lanche", "Sobremesa");

    private static final RowMapper<Recipe> RECIPE = (rs, n) -> new Recipe(
            rs.getInt("id_recipe"), rs.getString("name"), rs.getString("description"), rs.getDouble("prep_time"),
            rs.getString("image_url"), rs.getString("difficulty"), rs.getString("meal_type"),
            // data.sql guarda os passos separados por "\n" literal
            rs.getString("steps") == null ? List.of() : Arrays.asList(rs.getString("steps").split("\\\\n")),
            (Integer) rs.getObject("calories"), (Double) rs.getObject("protein"));

    private static final RowMapper<User> USER = (rs, n) -> new User(
            rs.getInt("id_user"), rs.getString("name"), (Integer) rs.getObject("id_goal"));

    private static final RowMapper<PlanItem> PLAN_ITEM = (rs, n) -> new PlanItem(
            rs.getInt("id_plan"), rs.getInt("id_recipe"), rs.getString("day"), rs.getString("time"),
            rs.getString("meal_type"), rs.getString("note"), rs.getBoolean("done"));

    private static final String PLAN_COLUMNS = "id_plan, id_recipe, day::text AS day, time, meal_type, note, done";

    private final JdbcClient db;
    private final RecipeSearch search;

    public GraphqlController(JdbcClient db, RecipeSearch search) {
        this.db = db;
        this.search = search;
    }

    // ---------- Receitas ----------

    @QueryMapping
    public List<Recipe> recipes(@Argument List<String> mealTypes, @Argument Double minPrepTime,
                                @Argument Double maxPrepTime, @Argument List<String> ingredients) {
        StringBuilder sql = new StringBuilder("SELECT * FROM recipe r WHERE TRUE");
        Map<String, Object> params = new HashMap<>();
        if (mealTypes != null && !mealTypes.isEmpty()) {
            sql.append(" AND r.meal_type IN (:mealTypes)");
            params.put("mealTypes", mealTypes);
        }
        if (minPrepTime != null) {
            sql.append(" AND r.prep_time >= :minPrepTime");
            params.put("minPrepTime", minPrepTime);
        }
        if (maxPrepTime != null) {
            sql.append(" AND r.prep_time <= :maxPrepTime");
            params.put("maxPrepTime", maxPrepTime);
        }
        if (ingredients != null && !ingredients.isEmpty()) {
            sql.append(" AND ").append("""
                    EXISTS (SELECT 1 FROM recipe_ingredient ri
                            JOIN ingredient i ON i.id_ingredient = ri.id_ingredient
                            WHERE ri.id_recipe = r.id_recipe AND i.name IN (:ingredients))""");
            params.put("ingredients", ingredients);
        }
        sql.append(" ORDER BY r.prep_time, r.name");
        return db.sql(sql.toString()).params(params).query(RECIPE).list();
    }

    @QueryMapping
    public Recipe recipe(@Argument int id) {
        return findRecipe(id);
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

    @QueryMapping
    public List<Recipe> recommendedRecipes(@ContextValue(required = false) String token, @Argument int limit) {
        int userId = userId(token);
        String goal = db.sql("SELECT g.goal_name FROM users u JOIN goal g ON g.id_goal = u.id_goal WHERE u.id_user = ?")
                .param(userId).query(String.class).optional().orElse("");
        // Exclui receitas com ingrediente restrito; ordena por alimentos preferidos e depois pelo objetivo.
        return db.sql("""
                SELECT r.* FROM recipe r
                WHERE NOT EXISTS (
                    SELECT 1 FROM recipe_ingredient ri
                    JOIN ingredient i ON i.id_ingredient = ri.id_ingredient
                    JOIN user_restriction ur ON ur.id_restriction = i.restriction AND ur.id_user = :u
                    WHERE ri.id_recipe = r.id_recipe)
                ORDER BY (
                    SELECT COUNT(*) FROM recipe_ingredient ri
                    JOIN ingredient i ON i.id_ingredient = ri.id_ingredient
                    JOIN preference p ON p.preference_name = i.name
                    JOIN user_preference up ON up.id_preference = p.id_preference AND up.id_user = :u
                    WHERE ri.id_recipe = r.id_recipe) DESC,
                  CASE :goal WHEN 'Perder peso' THEN r.calories
                             WHEN 'Ganhar massa' THEN -r.protein
                             ELSE r.prep_time END,
                  r.name
                LIMIT :limit""")
                .param("u", userId).param("goal", goal).param("limit", Math.max(1, Math.min(limit, 50)))
                .query(RECIPE).list();
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

    @SchemaMapping(typeName = "Recipe", field = "favorite")
    public boolean recipeFavorite(Recipe recipe, @ContextValue(required = false) String token) {
        Integer userId = optionalUserId(token);
        return userId != null && db.sql("SELECT COUNT(*) FROM user_favorite WHERE id_user = ? AND id_recipe = ?")
                .params(userId, recipe.id()).query(Long.class).single() > 0;
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

    // ---------- Conta ----------

    @MutationMapping
    public AuthPayload signUp(@Argument String name, @Argument String password) {
        if (name.isBlank() || password.length() < 4) {
            throw new BadInput("Nome obrigatório e senha com no mínimo 4 caracteres");
        }
        if (db.sql("SELECT COUNT(*) FROM users WHERE name = ?").param(name.trim()).query(Long.class).single() > 0) {
            throw new BadInput("Usuário já existe");
        }
        String token = UUID.randomUUID().toString();
        int id = db.sql("INSERT INTO users (name, password, token) VALUES (?, ?, ?) RETURNING id_user")
                .params(name.trim(), BCRYPT.encode(password), token).query(Integer.class).single();
        return new AuthPayload(token, findUser(id));
    }

    @MutationMapping
    public AuthPayload login(@Argument String name, @Argument String password) {
        Integer id = db.sql("SELECT id_user, password FROM users WHERE name = ?").param(name.trim())
                .query((rs, n) -> BCRYPT.matches(password, rs.getString("password")) ? rs.getInt("id_user") : null)
                .optional().orElse(null);
        if (id == null) {
            return null;
        }
        // ponytail: um token por usuário (login novo derruba a sessão anterior); tabela de sessões se precisar de vários aparelhos
        String token = UUID.randomUUID().toString();
        db.sql("UPDATE users SET token = ? WHERE id_user = ?").params(token, id).update();
        return new AuthPayload(token, findUser(id));
    }

    @MutationMapping
    public boolean logout(@ContextValue(required = false) String token) {
        db.sql("UPDATE users SET token = NULL WHERE id_user = ?").param(userId(token)).update();
        return true;
    }

    @QueryMapping
    public User me(@ContextValue(required = false) String token) {
        return findUser(userId(token));
    }

    @Transactional
    @MutationMapping
    public User updateProfile(@ContextValue(required = false) String token, @Argument String goal,
                              @Argument List<String> preferences, @Argument List<String> restrictions) {
        int userId = userId(token);
        if (goal != null) {
            int updated = db.sql("UPDATE users SET id_goal = (SELECT id_goal FROM goal WHERE goal_name = ?) "
                    + "WHERE id_user = ? AND EXISTS (SELECT 1 FROM goal WHERE goal_name = ?)")
                    .params(goal, userId, goal).update();
            if (updated == 0) {
                throw new BadInput("Objetivo inválido: " + goal);
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

    @MutationMapping
    public boolean toggleFavorite(@ContextValue(required = false) String token, @Argument int recipeId) {
        int userId = userId(token);
        int removed = db.sql("DELETE FROM user_favorite WHERE id_user = ? AND id_recipe = ?")
                .params(userId, recipeId).update();
        if (removed > 0) {
            return false;
        }
        db.sql("INSERT INTO user_favorite (id_user, id_recipe) VALUES (?, ?)").params(userId, recipeId).update();
        return true;
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

    @SchemaMapping(typeName = "User", field = "favorites")
    public List<Recipe> userFavorites(User user) {
        return db.sql("SELECT r.* FROM recipe r JOIN user_favorite f ON f.id_recipe = r.id_recipe "
                + "WHERE f.id_user = ? ORDER BY r.name").param(user.id()).query(RECIPE).list();
    }

    // ---------- Cardápio, água e progresso ----------

    @QueryMapping
    public List<PlanItem> plan(@ContextValue(required = false) String token, @Argument String from, @Argument String to) {
        return db.sql("SELECT " + PLAN_COLUMNS + " FROM meal_plan WHERE id_user = ? AND day BETWEEN ? AND ? "
                        + "ORDER BY day, time")
                .params(userId(token), date(from), date(to)).query(PLAN_ITEM).list();
    }

    @MutationMapping
    public PlanItem addToPlan(@ContextValue(required = false) String token, @Argument int recipeId,
                              @Argument String day, @Argument String time, @Argument String mealType,
                              @Argument String note) {
        int userId = userId(token);
        if (findRecipe(recipeId) == null) {
            throw new BadInput("Receita não encontrada");
        }
        return db.sql("INSERT INTO meal_plan (id_user, id_recipe, day, time, meal_type, note) VALUES (?, ?, ?, ?, ?, ?) "
                        + "RETURNING " + PLAN_COLUMNS)
                .params(userId, recipeId, date(day), time(time), mealType(mealType), note)
                .query(PLAN_ITEM).single();
    }

    @MutationMapping
    public PlanItem updatePlanItem(@ContextValue(required = false) String token, @Argument int id,
                                   @Argument Integer recipeId, @Argument String day, @Argument String time,
                                   @Argument String mealType, @Argument String note, @Argument Boolean done) {
        int userId = userId(token);
        if (recipeId != null && findRecipe(recipeId) == null) {
            throw new BadInput("Receita não encontrada");
        }
        // id_user no WHERE: um usuário não altera o cardápio de outro.
        return db.sql("""
                        UPDATE meal_plan SET
                            id_recipe = COALESCE(:recipeId, id_recipe),
                            day = COALESCE(CAST(:day AS date), day),
                            time = COALESCE(CAST(:time AS text), time),
                            meal_type = COALESCE(CAST(:mealType AS text), meal_type),
                            note = COALESCE(CAST(:note AS text), note),
                            done = COALESCE(CAST(:done AS boolean), done)
                        WHERE id_plan = :id AND id_user = :u
                        RETURNING""" + " " + PLAN_COLUMNS)
                .param("recipeId", recipeId)
                .param("day", day == null ? null : date(day))
                .param("time", time == null ? null : time(time))
                .param("mealType", mealType == null ? null : mealType(mealType))
                .param("note", note)
                .param("done", done)
                .param("id", id).param("u", userId)
                .query(PLAN_ITEM).optional()
                .orElseThrow(() -> new BadInput("Refeição não encontrada"));
    }

    @MutationMapping
    public boolean removePlanItem(@ContextValue(required = false) String token, @Argument int id) {
        return db.sql("DELETE FROM meal_plan WHERE id_plan = ? AND id_user = ?").params(id, userId(token)).update() > 0;
    }

    @SchemaMapping(typeName = "PlanItem", field = "recipe")
    public Recipe planItemRecipe(PlanItem item) {
        return findRecipe(item.recipeId());
    }

    @QueryMapping
    public int water(@ContextValue(required = false) String token, @Argument String day) {
        return db.sql("SELECT ml FROM water_intake WHERE id_user = ? AND day = ?").params(userId(token), date(day))
                .query(Integer.class).optional().orElse(0);
    }

    @MutationMapping
    public int addWater(@ContextValue(required = false) String token, @Argument String day, @Argument int ml) {
        return db.sql("""
                        INSERT INTO water_intake (id_user, day, ml) VALUES (?, ?, GREATEST(0, ?))
                        ON CONFLICT (id_user, day) DO UPDATE SET ml = GREATEST(0, water_intake.ml + ?)
                        RETURNING ml""")
                .params(userId(token), date(day), ml, ml).query(Integer.class).single();
    }

    @QueryMapping
    public Stats stats(@ContextValue(required = false) String token, @Argument String from, @Argument String to) {
        int userId = userId(token);
        LocalDate start = date(from);
        LocalDate end = date(to);
        List<DayStat> days = db.sql("""
                        SELECT day::text, COUNT(*), COUNT(*) FILTER (WHERE done) FROM meal_plan
                        WHERE id_user = ? AND day BETWEEN ? AND ? GROUP BY day ORDER BY day""")
                .params(userId, start, end)
                .query((rs, n) -> new DayStat(rs.getString(1), rs.getInt(2), rs.getInt(3))).list();
        int planned = days.stream().mapToInt(DayStat::planned).sum();
        int done = days.stream().mapToInt(DayStat::done).sum();
        int daysOnPlan = (int) days.stream().filter(d -> d.done() == d.planned()).count();
        int recipesTried = db.sql("SELECT COUNT(DISTINCT id_recipe) FROM meal_plan "
                        + "WHERE id_user = ? AND done AND day BETWEEN ? AND ?")
                .params(userId, start, end).query(Integer.class).single();
        int fruitVeg = db.sql("""
                        SELECT COUNT(*) FROM meal_plan m
                        JOIN recipe_ingredient ri ON ri.id_recipe = m.id_recipe
                        JOIN ingredient i ON i.id_ingredient = ri.id_ingredient
                        JOIN category c ON c.id_category = i.category
                        WHERE m.id_user = ? AND m.done AND m.day BETWEEN ? AND ? AND c.name IN ('Fruta', 'Vegetal')""")
                .params(userId, start, end).query(Integer.class).single();
        return new Stats(days, planned, done, recipesTried, days.size(), daysOnPlan,
                days.isEmpty() ? 0 : (double) fruitVeg / days.size(),
                done == 0 ? 0 : (double) recipesTried / done);
    }

    // ---------- Auxiliares ----------

    private Integer optionalUserId(String token) {
        return token == null ? null : db.sql("SELECT id_user FROM users WHERE token = ?").param(token)
                .query(Integer.class).optional().orElse(null);
    }

    private int userId(String token) {
        Integer id = optionalUserId(token);
        if (id == null) {
            throw new NotLoggedIn();
        }
        return id;
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BadInput("Data inválida (use AAAA-MM-DD): " + value);
        }
    }

    private static String time(String value) {
        try {
            return LocalTime.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw new BadInput("Horário inválido (use HH:MM): " + value);
        }
    }

    private static String mealType(String value) {
        if (!MEAL_TYPES.contains(value)) {
            throw new BadInput("Refeição inválida: " + value + ". Use " + MEAL_TYPES);
        }
        return value;
    }

    private Recipe findRecipe(int id) {
        return db.sql("SELECT * FROM recipe WHERE id_recipe = ?").param(id).query(RECIPE).optional().orElse(null);
    }

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
