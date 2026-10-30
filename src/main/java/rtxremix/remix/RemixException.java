package rtxremix.remix;

public final class RemixException extends RuntimeException {
	private final int code;

	public RemixException(String message) {
		this(message, ErrorCodes.GENERAL_FAILURE);
	}

	public RemixException(String message, int code) {
		super(message);
		this.code = code;
	}

	public RemixException(String message, Throwable cause) {
		super(message, cause);
		this.code = ErrorCodes.GENERAL_FAILURE;
	}

	public int code() {
		return code;
	}
}
