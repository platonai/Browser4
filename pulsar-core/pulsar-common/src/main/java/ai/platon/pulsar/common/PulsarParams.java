package ai.platon.pulsar.common;

import ai.platon.pulsar.common.config.Params;

import java.util.Map;

/**
 * Created by Vincent on 16-9-24.
 *
 * @author vincent
 * @version $Id: $Id
 */
public class PulsarParams extends Params {

    public static final String VAR_FETCH_STATE = "fetch_state";
    public static final String VAR_PREV_FETCH_TIME_BEFORE_UPDATE = "prev_fetch_time_before_update";
    public static final String VAR_PRIVACY_CONTEXT_DISPLAY = "privacy_context_name";
    /**
     * Additional load status to report by PageLoadStatusFormatter.
     * */
    public static final String VAR_ADD_LOAD_STATUS = "additional_load_status";

    public static final String VAR_LOAD_OPTIONS = "LOAD_OPTIONS";

    /**
     * <p>Constructor for PulsarParams.</p>
     */
    public PulsarParams() {
    }

    /**
     * <p>Constructor for PulsarParams.</p>
     *
     * @param key a {@link java.lang.String} object.
     * @param value a {@link java.lang.Object} object.
     * @param others a {@link java.lang.Object} object.
     */
    public PulsarParams(String key, Object value, Object... others) {
        super(key, value, others);
    }

    /**
     * <p>Constructor for PulsarParams.</p>
     *
     * @param args a {@link java.util.Map} object.
     */
    public PulsarParams(Map<String, Object> args) {
        super(args);
    }
}
