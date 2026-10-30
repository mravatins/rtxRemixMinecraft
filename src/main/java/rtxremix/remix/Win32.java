package rtxremix.remix;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/** The few kernel32/user32 calls the bridge needs. */
final class Win32 {
	private static final Linker LINKER = Linker.nativeLinker();
	private static final MethodHandle SET_DLL_DIRECTORY_W;
	private static final MethodHandle GET_CLIENT_RECT;
	private static final MethodHandle GET_ASYNC_KEY_STATE;
	private static final MethodHandle GET_FOREGROUND_WINDOW;

	static {
		SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
		SET_DLL_DIRECTORY_W = LINKER.downcallHandle(kernel32.find("SetDllDirectoryW").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS));
		GET_CLIENT_RECT = LINKER.downcallHandle(user32.find("GetClientRect").orElseThrow(),
				FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
		GET_ASYNC_KEY_STATE = LINKER.downcallHandle(user32.find("GetAsyncKeyState").orElseThrow(),
				FunctionDescriptor.of(JAVA_SHORT, JAVA_INT));
		GET_FOREGROUND_WINDOW = LINKER.downcallHandle(user32.find("GetForegroundWindow").orElseThrow(),
				FunctionDescriptor.of(ADDRESS));
	}

	private Win32() {
	}

	/** Lets Windows resolve the runtime's own dependencies (NRD.dll, nvngx_dlss.dll, usd, ...). */
	static void setDllDirectory(String dir) {
		try (Arena arena = Arena.ofConfined()) {
			int ignored = (int) SET_DLL_DIRECTORY_W.invokeExact(arena.allocateFrom(dir + '\0', StandardCharsets.UTF_16LE));
		} catch (Throwable t) {
			throw new RemixException("SetDllDirectoryW failed", t);
		}
	}

	/** Client-area size of {@code hwnd} as {width, height}. */
	static int[] clientSize(long hwnd) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment rect = arena.allocate(16, 4);
			int ok = (int) GET_CLIENT_RECT.invokeExact(MemorySegment.ofAddress(hwnd), rect);
			if (ok == 0) {
				return new int[] {0, 0};
			}
			return new int[] {rect.get(JAVA_INT, 8) - rect.get(JAVA_INT, 0), rect.get(JAVA_INT, 12) - rect.get(JAVA_INT, 4)};
		} catch (Throwable t) {
			return new int[] {0, 0};
		}
	}

	/** Whether a virtual key is held right now, independent of which window procedure gets its messages. */
	static boolean isKeyDown(int vk) {
		try {
			return ((short) GET_ASYNC_KEY_STATE.invokeExact(vk) & 0x8000) != 0;
		} catch (Throwable t) {
			return false;
		}
	}

	static boolean isForeground(long hwnd) {
		try {
			return ((MemorySegment) GET_FOREGROUND_WINDOW.invokeExact()).address() == hwnd;
		} catch (Throwable t) {
			return false;
		}
	}
}
