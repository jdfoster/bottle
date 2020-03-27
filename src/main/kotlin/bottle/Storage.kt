package bottle

import io.lettuce.core.*
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.support.ConnectionPoolSupport
import org.apache.commons.pool2.impl.GenericObjectPool
import org.apache.commons.pool2.impl.GenericObjectPoolConfig

typealias RedisConn = StatefulRedisConnection<String, String>
typealias RedisPool = GenericObjectPool<RedisConn>

fun initRedisPool(uri: String): RedisPool {
    val client = RedisClient.create(uri)

    return ConnectionPoolSupport.createGenericObjectPool({
        client.connect()
    }, GenericObjectPoolConfig())
}

class Storage(
        private val conn: RedisConn,
        private val keyDelimiter: String = "/",
        private val valueDelimiter: String = ":"
) {
    companion object {
        fun new(conn: RedisConn): Storage {
            return Storage(conn)
        }
    }

    private fun makeKeyBase(kind: String): String {
        return keyDelimiter + kind + keyDelimiter
    }

    private fun makeKeyWithKind(kind: String, key: String): String {
        return makeKeyBase(kind) + key.toUpperCase() + keyDelimiter
    }

    private fun makeKeyWithValue(kind: String, key: String, value: String): String {
        return makeKeyWithKind(kind, key) + valueDelimiter + value
    }

    private fun toKey(kind: String, key: String? = null, value: String?= null): String {
        return when {
            key is String && value is String -> makeKeyWithValue(kind, key, value)
            key is String -> makeKeyWithKind(kind, key)
            else -> makeKeyBase(kind)
        }
    }

    private fun fromKey(key: String): Map<String, String> {
        return key.split(valueDelimiter).let { keyValue ->
            keyValue.first()
                    .removePrefix(keyDelimiter)
                    .removeSuffix(keyDelimiter)
                    .split(keyDelimiter)
                    .let {
                mapOf("kind" to it.first(), "key" to it.last(), "value" to keyValue.last())
            }
        }
    }

    fun pull(env: String, kind: String, key: String? = null): List<Map<String, String>>? {
        val query = toKey(kind, key)
        val range = Range.from(Range.Boundary.including(query), Range.Boundary.unbounded())

        return conn.sync().zrangebylex(env, range)?.let { items ->
            items.mapNotNull {
                fromKey(it)
            }
        }
    }

    fun push(env: String, kind: String, key: String, value: String): Map<String, String>? {
        return toKey(kind, key, value).let {keyValue ->
            conn.sync().zadd(env, 0.0, keyValue)?.let{
                return if (it > 0) fromKey(keyValue) else null
            }
        }
    }

    fun drop(env: String, kind: String, key: String? = null): Int {
        val query = toKey(kind, key)
        val range = Range.from(Range.Boundary.including(query), Range.Boundary.unbounded())

        return conn.sync().zremrangebylex(env, range)?.toInt() ?: 0
    }
}
