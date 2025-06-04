package com.oursprivacy.android.util;

/**
 * OursPrivacy Constants
 */

public class OPConstants {
    public static class SessionReplay {
        public static final String REGISTER_ACTION = "com.oursprivacy.properties.register";
        public static final String UNREGISTER_ACTION = "com.oursprivacy.properties.unregister";
        public static final String REPLAY_ID_KEY = "$mp_replay_id";
    }
    public static class URL {
        public static final String OURSPRIVACY_API = "https://api.oursprivacy.com/api/v1";
        public static final String EVENT = "/track";
        public static final String IDENTIFY = "/identify";
    }
}
