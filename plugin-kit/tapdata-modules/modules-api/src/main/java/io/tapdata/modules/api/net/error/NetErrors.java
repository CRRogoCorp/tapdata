package io.tapdata.modules.api.net.error;

public interface NetErrors {
	int ERROR_ENCODER_NOT_FOUND = 8000;
	int JAVA_CUSTOM_DESERIALIZE_FAILED = 8001;
	int ENCODE_NOT_SUPPORTED = 8002;
	int RESURRECT_DATA_NULL = 8003;
	int ID_TYPE_NOT_FOUND = 8004;
	int GATEWAY_SESSION_HANDLER_CLASS_NEW_FAILED = 8005;
	int WEBSOCKET_SERVER_START_FAILED = 8006;
	int WEBSOCKET_LOGIN_FAILED = 8007;
	int WEBSOCKET_PROTOCOL_ILLEGAL = 8008;
	int WEBSOCKET_SSL_FAILED = 8009;
	int WEBSOCKET_URL_ILLEGAL = 8010;
	int WEBSOCKET_CONNECT_FAILED = 8011;
	int PERSISTENT_FAILED = 8012;
	int ILLEGAL_ENCODE = 8013;
	int CURRENT_NODE_ID_NOT_FOUND = 8014;
	int RESULT_IS_NULL = 8015;
}
