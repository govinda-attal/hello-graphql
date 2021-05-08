package hello.graphql.queries

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import hello.models.Account
import com.expediagroup.graphql.server.operations.Query
import hello.graphql.Authorized
import hello.graphql.UserContext


class AccountsQuery : Query {
    @Suppress("unused")
    @GraphQLDescription("use this query to list all accounts")
    @Authorized
    fun getAccounts(ctx: UserContext): List<Account> {
        println("getAccounts called with contextual token: ${ctx.token}")
        return emptyList()
    }

}
