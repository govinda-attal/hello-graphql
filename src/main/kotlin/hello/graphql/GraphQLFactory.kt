package hello.graphql

import com.expediagroup.graphql.generator.SchemaGeneratorConfig
import com.expediagroup.graphql.generator.TopLevelObject
import com.expediagroup.graphql.generator.annotations.GraphQLDirective
import com.expediagroup.graphql.generator.directives.KotlinDirectiveWiringFactory
import com.expediagroup.graphql.generator.directives.KotlinFieldDirectiveEnvironment
import com.expediagroup.graphql.generator.directives.KotlinSchemaDirectiveWiring
import com.expediagroup.graphql.generator.execution.FunctionDataFetcher
import com.expediagroup.graphql.generator.execution.GraphQLContext
import com.expediagroup.graphql.generator.execution.SimpleKotlinDataFetcherFactoryProvider
import com.expediagroup.graphql.generator.hooks.SchemaGeneratorHooks
import com.expediagroup.graphql.generator.toSchema
import com.expediagroup.graphql.server.execution.GraphQLContextFactory
import com.expediagroup.graphql.server.extensions.toGraphQLError
import com.fasterxml.jackson.databind.ObjectMapper
import graphql.*
import graphql.execution.AsyncExecutionStrategy
import hello.graphql.queries.AccountsQuery
import graphql.execution.DataFetcherExceptionHandlerParameters
import graphql.execution.DataFetcherExceptionHandlerResult
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.language.SourceLocation
import graphql.schema.*
import io.micronaut.configuration.graphql.GraphQLExecutionInputCustomizer
import io.micronaut.context.annotation.Bean
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Primary
import io.micronaut.core.async.publisher.Publishers
import io.micronaut.http.HttpRequest
import io.micronaut.http.MutableHttpResponse
import kotlinx.coroutines.runBlocking
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import javax.inject.Singleton
import kotlin.reflect.KFunction
import kotlin.reflect.full.createType


@Suppress("unused")
@Factory
class GraphQLFactory{

    @Bean
    @Singleton
    @Suppress("unused")
    fun graphQL(exceptionHandler: CustomDataFetcherExceptionHandler): GraphQL {
        val hooks = object : SchemaGeneratorHooks {
            override val wiringFactory: KotlinDirectiveWiringFactory
                get() = KotlinDirectiveWiringFactory(manualWiring = mapOf("Authorized" to AuthorizedDirectiveWiring()))
        }
        val config = SchemaGeneratorConfig(
            supportedPackages = listOf("hello.models"),
            dataFetcherFactoryProvider = CustomDataFetcherFactoryProvider(objectMapper = ObjectMapper()),
            hooks = hooks
        )
        val queries = listOf(
            TopLevelObject(AccountsQuery())
        )
        return GraphQL.newGraphQL(toSchema(config, queries, emptyList()))
            .queryExecutionStrategy(AsyncExecutionStrategy(exceptionHandler))
            .mutationExecutionStrategy(AsyncExecutionStrategy(exceptionHandler))
            .build()
    }
}


class UserContext(val token: String) : GraphQLContext

@Singleton
class UserContextFactory : GraphQLContextFactory<UserContext, HttpRequest<*>> {
    override suspend fun generateContext(request: HttpRequest<*>): UserContext {
        val token = request.headers.get("Authorization")?.substringAfter("Bearer")?.trim() ?: ""
        println("token as found by context factory: $token")
        return UserContext(token = token)
    }
}

class CustomFunctionDataFetcher(target: Any?, fn: KFunction<*>, objectMapper: ObjectMapper) : FunctionDataFetcher(target, fn, objectMapper) {
    override fun get(environment: DataFetchingEnvironment): Any? {
        val ctx : UserContext? = environment.getContext<UserContext?>()
        println("contextual token: ${ctx?.token}")
        return super.get(environment)
    }
}

@Singleton
class CustomDataFetcherFactoryProvider(private val objectMapper: ObjectMapper) : SimpleKotlinDataFetcherFactoryProvider(objectMapper) {
    override fun functionDataFetcherFactory(target: Any?, kFunction: KFunction<*>) : DataFetcherFactory<Any?> {
        println("functionDataFetcherFactory() called")
        kFunction.parameters.let { params ->
            return when {
                params.size >= 2 && params[1].type == UserContext::class.createType() -> DataFetcherFactory { CustomFunctionDataFetcher(target, kFunction, objectMapper) }
                else -> super.functionDataFetcherFactory(target, kFunction)
            }
        }
    }
}

@GraphQLDirective(name = "Authorized",description = "authorized access only")
annotation class Authorized
@Suppress("unused")
class AuthorizedDirectiveWiring : KotlinSchemaDirectiveWiring {

    override fun onField(environment: KotlinFieldDirectiveEnvironment): GraphQLFieldDefinition {
        val field = environment.element
        val original: DataFetcher<*> = environment.getDataFetcher()

        val authDataFetcher = DataFetcher { env ->
            val ctx = env.getContext<UserContext>()
            if (ctx.token.isEmpty()) {
                throw UnAuthorizedException("unauthorized request")
            }
            original.get(env)
        }
        environment.setDataFetcher(authDataFetcher)
        return field
    }
}


@Suppress("unused")
@Primary// mark it as primary to override the default one
@Singleton
class AuthCustomizer(private val ctxFactory : UserContextFactory) : GraphQLExecutionInputCustomizer {
    override fun customize(
        executionInput: ExecutionInput?,
        httpRequest: HttpRequest<*>?,
        httpResponse: MutableHttpResponse<String>?
    ): Publisher<ExecutionInput>? {
        val ctx = runBlocking {
            ctxFactory.generateContext(httpRequest!!)
        }
        return Publishers.just(executionInput?.transform { builder ->
            builder.context(ctx)
        })
    }
}


open class UnAuthorizedException(errorMessage: String? = "") : GraphQLException(errorMessage) {
    override val message: String?
        get() = super.message
}


@Singleton
class CustomDataFetcherExceptionHandler : SimpleDataFetcherExceptionHandler() {

    private val log = LoggerFactory.getLogger(this.javaClass)

    override fun onException(handlerParameters: DataFetcherExceptionHandlerParameters): DataFetcherExceptionHandlerResult {
        val exception = handlerParameters.exception
        log.error("Exception while GraphQL data fetching", exception)

        return when (exception) {
            is GraphQLException -> {
                DataFetcherExceptionHandlerResult.newResult().error(exception.toGraphQLError()).build()
            }
            is GraphQLError -> {
                DataFetcherExceptionHandlerResult.newResult().error(exception).build()
            }
            else -> {
                val error = object : GraphQLError {
                    override fun getMessage(): String = "There was an error: ${exception.message}"
                    override fun getErrorType(): ErrorType? = null
                    override fun getLocations(): MutableList<SourceLocation>? = null
                }
                DataFetcherExceptionHandlerResult.newResult().error(error).build()
            }
        }
    }
}