package br.com.nutriviver;

import java.util.List;

/** Tipos expostos no GraphQL (ver schema.graphqls). */
public final class Types {
    private Types() {
    }

    public record Category(int id, String name) {
    }

    public record Ingredient(int id, String name, Category category) {
    }

    public record RecipeIngredient(Ingredient ingredient, double quantity, String measureUnity) {
    }

    public record Recipe(int id, String name, String description, double prepTime, String imageUrl,
                         String difficulty, String mealType, List<String> steps) {
    }

    /** Goal, Preference e Restriction têm o mesmo formato: id + nome. */
    public record Named(int id, String name) {
    }

    public record User(int id, String name, Integer goalId) {
    }
}
