package io.eigr.synapsys.core.internals.serialization

import com.google.protobuf.Any
import com.google.protobuf.ByteString
import io.eigr.synapsys.core.internals.MessageSerializer
import io.protostuff.LinkedBuffer
import io.protostuff.ProtobufIOUtil
import io.protostuff.Schema
import io.protostuff.runtime.RuntimeSchema

/**
 * Protobuf-based implementation of [MessageSerializer] using Protostuff runtime schemas.
 * Provides efficient binary serialization with type preservation through Protocol Buffers' Any type.
 *
 * <h2>Features:</h2>
 * <ul>
 *   <li>Runtime schema generation for POJOs</li>
 *   <li>Automatic type URL embedding (type.googleapis.com/fully.qualified.ClassName)</li>
 *   <li>Schema caching for performance optimization</li>
 *   <li>Protocol Buffers Any wrapper for type safety</li>
 * </ul>
 *
 * @see MessageSerializer
 * @see Any
 */
class ProtobufMessageSerializer : MessageSerializer {
    private val buffer = LinkedBuffer.allocate(512)
    private val schemaCache = mutableMapOf<Class<*>, Schema<*>>()

    /**
     * Serializes an object to protobuf format wrapped in an Any container.
     *
     * @param obj Object to serialize (must be non-null)
     * @return Byte array containing serialized Any message
     * @throws NullPointerException if input object is null
     *
     * @sample
     * val serializer = ProtobufMessageSerializer()
     * val bytes = serializer.serialize(MyData("test"))
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T> serialize(obj: T): ByteArray {
        val clazz = obj!!.javaClass
        val schema = getSchema(clazz) as Schema<T>

        val bytes = ProtobufIOUtil.toByteArray(obj, schema, buffer)
        buffer.clear()

        return Any.newBuilder()
            .setTypeUrl(typeUrlFor(clazz))
            .setValue(ByteString.copyFrom(bytes))
            .build()
            .toByteArray()
    }

    /**
     * Deserializes bytes back to original type using embedded type information.
     *
     * @param bytes Serialized Any message bytes
     * @param clazz Expected target class
     * @return Deserialized object instance
     * @throws IllegalArgumentException if type mismatch occurs
     * @throws ClassNotFoundException if original type isn't available
     *
     * @sample
     * val obj = serializer.deserialize(bytes, MyData::class.java)
     */
    @Suppress("UNCHECKED_CAST")
    override fun <T> deserialize(bytes: ByteArray, clazz: Class<T>): T {
        val any = Any.parseFrom(bytes)
        val targetClass = classForTypeUrl(any.typeUrl)

        if (!clazz.isAssignableFrom(targetClass)) {
            throw IllegalArgumentException("Type mismatch: ${targetClass.name} is not assignable to ${clazz.name}")
        }

        val schema = getSchema(targetClass) as Schema<T>
        val message = schema.newMessage()
        ProtobufIOUtil.mergeFrom(any.value.toByteArray(), message, schema)

        return message
    }

    /**
     * @internal
     * Generates type URL following Google's type.googleapis.com convention
     * @param clazz Class to generate URL for
     * @return Type URL string
     */
    private fun typeUrlFor(clazz: Class<*>): String {
        return "type.googleapis.com/${clazz.name}"
    }

    /**
     * @interval
     * Resolves class from type URL
     * @param typeUrl URL generated by typeUrlFor()
     * @return Original class
     * @throws ClassNotFoundException if class isn't available
     */
    private fun classForTypeUrl(typeUrl: String): Class<*> {
        val className = typeUrl.substringAfterLast('/')
        return Class.forName(className)
    }

    /**
     * @interval
     * Retrieves or creates schema for a class
     * @param clazz Target class
     * @return Cached or newly created schema
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getSchema(clazz: Class<T>): Schema<T> {
        return schemaCache.getOrPut(clazz) {
            RuntimeSchema.createFrom(clazz)
        } as Schema<T>
    }
}