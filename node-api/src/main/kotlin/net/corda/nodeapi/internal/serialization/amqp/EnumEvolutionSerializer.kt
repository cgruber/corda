package net.corda.nodeapi.internal.serialization.amqp

import net.corda.core.internal.uncheckedCast
import org.apache.qpid.proton.amqp.Symbol
import org.apache.qpid.proton.codec.Data
import java.io.NotSerializableException
import java.lang.UnsupportedOperationException
import java.lang.reflect.Type

/**
 * @property clazz The enum as it exists now, not as it did when it was serialized (either in the past
 * or future).
 * @property factory the [SerializerFactory] that is building this serialization object.
 * @property conversions A mapping between all potential enum constants that could've been assigned to
 * an instance of the enum as it existed at serialisation and those that exist now
 * @property ordinals Convenience mapping of constant to ordinality
 */
class EnumEvolutionSerializer(
        clazz: Type,
        factory: SerializerFactory,
        private val conversions: Map<String, String>,
        private val ordinals: Map<String, Int>) : AMQPSerializer<Any> {
    override val type: Type = clazz
    override val typeDescriptor = Symbol.valueOf("$DESCRIPTOR_DOMAIN:${fingerprintForType(type, factory)}")!!

    companion object {
        private fun MutableMap<String, String>.mapInPlace(f: (String) -> String) {
            val i = iterator()
            while (i.hasNext()) {
                val curr = i.next()
                curr.setValue(f(curr.value))
            }
        }

        /**
         * Builds an Enum Evolver serializer.
         *
         * @param old The description of the enum as it existed at the time of serialisation taken from the
         * received AMQP header
         * @param new The Serializer object we built based on the current state of the enum class on our classpath
         * @param factory the [SerializerFactory] that is building this serialization object.
         * @param transformsFromBlob the transforms attached to the class in the AMQP header, i.e. the transforms
         * known at serialization time
         */
        fun make(old: RestrictedType,
                 new: AMQPSerializer<Any>,
                 factory: SerializerFactory,
                 transformsFromBlob: TransformsSchema): AMQPSerializer<Any> {

            val wireTransforms = transformsFromBlob.types[old.name]
            val localTransforms = TransformsSchema.get(old.name, factory)
            val transforms = if (wireTransforms?.size ?: -1 > localTransforms.size) wireTransforms!! else localTransforms

            // if either of these isn't of the cast type then something has gone terribly wrong
            // elsewhere in the code
            val defaultRules: List<EnumDefaultSchemaTransform>? = uncheckedCast(transforms[TransformTypes.EnumDefault])
            val renameRules: List<RenameSchemaTransform>? = uncheckedCast(transforms[TransformTypes.Rename])

            // What values exist on the enum as it exists on the class path
            val localValues = new.type.asClass()!!.enumConstants.map { it.toString() }

            val conversions: MutableMap<String, String> = localValues
                    .union(defaultRules?.map { it.new }?.toSet() ?: emptySet())
                    .union(renameRules?.map { it.to } ?: emptySet())
                    .associateBy({ it }, { it })
                    .toMutableMap()

            val rules: MutableMap<String, String> = mutableMapOf()
            rules.putAll(defaultRules?.associateBy({ it.new }, { it.old }) ?: emptyMap())
            rules.putAll(renameRules?.associateBy({ it.to }, { it.from }) ?: emptyMap())

            while (conversions.filter { it.value !in localValues }.isNotEmpty()) {
                conversions.mapInPlace { rules[it] ?: it }
            }

            // you'd think this was overkill to get access to the ordinal values for each constant but it's actually
            // rather tricky when you don't have access to the actual type, so this is a nice way to be able
            // to precompute and pass to the actual object
            var idx = 0
            return EnumEvolutionSerializer(new.type, factory, conversions, localValues.associateBy({ it }, { idx++ }))
        }
    }

    override fun readObject(obj: Any, schemas: SerializationSchemas, input: DeserializationInput): Any {
        val enumName = (obj as List<*>)[0] as String

        if (enumName !in conversions) {
            throw NotSerializableException("No rule to evolve enum constant $type::$enumName")
        }

        return type.asClass()!!.enumConstants[ordinals[conversions[enumName]]!!]
    }

    override fun writeClassInfo(output: SerializationOutput) {
        throw UnsupportedOperationException("It should be impossible to write an evolution serializer")
    }

    override fun writeObject(obj: Any, data: Data, type: Type, output: SerializationOutput) {
        throw UnsupportedOperationException("It should be impossible to write an evolution serializer")
    }
}