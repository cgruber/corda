package net.corda.nodeapi.internal.serialization.amqp.custom

import net.corda.core.contracts.Attachment
import net.corda.core.contracts.ContractAttachment
import net.corda.core.contracts.ContractClassName
import net.corda.core.serialization.MissingAttachmentsException
import net.corda.nodeapi.internal.serialization.GeneratedAttachment
import net.corda.nodeapi.internal.serialization.amqp.CustomSerializer
import net.corda.nodeapi.internal.serialization.amqp.SerializerFactory

/**
 * A serializer for [ContractAttachment] that uses a proxy object to write out the full attachment eagerly.
 * @param factory the serializerFactory
 */
class ContractAttachmentSerializer(factory: SerializerFactory) : CustomSerializer.Proxy<ContractAttachment,
        ContractAttachmentSerializer.ContractAttachmentProxy>(ContractAttachment::class.java,
        ContractAttachmentProxy::class.java, factory) {
    override fun toProxy(obj: ContractAttachment): ContractAttachmentProxy {
        val bytes = try {
            obj.attachment.open().readBytes()
        } catch (e: Exception) {
            throw MissingAttachmentsException(listOf(obj.id))
        }
        return ContractAttachmentProxy(GeneratedAttachment(bytes), obj.contract)
    }

    override fun fromProxy(proxy: ContractAttachmentProxy): ContractAttachment {
        return ContractAttachment(proxy.attachment, proxy.contract)
    }

    data class ContractAttachmentProxy(val attachment: Attachment, val contract: ContractClassName)
}