package rtxremix.remix;

/** remixapi_ErrorCode values. */
public final class ErrorCodes {
	public static final int SUCCESS = 0;
	public static final int GENERAL_FAILURE = 1;
	public static final int INVALID_ARGUMENTS = 3;

	private ErrorCodes() {
	}

	public static String describe(int code) {
		return switch (code) {
			case 0 -> "success";
			case 1 -> "general failure";
			case 2 -> "LoadLibrary failed";
			case 3 -> "invalid arguments";
			case 4 -> "GetProcAddress failed";
			case 5 -> "already exists";
			case 6 -> "registering a non-Remix D3D9 device";
			case 7 -> "D3D9 device was not registered";
			case 8 -> "incompatible API version (the mod targets the RTX Remix 1.6.1 runtime, API 0.6)";
			case 9 -> "SetDllDirectory failed";
			case 10 -> "GetFullPathName failed";
			case 11 -> "not initialized";
			case 0x88960001 -> "GPU lacks required ray tracing features";
			case 0x88960002 -> "NVIDIA driver is older than the runtime requires (616.92+)";
			case 0x88960003 -> "Vulkan instance extension missing";
			case 0x88960004 -> "vkCreateInstance failed";
			case 0x88960005 -> "vkCreateDevice failed";
			case 0x88960006 -> "no graphics queue family";
			default -> "error 0x" + Integer.toHexString(code);
		};
	}
}
