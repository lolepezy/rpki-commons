/**
 * The BSD License
 *
 * Copyright (c) 2010, 2011 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.commons.provisioning.cms;

import net.ripe.commons.certification.validation.ValidationLocation;
import net.ripe.commons.certification.validation.ValidationResult;
import net.ripe.commons.certification.x509cert.AbstractX509CertificateWrapperException;
import net.ripe.commons.certification.x509cert.X509CertificateUtil;
import net.ripe.commons.provisioning.payload.AbstractProvisioningPayload;
import net.ripe.commons.provisioning.payload.PayloadParser;
import net.ripe.commons.provisioning.x509.ProvisioningCmsCertificateParser;
import org.apache.commons.io.IOUtils;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Set;
import org.bouncycastle.asn1.cms.*;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.X509Extension;
import org.bouncycastle.cms.*;
import org.bouncycastle.operator.bc.BcDigestCalculatorProvider;
import org.bouncycastle.x509.extension.X509ExtensionUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import static net.ripe.commons.certification.validation.ValidationString.*;
import static net.ripe.commons.certification.x509cert.X509CertificateBuilderHelper.DEFAULT_SIGNATURE_PROVIDER;

public class ProvisioningCmsObjectParser {

    private static final BcDigestCalculatorProvider DIGEST_CALCULATOR_PROVIDER = new BcDigestCalculatorProvider();

    private static final ASN1ObjectIdentifier PROVISIONING_OBJECT_OID_STRING = new ASN1ObjectIdentifier("1.2.840.113549.1.9.16.1.28");
    private static final int CMS_OBJECT_SIGNER_VERSION = 3;
    private static final int CMS_OBJECT_VERSION = 3;

    private byte[] encoded;

    private X509Certificate cmsCertificate;

    private Collection<X509Certificate> caCertificates = new HashSet<X509Certificate>();

    private X509CRL crl;

    private CMSSignedDataParser sp;

    private ValidationResult validationResult;

    private String location;
    private AbstractProvisioningPayload payload;


    public ProvisioningCmsObjectParser() {
        this(new ValidationResult());
    }

    public ProvisioningCmsObjectParser(ValidationResult validationResult) {
        this.validationResult = validationResult;
    }

    public ValidationResult getValidationResult() {
        return validationResult;
    }

    public void parseCms(String location, byte[] encoded) { //NOPMD - ArrayIsStoredDirectly
        this.location = location;
        this.encoded = encoded;
        validationResult.setLocation(new ValidationLocation(location));

        try {
            sp = new CMSSignedDataParser(DIGEST_CALCULATOR_PROVIDER, encoded);
        } catch (CMSException e) {
            validationResult.rejectIfFalse(false, CMS_DATA_PARSING);
            return;
        }
        validationResult.rejectIfFalse(true, CMS_DATA_PARSING);

        verifyVersionNumber();
        verifyDigestAlgorithm(encoded);
        verifyContentType();
        parseContent();

        parseCertificates();
        parseCmsCrl();
        verifySignerInfos();
    }

    public ProvisioningCmsObject getProvisioningCmsObject() {
        if (validationResult.hasFailures()) {
            throw new ProvisioningCmsObjectParserException("provisioning cms object validation failed: " + validationResult.getFailuresForCurrentLocation());
        }
        return new ProvisioningCmsObject(encoded, cmsCertificate, caCertificates, crl, payload);
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.1
     */
    private void verifyVersionNumber() {
        validationResult.rejectIfFalse(sp.getVersion() == CMS_OBJECT_VERSION, CMS_SIGNED_DATA_VERSION);
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.2
     */
    private void verifyDigestAlgorithm(byte[] data) {
        validationResult.rejectIfFalse(CMSSignedGenerator.DIGEST_SHA256.equals(getDigestAlgorithmOidFromEncodedCmsObject(data).getAlgorithm().getId()), CMS_SIGNED_DATA_DIGEST_ALGORITHM);
    }

    private AlgorithmIdentifier getDigestAlgorithmOidFromEncodedCmsObject(byte[] data) {
        ASN1InputStream in = new ASN1InputStream(new ByteArrayInputStream(data));
        ContentInfo info;
        try {
            info = ContentInfo.getInstance(in.readObject());
        } catch (IOException e) {
            throw new ProvisioningCmsObjectParserException("error while reading cms object content info", e);
        }
        SignedData signedData = SignedData.getInstance(info.getContent());
        ASN1Set digestAlgorithms = signedData.getDigestAlgorithms();
        ASN1Encodable object = digestAlgorithms.getObjectAt(0);
        return AlgorithmIdentifier.getInstance(object.toASN1Primitive());
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.3.1
     */
    private void verifyContentType() {
        validationResult.rejectIfFalse(PROVISIONING_OBJECT_OID_STRING.equals(sp.getSignedContent().getContentType()), CMS_CONTENT_TYPE);
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.3.2
     */
    private void parseContent() {
        try {
            CMSTypedStream signedContent = sp.getSignedContent();
            InputStream signedContentStream = signedContent.getContentStream();

            String payloadXml = IOUtils.toString(signedContentStream, "UTF-8");
            payload = PayloadParser.parse(payloadXml, validationResult);

            validationResult.rejectIfFalse(true, CMS_CONTENT_PARSING);
        } catch (IOException e) {
            validationResult.rejectIfFalse(false, CMS_CONTENT_PARSING);
        }
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.4
     */
    private void parseCertificates() {
        Collection<? extends Certificate> certificates = extractCertificates(sp);
        if (!validationResult.rejectIfNull(certificates, GET_CERTS_AND_CRLS)) {
            return;
        }

        for (Certificate cert : certificates) {
            if (!validationResult.rejectIfFalse(cert instanceof X509Certificate, CERT_IS_X509CERT)) {
                continue;
            }
            processX509Certificate((X509Certificate)cert);
        }
    }

    private void processX509Certificate(X509Certificate certificate) {
        if (isEndEntityCertificate(certificate)) {
            if (cmsCertificate == null) {
                cmsCertificate = parseCmsCertificate(certificate);
                validationResult.rejectIfFalse(true, CERT_IS_EE_CERT);
                validationResult.rejectIfNull(X509CertificateUtil.getSubjectKeyIdentifier(cmsCertificate) != null, CERT_HAS_SKI);
            } else {
                validationResult.rejectIfFalse(false, ONLY_ONE_EE_CERT_ALLOWED);
            }
        } else {
            caCertificates.add(certificate);
        }
    }

    private <T> X509Certificate parseCmsCertificate(X509Certificate certificate) {
        ProvisioningCmsCertificateParser parser = new ProvisioningCmsCertificateParser();
        try {
            parser.parse(new ValidationLocation(location), certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new AbstractX509CertificateWrapperException(e);
        }
        return parser.getCertificate().getCertificate();
    }

    private boolean isEndEntityCertificate(X509Certificate certificate) {
        try {
            byte[] basicConstraintsExtension = certificate.getExtensionValue(X509Extension.basicConstraints.getId());
            if (basicConstraintsExtension == null) {
                /**
                 * If the basic constraints extension is not present [...] then the certified public key MUST NOT be used
                 * to verify certificate signatures.
                 *  http://tools.ietf.org/html/rfc5280#section-4.2.1.9
                 */
                return true;
            }
            BasicConstraints constraints = BasicConstraints.getInstance(X509ExtensionUtil.fromExtensionValue(basicConstraintsExtension));
            return ! constraints.isCA();
        } catch (IOException e) {
            throw new ProvisioningCmsObjectParserException("error while reading cms object certificate", e);
        }
    }

    private Collection<? extends Certificate> extractCertificates(CMSSignedDataParser sp) {
        Collection<? extends Certificate> certificates;
        try {
            CertStore certs;
            certs = sp.getCertificatesAndCRLs("Collection", (String) null);
            certificates = certs.getCertificates(null);
        } catch (NoSuchAlgorithmException e) {
            certificates = null;
        } catch (NoSuchProviderException e) {
            certificates = null;
        } catch (CMSException e) {
            certificates = null;
        } catch (CertStoreException e) {
            certificates = null;
        }
        return certificates;
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.5
     */
    private void parseCmsCrl() {
        Collection<? extends CRL> certificates = extractCrl(sp);
        if (!validationResult.rejectIfNull(certificates, GET_CERTS_AND_CRLS)) {
            return;
        }

        if (!validationResult.rejectIfFalse(certificates.size() == 1, ONLY_ONE_CRL_ALLOWED)) {
            return;
        }

        CRL x509Crl = certificates.iterator().next();
        if (validationResult.rejectIfFalse(x509Crl instanceof X509CRL, CRL_IS_X509CRL)) {
            crl = (X509CRL) x509Crl;
        }
    }

    private Collection<? extends CRL> extractCrl(CMSSignedDataParser sp) {
        Collection<? extends CRL> certificates;
        try {
            CertStore certs;
            certs = sp.getCertificatesAndCRLs("Collection", (String) null);
            certificates = certs.getCRLs(null);
        } catch (NoSuchAlgorithmException e) {
            certificates = null;
        } catch (NoSuchProviderException e) {
            certificates = null;
        } catch (CMSException e) {
            certificates = null;
        } catch (CertStoreException e) {
            certificates = null;
        }
        return certificates;
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6
     */
    private void verifySignerInfos() {
        SignerInformationStore signerStore = getSignerStore();
        if (!validationResult.rejectIfNull(signerStore, GET_SIGNER_INFO)) {
            return;
        }

        Collection<?> signers = signerStore.getSigners();
        validationResult.rejectIfFalse(signers.size() == 1, ONLY_ONE_SIGNER);

        SignerInformation signer =  (SignerInformation) signers.iterator().next();
        verifySignerVersion(signer);
        verifySubjectKeyIdentifier(signer);
        verifyDigestAlgorithm(signer);
        verifySignedAttributes(signer);
        verifyEncryptionAlgorithm(signer);
        verifySignature(signer);
        verifyUnsignedAttributes(signer);
    }

    private SignerInformationStore getSignerStore() {
        SignerInformationStore signerStore;
        try {
            signerStore = sp.getSignerInfos();
        } catch (CMSException e) {
            signerStore = null;
        }
        return signerStore;
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.1
     */
    private void verifySignerVersion(SignerInformation signer) {
        validationResult.rejectIfFalse(signer.getVersion() == CMS_OBJECT_SIGNER_VERSION, CMS_SIGNER_INFO_VERSION);
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.2
     */
    private void verifySubjectKeyIdentifier(SignerInformation signer) {
        SignerId sid = signer.getSID();
        validationResult.rejectIfFalse(Arrays.equals(X509CertificateUtil.getSubjectKeyIdentifier(cmsCertificate), sid.getSubjectKeyIdentifier()), CMS_SIGNER_INFO_SKI);
        validationResult.rejectIfFalse(sid.getIssuer() == null && sid.getSerialNumber() == null, CMS_SIGNER_INFO_SKI_ONLY);
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.3
     */
    private void verifyDigestAlgorithm(SignerInformation signer) {
        validationResult.rejectIfFalse(CMSSignedGenerator.DIGEST_SHA256.equals(signer.getDigestAlgOID()), CMS_SIGNER_INFO_DIGEST_ALGORITHM);
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.4
     */
    private void verifySignedAttributes(SignerInformation signer) {
        AttributeTable attributeTable = signer.getSignedAttributes();
        if (!validationResult.rejectIfNull(attributeTable, SIGNED_ATTRS_PRESENT)) {
            return;
        }

        verifyContentType(attributeTable);
        verifyMessageDigest(attributeTable);
        verifySigningTime(attributeTable);
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.4.1
     */
    private void verifyContentType(AttributeTable attributeTable) {
        Attribute contentType = attributeTable.get(CMSAttributes.contentType);
        if (!validationResult.rejectIfNull(contentType, CONTENT_TYPE_ATTR_PRESENT)) {
            return;
        }
        if(!validationResult.rejectIfFalse(contentType.getAttrValues().size() == 1, CONTENT_TYPE_VALUE_COUNT)) {
            return;
        }
        validationResult.rejectIfFalse(PROVISIONING_OBJECT_OID_STRING.equals(contentType.getAttrValues().getObjectAt(0)), CONTENT_TYPE_VALUE);
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.4.2
     */
    private void verifyMessageDigest(AttributeTable attributeTable) {
        Attribute messageDigest = attributeTable.get(CMSAttributes.messageDigest);
        if (!validationResult.rejectIfNull(messageDigest, MSG_DIGEST_ATTR_PRESENT)) {
            return;
        }
        if (!validationResult.rejectIfFalse(messageDigest.getAttrValues().size() == 1, MSG_DIGEST_VALUE_COUNT)) {
            return;
        }
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.4.3
     */
    private void verifySigningTime(AttributeTable attributeTable) {
        Attribute signingTime = attributeTable.get(CMSAttributes.signingTime);
        if (!validationResult.rejectIfNull(signingTime, SIGNING_TIME_ATTR_PRESENT)) {
            return;
        }
        if (!validationResult.rejectIfFalse(signingTime.getAttrValues().size() == 1, ONLY_ONE_SIGNING_TIME_ATTR)) {
            return;
        }
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.5
     * http://tools.ietf.org/html/draft-huston-sidr-rpki-algs-00#section-2
     */
    private void verifyEncryptionAlgorithm(SignerInformation signer) {
        validationResult.rejectIfFalse(CMSSignedGenerator.ENCRYPTION_RSA.equals(signer.getEncryptionAlgOID()), ENCRYPTION_ALGORITHM);
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.6
     */
    private void verifySignature(SignerInformation signer) {
        boolean errorOccured = false;
        try {
            validationResult.rejectIfFalse(signer.verify(cmsCertificate, DEFAULT_SIGNATURE_PROVIDER), SIGNATURE_VERIFICATION);
        } catch (CertificateExpiredException e) {
            errorOccured = true;
        } catch (CertificateNotYetValidException e) {
            errorOccured = true;
        } catch (NoSuchAlgorithmException e) {
            errorOccured = true;
        } catch (NoSuchProviderException e) {
            errorOccured = true;
        } catch (CMSException e) {
            errorOccured = true;
        }

        if (errorOccured) {
            validationResult.rejectIfFalse(false, SIGNATURE_VERIFICATION);
        }
    }

    /**
     * http://tools.ietf.org/html/draft-ietf-sidr-rescerts-provisioning-09#section-3.1.1.6.7
     */
    private void verifyUnsignedAttributes(SignerInformation signer) {
        validationResult.rejectIfFalse(signer.getUnsignedAttributes() == null, UNSIGNED_ATTRS_OMITTED);
    }
}
