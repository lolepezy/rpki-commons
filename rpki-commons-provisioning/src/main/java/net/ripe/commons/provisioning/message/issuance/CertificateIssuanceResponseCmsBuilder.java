package net.ripe.commons.provisioning.message.issuance;

import net.ripe.commons.provisioning.message.PayloadMessageType;
import net.ripe.commons.provisioning.message.list.response.ResourceClassCmsBuilder;

public class CertificateIssuanceResponseCmsBuilder extends ResourceClassCmsBuilder {
    public CertificateIssuanceResponseCmsBuilder() {
        super(PayloadMessageType.issue_response);
    }
}
