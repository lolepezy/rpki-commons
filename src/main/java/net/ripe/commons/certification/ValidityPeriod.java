package net.ripe.commons.certification;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Embeddable;

import net.ripe.commons.certification.util.EqualsSupport;

import org.hibernate.validator.AssertTrue;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.Instant;
import org.joda.time.ReadableInstant;

/**
 * Validity period used by the certification system. Since certificates only
 * have up-to second accuracy with validity fields, this class truncates not
 * before and not after to second accuracy.
 */
@Embeddable
public class ValidityPeriod extends EqualsSupport implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "validity_not_before", nullable = true)
    private Timestamp notValidBefore;

    @Column(name = "validity_not_after", nullable = true)
    private Timestamp notValidAfter;

    public ValidityPeriod() {
        this.notValidBefore = null;
        this.notValidAfter = null;
    }

    public ValidityPeriod(ReadableInstant notValidBefore, ReadableInstant notValidAfter) {
        setNotValidBefore(notValidBefore);
        setNotValidAfter(notValidAfter);
    }

    public ValidityPeriod(Date notValidBefore, Date notValidAfter) {
        setNotValidBefore(notValidBefore == null ? null : new DateTime(notValidBefore.getTime(), DateTimeZone.UTC));
        setNotValidAfter(notValidAfter == null ? null : new DateTime(notValidAfter.getTime(), DateTimeZone.UTC));
    }

    private void setNotValidBefore(ReadableInstant notValidBefore) {
        this.notValidBefore = notValidBefore == null ? null : new Timestamp(truncatedMillis(notValidBefore));
    }

    private void setNotValidAfter(ReadableInstant notValidAfter) {
        this.notValidAfter = notValidAfter == null ? null : new Timestamp(truncatedMillis(notValidAfter));
    }

    /**
     * Match resolution of certificate validity period (seconds)
     */
    private long truncatedMillis(ReadableInstant notValidBefore) {
        // CHECKSTYLE:OFF – 1000 is not a "magic number" in this case
        return notValidBefore.getMillis() / 1000 * 1000;
        // CHECKSTYLE:ON
    }

    public DateTime getNotValidAfter() {
        return notValidAfter == null ? null : new DateTime(notValidAfter.getTime(), DateTimeZone.UTC);
    }

    public DateTime getNotValidBefore() {
        return notValidBefore == null ? null : new DateTime(notValidBefore.getTime(), DateTimeZone.UTC);
    }

    public ValidityPeriod withNotValidBefore(ReadableInstant notValidBefore) {
        return new ValidityPeriod(notValidBefore, getNotValidAfter());
    }

    public ValidityPeriod withNotValidAfter(ReadableInstant notValidAfter) {
        return new ValidityPeriod(getNotValidBefore(), notValidAfter);
    }

    public boolean contains(ValidityPeriod other) {
        return isValidAt(other.getNotValidBefore()) && isValidAt(other.getNotValidAfter());
    }

    public boolean isExpiredNow() {
        return isExpiredAt(new Instant());
    }

    public boolean isExpiredAt(ReadableInstant instant) {
        return notValidAfter != null && instant.isAfter(getNotValidAfter());
    }

    public boolean isValidNow() {
        return isValidAt(new Instant());
    }

    public boolean isValidAt(ReadableInstant instant) {
        if (instant == null) {
            return !isClosed();
        } else {
            return (notValidBefore == null || !instant.isBefore(getNotValidBefore()))
                    && (notValidAfter == null || !instant.isAfter(getNotValidAfter()));
        }
    }

    /**
     * @return true if this instances notValidBefore and notValidAfter are both
     *         specified.
     */
    public boolean isClosed() {
        return notValidBefore != null && notValidAfter != null;
    }

    /**
     * Calculates the intersection of two validity periods, taking into account
     * open-ended validity periods.
     *
     * @param other
     *            the validity period to intersect with.
     * @return the intersection of this and the other validity period, or null
     *         if there is no overlap.
     */
    public ValidityPeriod intersect(ValidityPeriod other) {
        ValidityPeriod result = this;
        if (getNotValidBefore() == null || (other.getNotValidBefore() != null && getNotValidBefore().isBefore(other.getNotValidBefore()))) {
            result = result.withNotValidBefore(other.getNotValidBefore());
        }
        if (getNotValidAfter() == null || (other.getNotValidAfter() != null && getNotValidAfter().isAfter(other.getNotValidAfter()))) {
            result = result.withNotValidAfter(other.getNotValidAfter());
        }
        return result.isValid() ? result : null;
    }

    @AssertTrue
    public boolean isValid() {
        return notValidBefore == null || notValidAfter == null || notValidBefore.compareTo(notValidAfter) <= 0;
    }

    @Override
    public String toString() {
        return notValidBefore + " - " + notValidAfter;
    }
}