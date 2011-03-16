package net.ripe.commons.provisioning.message.resourceclassquery;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import net.ripe.commons.provisioning.message.PayloadMessageType;
import net.ripe.commons.provisioning.message.ProvisioningPayload;
import org.apache.commons.lang.builder.ToStringBuilder;

@XStreamAlias("message")
public class ListResponsePayload extends ProvisioningPayload {

    @XStreamAlias("class")
    private ListResponsePayloadClass payloadClass;

    public ListResponsePayload(String sender, String recipient, PayloadMessageType type, ListResponsePayloadClass payloadClass) {
        super(sender, recipient, type);
        this.payloadClass = payloadClass;
    }

    public ListResponsePayloadClass getPayloadClass() {
        return payloadClass;
    }

    public void setPayloadClass(ListResponsePayloadClass payloadClass) {
        this.payloadClass = payloadClass;
    }

    @Override
    public String toString() {
        return ToStringBuilder.reflectionToString(this);
    }

}