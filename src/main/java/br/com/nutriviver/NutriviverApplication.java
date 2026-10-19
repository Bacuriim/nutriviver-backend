package br.com.nutriviver;

import graphql.GraphqlErrorBuilder;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.graphql.execution.DataFetcherExceptionResolver;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.server.WebGraphQlInterceptor;

import java.util.Map;

// O graphql-java lê os campos dos records por reflexão; no native image isso precisa ser registrado.
@RegisterReflectionForBinding({Types.Category.class, Types.Ingredient.class, Types.RecipeIngredient.class,
        Types.Recipe.class, Types.Named.class, Types.User.class, Types.AuthPayload.class, Types.PlanItem.class,
        Types.DayStat.class, Types.Stats.class})
@SpringBootApplication
public class NutriviverApplication {
    public static void main(String[] args) {
        SpringApplication.run(NutriviverApplication.class, args);
    }

    /** Erro de validação: a mensagem vai para o app (senão o GraphQL devolve só "INTERNAL_ERROR"). */
    static class BadInput extends RuntimeException {
        BadInput(String message) {
            super(message);
        }
    }

    /** Requisição sem token válido. */
    static class NotLoggedIn extends RuntimeException {
        NotLoggedIn() {
            super("Faça login para continuar");
        }
    }

    @Bean
    DataFetcherExceptionResolver errors() {
        return DataFetcherExceptionResolver.forSingleError((ex, env) -> {
            if (ex instanceof BadInput) {
                return GraphqlErrorBuilder.newError(env).errorType(ErrorType.BAD_REQUEST).message(ex.getMessage()).build();
            }
            if (ex instanceof NotLoggedIn) {
                return GraphqlErrorBuilder.newError(env).errorType(ErrorType.UNAUTHORIZED).message(ex.getMessage()).build();
            }
            return null;
        });
    }

    /** Copia o token do header "Authorization: Bearer ..." para o contexto do GraphQL (@ContextValue token). */
    @Bean
    WebGraphQlInterceptor authToken() {
        return (request, chain) -> {
            String header = request.getHeaders().getFirst("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7).trim();
                request.configureExecutionInput((input, builder) ->
                        builder.graphQLContext(Map.of("token", token)).build());
            }
            return chain.next(request);
        };
    }
}
