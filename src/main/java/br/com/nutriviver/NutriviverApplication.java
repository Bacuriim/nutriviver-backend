package br.com.nutriviver;

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.GraphqlErrorBuilder;

// O graphql-java lê os campos dos records por reflexão; no native image isso precisa ser registrado.
@RegisterReflectionForBinding({Types.Category.class, Types.Ingredient.class, Types.RecipeIngredient.class,
        Types.Recipe.class, Types.Named.class, Types.User.class})
@SpringBootApplication
public class NutriviverApplication {
    public static void main(String[] args) {
        SpringApplication.run(NutriviverApplication.class, args);
    }

    /** Mostra ao app as mensagens de validação (senão o GraphQL devolve só "INTERNAL_ERROR"). */
    @Bean
    DataFetcherExceptionResolver validationErrors() {
        return DataFetcherExceptionResolver.forSingleError((ex, env) -> ex instanceof IllegalArgumentException
                ? GraphqlErrorBuilder.newError(env).errorType(ErrorType.BAD_REQUEST).message(ex.getMessage()).build()
                : null);
    }
}
