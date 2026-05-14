package com.oursprivacy.android.opmetrics;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Typed visitor profile passed to {@link OursPrivacyAPI#identify(OursPrivacyUserProperties)}
 * and the three-arg {@link OursPrivacyAPI#track(String, JSONObject, OursPrivacyUserProperties)}.
 *
 * Named fields are camelCase at the API surface and serialize to snake_case on the wire
 * (e.g. {@code externalId} → {@code external_id}). {@code customProperties} and {@code consent}
 * are free-form bags that pass through unchanged.
 */
public final class OursPrivacyUserProperties {

    private final String email;
    private final String externalId;
    private final String phoneNumber;
    private final String firstName;
    private final String lastName;
    private final String gender;
    private final String dateOfBirth;
    private final String city;
    private final String state;
    private final String zip;
    private final String country;
    private final String companyName;
    private final String jobTitle;
    private final String ip;
    private final JSONObject customProperties;
    private final JSONObject consent;

    private OursPrivacyUserProperties(Builder b) {
        this.email = b.email;
        this.externalId = b.externalId;
        this.phoneNumber = b.phoneNumber;
        this.firstName = b.firstName;
        this.lastName = b.lastName;
        this.gender = b.gender;
        this.dateOfBirth = b.dateOfBirth;
        this.city = b.city;
        this.state = b.state;
        this.zip = b.zip;
        this.country = b.country;
        this.companyName = b.companyName;
        this.jobTitle = b.jobTitle;
        this.ip = b.ip;
        this.customProperties = b.customProperties;
        this.consent = b.consent;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Snake-cased wire form. Top-level keys reflect every populated named field;
     * {@code custom_properties} and {@code consent} are included here whenever
     * the caller passed them. The store-level merge plus the empty-consent
     * omission rule are applied later, in {@link Track#mergeUserProperties}.
     */
    public JSONObject toWireProperties() {
        final JSONObject out = new JSONObject();
        try {
            putIfPresent(out, "email", email);
            putIfPresent(out, "external_id", externalId);
            putIfPresent(out, "phone_number", phoneNumber);
            putIfPresent(out, "first_name", firstName);
            putIfPresent(out, "last_name", lastName);
            putIfPresent(out, "gender", gender);
            putIfPresent(out, "date_of_birth", dateOfBirth);
            putIfPresent(out, "city", city);
            putIfPresent(out, "state", state);
            putIfPresent(out, "zip", zip);
            putIfPresent(out, "country", country);
            putIfPresent(out, "company_name", companyName);
            putIfPresent(out, "job_title", jobTitle);
            putIfPresent(out, "ip", ip);
            if (customProperties != null) {
                out.put("custom_properties", customProperties);
            }
            if (consent != null) {
                out.put("consent", consent);
            }
        } catch (JSONException e) {
            // JSONObject.put throws JSONException only for NaN/Inf doubles; we put strings + objects only.
        }
        return out;
    }

    private static void putIfPresent(JSONObject obj, String key, String value) throws JSONException {
        if (value != null) {
            obj.put(key, value);
        }
    }

    public static final class Builder {
        private String email;
        private String externalId;
        private String phoneNumber;
        private String firstName;
        private String lastName;
        private String gender;
        private String dateOfBirth;
        private String city;
        private String state;
        private String zip;
        private String country;
        private String companyName;
        private String jobTitle;
        private String ip;
        private JSONObject customProperties;
        private JSONObject consent;

        public Builder email(String v) { this.email = v; return this; }
        public Builder externalId(String v) { this.externalId = v; return this; }
        public Builder phoneNumber(String v) { this.phoneNumber = v; return this; }
        public Builder firstName(String v) { this.firstName = v; return this; }
        public Builder lastName(String v) { this.lastName = v; return this; }
        public Builder gender(String v) { this.gender = v; return this; }
        public Builder dateOfBirth(String v) { this.dateOfBirth = v; return this; }
        public Builder city(String v) { this.city = v; return this; }
        public Builder state(String v) { this.state = v; return this; }
        public Builder zip(String v) { this.zip = v; return this; }
        public Builder country(String v) { this.country = v; return this; }
        public Builder companyName(String v) { this.companyName = v; return this; }
        public Builder jobTitle(String v) { this.jobTitle = v; return this; }
        public Builder ip(String v) { this.ip = v; return this; }

        public Builder customProperties(JSONObject v) { this.customProperties = v; return this; }
        public Builder customProperties(Map<String, Object> v) {
            this.customProperties = v == null ? null : new JSONObject(v);
            return this;
        }

        public Builder consent(JSONObject v) { this.consent = v; return this; }
        public Builder consent(Map<String, Object> v) {
            this.consent = v == null ? null : new JSONObject(v);
            return this;
        }

        public OursPrivacyUserProperties build() {
            return new OursPrivacyUserProperties(this);
        }
    }
}
