package hello.graphql

import io.micronaut.runtime.Micronaut.*
fun main(args: Array<String>) {
	build()
	    .args(*args)
		.packages("hello.graphql")
		.start()
}

