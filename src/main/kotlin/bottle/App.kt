package bottle

import org.http4k.core.*
import org.http4k.core.Status.Companion.BAD_REQUEST
import org.http4k.core.Status.Companion.INTERNAL_SERVER_ERROR
import org.http4k.core.Status.Companion.NOT_FOUND
import org.http4k.core.Status.Companion.NO_CONTENT
import org.http4k.core.Status.Companion.OK
import org.http4k.filter.ServerFilters
import org.http4k.format.Jackson.auto
import org.http4k.lens.*
import org.http4k.routing.*
import org.http4k.server.Jetty
import org.http4k.server.asServer
import sun.misc.Signal
import kotlin.system.exitProcess

class ParameterEntry(item: Map<String, String>) {
    val key: String by item
    val kind: String by item
    val value: String by item
}

val ParameterEntryLens = Body.auto<ParameterEntry>().toLens()
val ParameterListLens = Body.auto<List<ParameterEntry>>().toLens()

sealed class Result<out T>
data class Succeeded<out T>(val value: T): Result<T>()
data class Failed<out T>(val e: Exception): Result<T>()

fun <IN, OUT> LensExtractor<IN, OUT>.toResult(): LensExtractor<IN, Result<OUT>> = object: LensExtractor<IN, Result<OUT>> {
    override fun invoke(target: IN): Result<OUT> = try {
        Succeeded(this@toResult.invoke(target))
    } catch (e: LensFailure) {
        Failed(e)
    }
}

data class SharedState(val store: Storage)

fun main() {
    val pool = initRedisPool("redis://localhost:6379")

    fun addState(key: RequestContextLens<SharedState>) = Filter { next ->
        { req ->
            pool.borrowObject().use{ conn ->
                next(req.with(key of SharedState(Storage.new(conn))))
            }
        }
    }

    val pathEnv = Path.of("env")
    val pathKind = Path.of("kind")
    val pathKey = Path.of("key")

    fun addRoutes(ctxLens: RequestContextLens<SharedState>): RoutingHttpHandler =  routes(
            "/environment/{env}" bind routes(
                    "/kind/{kind}" bind routes(
                            "/" bind Method.GET to { req : Request ->
                                val state = ctxLens(req)
                                val env = pathEnv(req)
                                val kind = pathKind(req)

                                state.store.pull(env, kind)?.let { items ->
                                    items.map {
                                        ParameterEntry(it)
                                    }.let {
                                        ParameterListLens(it, Response(OK))
                                    }
                                } ?: Response(NOT_FOUND)
                            },
                            "/" bind Method.DELETE to { req: Request ->
                                val state = ctxLens(req)
                                val env = pathEnv(req)
                                val kind = pathKind(req)

                                state.store.drop(env, kind).let {
                                    if (it < 1L) {
                                        Response(NO_CONTENT)
                                    } else {
                                        Response(OK).body("Deleted $it key(s).")
                                    }
                                }
                            },
                            "/{key}" bind Method.GET to { req: Request ->
                                val state = ctxLens(req)
                                val env = pathEnv(req)
                                val kind = pathKind(req)
                                val key = pathKey(req)

                                state.store.pull(env, kind, key)?.let { item ->
                                    if (item.isEmpty()) {
                                        Response(NOT_FOUND)
                                    } else {
                                        val reply = ParameterEntry(item.first())
                                        ParameterEntryLens(reply, Response(OK))
                                    }
                                } ?: Response(NOT_FOUND)
                            },
                            "/{key}" bind Method.POST to { req: Request ->
                                val state = ctxLens(req)
                                val env = pathEnv(req)
                                val kind = pathKind(req)
                                val key = pathKey(req)

                                ParameterEntryLens.toResult().let {lens ->
                                    lens(req).let {
                                        when(it) {
                                            is Succeeded -> {
                                                if (kind != it.value.kind || key != it.value.key) {
                                                    Response(BAD_REQUEST)
                                                } else {
                                                    state.store.push(env, kind, key, it.value.value)?.let {
                                                        Response(OK)
                                                    } ?: Response(INTERNAL_SERVER_ERROR)
                                                }
                                            }
                                            is Failed -> Response(BAD_REQUEST)
                                        }
                                    }
                                }
                            },
                            "/{key}" bind Method.DELETE to { req: Request ->
                                val state = ctxLens(req)
                                val env = pathEnv(req)
                                val kind = pathKind(req)
                                val key = pathKey(req)

                                state.store.drop(env, kind, key).let{
                                    if (it < 1L) {
                                        Response(NO_CONTENT)
                                    } else {
                                        Response(OK).body("Deleted $it key(s).")
                                    }
                                }
                            }
                    )
            )
    )

    val contexts = RequestContexts()
    val key = RequestContextKey.required<SharedState>(contexts)
    val app = ServerFilters.InitialiseRequestContext(contexts)
            .then(addState(key))
            .then(addRoutes(key))
    val server = app.asServer(Jetty(9000)).start()

    Signal.handle(Signal("INT")) {
        server.stop()
        exitProcess(0)
    }
}
