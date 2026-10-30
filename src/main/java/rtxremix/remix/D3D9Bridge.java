package rtxremix.remix;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Drives the IDirect3DDevice9Ex that the Remix runtime creates (dxvk_CreateD3D9).
 *
 * <p>Remix path-traces the scene submitted through the Remix API and presents it through this device.
 * Minecraft's HUD and menus are composited as one screen-space quad (pre-transformed vertices), which
 * Remix treats as UI and draws over the path-traced image.
 */
public final class D3D9Bridge implements AutoCloseable {
	private int debugDraws;
	// TEMP experiment switch: contents of rtxremix-uiexp.txt in the working directory
	public static volatile int exp;
	private static int expPoll;

	public static void pollExp() {
		if (expPoll++ % 60 != 0) {
			return;
		}
		try {
			int v = Integer.parseInt(java.nio.file.Files.readString(java.nio.file.Path.of("rtxremix-uiexp.txt")).strip());
			if (v != exp) {
				org.slf4j.LoggerFactory.getLogger("rtxremix").info("[ui-exp] mode {}", v);
			}
			exp = v;
			if (v == 99) {
				net.minecraft.client.Minecraft.getInstance().stop(); // TEMP: lets a test run end itself
			}
		} catch (Exception e) {
			exp = 0;
		}
	}
	// IDirect3D9Ex vtable
	private static final int D3D9EX_CREATE_DEVICE_EX = 20;
	// IUnknown
	private static final int RELEASE = 2;
	// IDirect3DDevice9 vtable
	private static final int DEV_RESET = 16;
	private static final int DEV_PRESENT = 17;
	private static final int DEV_CREATE_TEXTURE = 23;
	private static final int DEV_CREATE_VERTEX_BUFFER = 26;
	private static final int DEV_BEGIN_SCENE = 41;
	private static final int DEV_END_SCENE = 42;
	private static final int DEV_CLEAR = 43;
	private static final int DEV_SET_TRANSFORM = 44;
	private static final int DEV_SET_RENDER_STATE = 57;
	private static final int DEV_SET_TEXTURE = 65;
	private static final int DEV_SET_TEXTURE_STAGE_STATE = 67;
	private static final int DEV_SET_SAMPLER_STATE = 69;
	private static final int DEV_DRAW_PRIMITIVE = 81;
	private static final int DEV_SET_FVF = 89;
	private static final int DEV_SET_STREAM_SOURCE = 100;
	// IDirect3DVertexBuffer9 vtable
	private static final int VB_LOCK = 11;
	private static final int VB_UNLOCK = 12;
	private static final int QUAD_STRIDE = 20;
	// IDirect3DTexture9 vtable
	private static final int TEX_LOCK_RECT = 19;
	private static final int TEX_UNLOCK_RECT = 20;

	private static final int D3DFMT_A8R8G8B8 = 21;
	private static final int D3DFMT_A8B8G8R8 = 32;
	private static final int D3DFMT_D24S8 = 75;
	private static final int D3DSWAPEFFECT_DISCARD = 1;
	private static final int D3DCREATE_FPU_PRESERVE = 0x2;
	private static final int D3DCREATE_MULTITHREADED = 0x4;
	private static final int D3DCREATE_HARDWARE_VERTEXPROCESSING = 0x40;
	private static final int D3DPRESENT_INTERVAL_ONE = 0x1;
	private static final int D3DPRESENT_INTERVAL_IMMEDIATE = 0x80000000;
	private static final int D3DUSAGE_DYNAMIC = 0x200;
	private static final int D3DPOOL_DEFAULT = 0;
	private static final int D3DPOOL_MANAGED = 1;
	private static final int D3DLOCK_DISCARD = 0x2000;
	private static final int D3DFVF_XYZ_TEX1 = 0x002 | 0x100;
	private static final int D3DTS_VIEW = 2;
	private static final int D3DTS_PROJECTION = 3;
	private static final int D3DTS_WORLD = 256;
	private static final int D3DPT_TRIANGLESTRIP = 5;

	private static final Linker LINKER = Linker.nativeLinker();

	private final RemixApi api;
	private final long hwnd;
	private MemorySegment d3d9;
	private MemorySegment device;
	private MemorySegment uiTexture = MemorySegment.NULL;
	private MemorySegment quadBuffer = MemorySegment.NULL;
	private int quadWidth;
	private int quadHeight;
	private int uiWidth;
	private int uiHeight;
	private int backBufferWidth;
	private int backBufferHeight;
	private boolean vsync;
	private boolean uiValid;

