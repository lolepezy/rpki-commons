package net.ripe.commons.provisioning.message.error;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamConverter;
import net.ripe.commons.provisioning.message.PayloadMessageType;
import net.ripe.commons.provisioning.message.AbstractProvisioningPayload;

@XStreamAlias("message")
public class RequestNotPerformedResponsePayload extends AbstractProvisioningPayload {

    @XStreamConverter(NotPerformedErrorConverter.class)
    private NotPerformedError status;

    private String description;

    protected RequestNotPerformedResponsePayload(String sender, String recipient, NotPerformedError status, String description) {
        super(sender, recipient, PayloadMessageType.error_response);
        this.status = status;
        this.description = description;
    }

    public NotPerformedError getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }
}