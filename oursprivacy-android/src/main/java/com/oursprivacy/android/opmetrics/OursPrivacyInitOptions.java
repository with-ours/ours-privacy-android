package com.oursprivacy.android.opmetrics;

import org.json.JSONObject;

import java.util.Map;

/**
 * Bootstrap options passed to {@link OursPrivacyAPI#initialize(OursPrivacyInitOptions)}.
 * All fields optional. Pass {@code null} to {@code initialize} to accept all defaults.
 */
public final class OursPrivacyInitOptions {

    private final Boolean trackAutomaticEvents;
    private final boolean trackAutomaticCrashes;
    private final String serverURL;
    private final String visitorId;
    private final String initialURL;
    private final JSONObject defaultEventProperties;
    private final JSONObject defaultUserCustomProperties;
    private final JSONObject defaultUserConsentProperties;
    private final Boolean optedOutByDefault;
    private final IngestRejectionListener ingestRejectionListener;

    private OursPrivacyInitOptions(Builder b) {
        this.trackAutomaticEvents = b.trackAutomaticEvents;
        this.trackAutomaticCrashes = b.trackAutomaticCrashes;
        this.serverURL = b.serverURL;
        this.visitorId = b.visitorId;
        this.initialURL = b.initialURL;
        this.defaultEventProperties = b.defaultEventProperties;
        this.defaultUserCustomProperties = b.defaultUserCustomProperties;
        this.defaultUserConsentProperties = b.defaultUserConsentProperties;
        this.optedOutByDefault = b.optedOutByDefault;
        this.ingestRejectionListener = b.ingestRejectionListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Boolean getTrackAutomaticEvents() { return trackAutomaticEvents; }
    public boolean getTrackAutomaticCrashes() { return trackAutomaticCrashes; }
    public String getServerURL() { return serverURL; }
    public String getVisitorId() { return visitorId; }
    public String getInitialURL() { return initialURL; }
    public JSONObject getDefaultEventProperties() { return defaultEventProperties; }
    public JSONObject getDefaultUserCustomProperties() { return defaultUserCustomProperties; }
    public JSONObject getDefaultUserConsentProperties() { return defaultUserConsentProperties; }
    public Boolean getOptedOutByDefault() { return optedOutByDefault; }
    public IngestRejectionListener getIngestRejectionListener() { return ingestRejectionListener; }

    public static final class Builder {
        private Boolean trackAutomaticEvents;
        private boolean trackAutomaticCrashes;
        private String serverURL;
        private String visitorId;
        private String initialURL;
        private JSONObject defaultEventProperties;
        private JSONObject defaultUserCustomProperties;
        private JSONObject defaultUserConsentProperties;
        private Boolean optedOutByDefault;
        private IngestRejectionListener ingestRejectionListener;

        public Builder trackAutomaticEvents(boolean v) { this.trackAutomaticEvents = v; return this; }
        public Builder trackAutomaticCrashes(boolean v) { this.trackAutomaticCrashes = v; return this; }
        public Builder serverURL(String v) { this.serverURL = v; return this; }
        public Builder visitorId(String v) { this.visitorId = v; return this; }
        public Builder initialURL(String v) { this.initialURL = v; return this; }

        public Builder defaultEventProperties(JSONObject v) { this.defaultEventProperties = v; return this; }
        public Builder defaultEventProperties(Map<String, Object> v) {
            this.defaultEventProperties = v == null ? null : new JSONObject(v);
            return this;
        }

        public Builder defaultUserCustomProperties(JSONObject v) { this.defaultUserCustomProperties = v; return this; }
        public Builder defaultUserCustomProperties(Map<String, Object> v) {
            this.defaultUserCustomProperties = v == null ? null : new JSONObject(v);
            return this;
        }

        public Builder defaultUserConsentProperties(JSONObject v) { this.defaultUserConsentProperties = v; return this; }
        public Builder defaultUserConsentProperties(Map<String, Object> v) {
            this.defaultUserConsentProperties = v == null ? null : new JSONObject(v);
            return this;
        }

        public Builder optedOutByDefault(boolean v) { this.optedOutByDefault = v; return this; }
        public Builder onIngestRejected(IngestRejectionListener v) {
            this.ingestRejectionListener = v;
            return this;
        }

        public OursPrivacyInitOptions build() {
            return new OursPrivacyInitOptions(this);
        }
    }
}