	public D3D9Bridge(RemixApi api, long hwnd, boolean vsync) {
		this.api = api;
		this.hwnd = hwnd;
		this.vsync = vsync;
		d3d9 = api.direct3DCreate9Ex();
		int[] size = Win32.clientSize(hwnd);
		backBufferWidth = Math.max(1, size[0]);
		backBufferHeight = Math.max(1, size[1]);
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment params = presentParameters(arena);
			MemorySegment out = arena.allocate(ADDRESS);
			int hr = (int) com(d3d9, D3D9EX_CREATE_DEVICE_EX,
					FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
					.invokeExact(d3d9, 0 /* default adapter */, 1 /* D3DDEVTYPE_HAL */, MemorySegment.ofAddress(hwnd),
							D3DCREATE_HARDWARE_VERTEXPROCESSING | D3DCREATE_MULTITHREADED | D3DCREATE_FPU_PRESERVE,
							params, MemorySegment.NULL, out);
			checkHr(hr, "IDirect3D9Ex::CreateDeviceEx");
			device = out.get(ADDRESS, 0);
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("IDirect3D9Ex::CreateDeviceEx failed", t);
		}
		api.registerD3D9Device(device);
	}

	private MemorySegment presentParameters(Arena arena) {
		// D3DPRESENT_PARAMETERS, 64 bytes on x64
		MemorySegment p = arena.allocate(64, 8);
		p.set(JAVA_INT, 0, backBufferWidth);
		p.set(JAVA_INT, 4, backBufferHeight);
		p.set(JAVA_INT, 8, D3DFMT_A8R8G8B8);
		p.set(JAVA_INT, 12, 1);                     // BackBufferCount
		p.set(JAVA_INT, 24, D3DSWAPEFFECT_DISCARD);
		p.set(ADDRESS, 32, MemorySegment.ofAddress(hwnd));
		p.set(JAVA_INT, 40, 1);                     // Windowed
		p.set(JAVA_INT, 44, 1);                     // EnableAutoDepthStencil
		p.set(JAVA_INT, 48, D3DFMT_D24S8);
		p.set(JAVA_INT, 60, vsync ? D3DPRESENT_INTERVAL_ONE : D3DPRESENT_INTERVAL_IMMEDIATE);
		return p;
	}

	/** Recreates the swap chain when the window size or vsync changes. Must run outside BeginScene/EndScene. */
	public void ensureBackBuffer(boolean wantVsync) {
		int[] size = Win32.clientSize(hwnd);
		int w = Math.max(1, size[0]);
		int h = Math.max(1, size[1]);
		if (w == backBufferWidth && h == backBufferHeight && wantVsync == vsync) {
			return;
		}
		backBufferWidth = w;
		backBufferHeight = h;
		vsync = wantVsync;
		releaseUiTexture(); // D3DPOOL_DEFAULT resources must be released before Reset
		try (Arena arena = Arena.ofConfined()) {
			int hr = (int) com(device, DEV_RESET, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS))
					.invokeExact(device, presentParameters(arena));
			checkHr(hr, "IDirect3DDevice9::Reset");
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("IDirect3DDevice9::Reset failed", t);
		}
	}

	public void beginScene() {
		call(device, DEV_BEGIN_SCENE);
		if (exp == 2) {
			return;
		}
		try {
			int hr = (int) com(device, DEV_CLEAR,
					FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_FLOAT, JAVA_INT))
					.invokeExact(device, 0, MemorySegment.NULL, 0x1 | 0x2 /* TARGET | ZBUFFER */, 0, 1f, 0);
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Uploads the HUD/menu layer. {@code rgba} holds tightly packed RGBA8 rows, bottom row first (as
	 * Minecraft's texture readback returns them), with color premultiplied by alpha.
	 */
	public void uploadUi(ByteBuffer rgba, int width, int height) {
		if (width <= 0 || height <= 0) {
			uiValid = false;
			return;
		}
		if (uiTexture.equals(MemorySegment.NULL) || width != uiWidth || height != uiHeight) {
			releaseUiTexture();
			try (Arena arena = Arena.ofConfined()) {
				MemorySegment out = arena.allocate(ADDRESS);
				int hr = (int) com(device, DEV_CREATE_TEXTURE,
						FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
						.invokeExact(device, width, height, 1, D3DUSAGE_DYNAMIC, D3DFMT_A8B8G8R8, D3DPOOL_DEFAULT, out, MemorySegment.NULL);
				checkHr(hr, "CreateTexture(UI)");
				uiTexture = out.get(ADDRESS, 0);
				uiWidth = width;
				uiHeight = height;
			} catch (RemixException e) {
				throw e;
			} catch (Throwable t) {
				throw new RemixException("CreateTexture(UI) failed", t);
			}
		}
		try (Arena arena = Arena.ofConfined()) {
			// D3DLOCKED_RECT { INT Pitch; void* pBits; }
			MemorySegment locked = arena.allocate(16, 8);
			int hr = (int) com(uiTexture, TEX_LOCK_RECT, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
					.invokeExact(uiTexture, 0, locked, MemorySegment.NULL, D3DLOCK_DISCARD);
			checkHr(hr, "LockRect(UI)");
			int pitch = locked.get(JAVA_INT, 0);
			MemorySegment bits = locked.get(ADDRESS, 8).reinterpret((long) pitch * height);
			MemorySegment src = MemorySegment.ofBuffer(rgba);
			long rowBytes = (long) width * 4;
			for (int y = 0; y < height; y++) {
				MemorySegment.copy(src, (long) (height - 1 - y) * rowBytes, bits, (long) y * pitch, rowBytes);
			}
			int ignored = (int) com(uiTexture, TEX_UNLOCK_RECT, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
					.invokeExact(uiTexture, 0);
			uiValid = true;
		} catch (RemixException e) {
			throw e;
		} catch (Throwable t) {
			throw new RemixException("UI upload failed", t);
		}
	}

	public void invalidateUi() {
		uiValid = false;
	}

	/** Draws the HUD/menu layer over the whole back buffer with premultiplied alpha blending. */
	public void drawUi() {
		if (!uiValid || uiTexture.equals(MemorySegment.NULL)) {
			return;
		}
		try (Arena arena = Arena.ofConfined()) {
			setRenderState(7, 0);    // D3DRS_ZENABLE
			setRenderState(14, 0);   // D3DRS_ZWRITEENABLE
			setRenderState(22, 1);   // D3DRS_CULLMODE = NONE
			setRenderState(137, 0);  // D3DRS_LIGHTING
			setRenderState(27, exp == 1 ? 0 : 1);   // D3DRS_ALPHABLENDENABLE
			setRenderState(19, 2);   // D3DRS_SRCBLEND = ONE: the layer is premultiplied
			setRenderState(20, 6);   // D3DRS_DESTBLEND = INVSRCALPHA
			setTextureStageState(0, 1, 2); // COLOROP = SELECTARG1
			setTextureStageState(0, 2, Boolean.getBoolean("rtxremix.debugUiSolid") ? 0 /* DIFFUSE */ : 2); // COLORARG1 = TEXTURE
			setTextureStageState(0, 4, 2); // ALPHAOP = SELECTARG1
			setTextureStageState(0, 5, 2); // ALPHAARG1 = TEXTURE
			setSamplerState(0, 5, 1); // MAGFILTER = POINT
			setSamplerState(0, 6, 1); // MINFILTER = POINT
			int hr = (int) com(device, DEV_SET_FVF, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT))
					.invokeExact(device, D3DFVF_XYZ_TEX1);
			// Identity transforms with Z writes off make this an orthographic draw, which Remix treats as UI
			// (rtx.orthographicIsUI): it composites the path-traced image right before it, so the HUD lands on
			// top. Pre-transformed vertices are not UI by default.
			MemorySegment identity = arena.allocateFrom(JAVA_FLOAT,
					1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1);
			for (int ts : new int[] {D3DTS_WORLD, D3DTS_VIEW, D3DTS_PROJECTION}) {
				hr = (int) com(device, DEV_SET_TRANSFORM, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
						.invokeExact(device, ts, identity);
			}

			ensureQuadBuffer();
			hr = (int) com(device, DEV_SET_STREAM_SOURCE, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
					.invokeExact(device, 0, quadBuffer, 0, QUAD_STRIDE);
			setTexture(uiTexture);
			hr = drawQuad();
			if (Boolean.getBoolean("rtxremix.debugUi") && debugDraws++ % 300 == 0) {
				org.slf4j.LoggerFactory.getLogger("rtxremix").info("[ui-debug] DrawPrimitive hr=0x{} backbuffer {}x{}",
						Integer.toHexString(hr), backBufferWidth, backBufferHeight);
			}
			hr = (int) com(device, DEV_SET_TEXTURE, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
					.invokeExact(device, 0, MemorySegment.NULL);
		} catch (Throwable t) {
			throw new RemixException("UI draw failed", t);
		}
	}

	/** (Re)fills the full-screen quad when the back buffer size changes. */
	private void ensureQuadBuffer() throws Throwable {
		try (Arena arena = Arena.ofConfined()) {
			if (quadBuffer.equals(MemorySegment.NULL)) {
				MemorySegment out = arena.allocate(ADDRESS);
				int hr = (int) com(device, DEV_CREATE_VERTEX_BUFFER,
						FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS))
						.invokeExact(device, 4 * QUAD_STRIDE, 0, D3DFVF_XYZ_TEX1, D3DPOOL_MANAGED, out, MemorySegment.NULL);
				checkHr(hr, "CreateVertexBuffer(UI)");
				quadBuffer = out.get(ADDRESS, 0);
				quadWidth = 0;
			}
			if (quadWidth == backBufferWidth && quadHeight == backBufferHeight) {
				return;
			}
			// Full-screen quad in clip space; the half-texel shift aligns texels with pixels (D3D9 rules).
			float hx = 1f / backBufferWidth;
			float hy = 1f / backBufferHeight;
			float[] verts = {
					-1 - hx, 1 + hy, 0.5f, 0f, 0f,
					1 - hx, 1 + hy, 0.5f, 1f, 0f,
					-1 - hx, -1 + hy, 0.5f, 0f, 1f,
					1 - hx, -1 + hy, 0.5f, 1f, 1f,
			};
			MemorySegment data = arena.allocate(ADDRESS);
			int hr = (int) com(quadBuffer, VB_LOCK, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
					.invokeExact(quadBuffer, 0, 0, data, 0);
			checkHr(hr, "Lock(UI vertex buffer)");
			MemorySegment.copy(verts, 0, data.get(ADDRESS, 0).reinterpret(4L * QUAD_STRIDE), JAVA_FLOAT, 0, verts.length);
			hr = (int) com(quadBuffer, VB_UNLOCK, FunctionDescriptor.of(JAVA_INT, ADDRESS)).invokeExact(quadBuffer);
			quadWidth = backBufferWidth;
			quadHeight = backBufferHeight;
		}
	}

	private void setTexture(MemorySegment texture) throws Throwable {
		int hr = (int) com(device, DEV_SET_TEXTURE, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))
				.invokeExact(device, 0, texture);
	}

	private int drawQuad() throws Throwable {
		return (int) com(device, DEV_DRAW_PRIMITIVE, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT))
				.invokeExact(device, D3DPT_TRIANGLESTRIP, 0, 2);
	}

	/** Ends the frame; Remix path-traces the submitted scene and presents it. */
	public void endSceneAndPresent() {
		call(device, DEV_END_SCENE);
		try {
			int hr = (int) com(device, DEV_PRESENT, FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
					.invokeExact(device, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
		} catch (Throwable t) {
			throw new RemixException("IDirect3DDevice9::Present failed", t);
		}
	}

	private void setRenderState(int state, int value) throws Throwable {
		int hr = (int) com(device, DEV_SET_RENDER_STATE, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT))
				.invokeExact(device, state, value);
	}

	private void setTextureStageState(int stage, int type, int value) throws Throwable {
		int hr = (int) com(device, DEV_SET_TEXTURE_STAGE_STATE, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT))
				.invokeExact(device, stage, type, value);
	}

	private void setSamplerState(int sampler, int type, int value) throws Throwable {
		int hr = (int) com(device, DEV_SET_SAMPLER_STATE, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT))
				.invokeExact(device, sampler, type, value);
	}

	public int width() {
		return backBufferWidth;
	}

	public int height() {
		return backBufferHeight;
	}

	private void releaseUiTexture() {
		if (!uiTexture.equals(MemorySegment.NULL)) {
			release(uiTexture);
			uiTexture = MemorySegment.NULL;
		}
		uiValid = false;
	}

	@Override
	public void close() {
		releaseUiTexture();
		if (!quadBuffer.equals(MemorySegment.NULL)) {
			release(quadBuffer);
			quadBuffer = MemorySegment.NULL;
		}
		if (device != null) {
			release(device);
			device = null;
		}
		if (d3d9 != null) {
			release(d3d9);
			d3d9 = null;
		}
	}

	// ---------------------------------------------------------------------------------------------
	// COM helpers

	private static MethodHandle com(MemorySegment obj, int index, FunctionDescriptor desc) {
		MemorySegment vtbl = obj.reinterpret(8).get(ADDRESS, 0).reinterpret(8L * 200);
		return LINKER.downcallHandle(vtbl.get(ADDRESS, 8L * index), desc);
	}

	private static void call(MemorySegment obj, int index) {
		try {
			int ignored = (int) com(obj, index, FunctionDescriptor.of(JAVA_INT, ADDRESS)).invokeExact(obj);
		} catch (Throwable t) {
			throw new RemixException("COM call #" + index + " failed", t);
		}
	}

	private static void release(MemorySegment obj) {
		try {
			int ignored = (int) com(obj, RELEASE, FunctionDescriptor.of(JAVA_INT, ADDRESS)).invokeExact(obj);
		} catch (Throwable ignored) {
		}
	}

	private static void checkHr(int hr, String what) {
		if (hr < 0) {
			throw new RemixException(what + " failed: HRESULT 0x" + Integer.toHexString(hr), hr);
		}
	}
}
