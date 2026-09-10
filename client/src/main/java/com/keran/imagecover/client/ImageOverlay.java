package com.keran.imagecover.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全屏图片覆盖层：按顺序播放一组图片，每张显示指定时长（淡入/淡出时长由服务端下发，0 表示关闭），
 * 全部播完自动消失；收到 stop 立刻清空并隐藏。
 *
 * <p>渲染入口是 {@link #renderTopmost(GuiGraphics)}，由 {@code GuiMixin} 注入
 * {@code Gui.render()} 末尾调用，保证图片画在聊天框、计分板等所有 HUD 之上。</p>
 *
 * 线程模型：
 *  - 下载/解码在后台线程（download + NativeImage 解码）
 *  - 纹理注册、播放状态、渲染都在主线程（Minecraft.execute）
 *  - generation 代次号用于丢弃 stop/新指令之后才回来的过期结果，避免纹理泄漏
 */
public final class ImageOverlay implements HudRenderCallback {
	public static final ImageOverlay INSTANCE = new ImageOverlay();

	private static final Logger LOGGER = LoggerFactory.getLogger("imagecover");
	/** 当前播放的淡入/淡出时长（毫秒），0 表示关闭淡入淡出 */
	private long fadeMs = 300L;
	/**
	 * 是否在图片下方铺一层全屏纯色遮罩（默认关闭）。
	 *
	 * <p>注意：这层遮罩曾经是"淡入淡出看起来没效果"的元凶——图片淡出时透出的是
	 * 一层同样在淡出的、与图片几乎同色的黑底，肉眼只会觉得整体稍微变暗，
	 * 直到最后一帧才"啪"地消失。透明背景的图片之所以能看到淡入淡出，
	 * 是因为它的透明区域直接透出了游戏画面。默认关闭后，图片的 alpha
	 * 才会真正作用在游戏画面上，淡入淡出清晰可见。</p>
	 */
	private boolean bgEnabled = false;
	/** 背景遮罩的 alpha（0.0~1.0），仅在 {@link #bgEnabled} 为 true 时生效 */
	private float bgAlpha = 1.0f;
	private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
			+ "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

	private static final int ST_PENDING = 0;
	private static final int ST_READY = 1;
	private static final int ST_FAILED = 2;

	/** 一张图片：链接 + 时长 + 加载状态 + 纹理 */
	public static final class Entry {
		final int slot;
		final String url;
		final long durationMs;
		volatile int state = ST_PENDING;
		volatile boolean submitted = false;
		volatile ResourceLocation texture = null;
		volatile int texW = 0;
		volatile int texH = 0;

		public Entry(int slot, String url, long durationMs) {
			this.slot = slot;
			this.url = url;
			this.durationMs = Math.max(1L, durationMs);
		}
	}

	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "imagecover-download");
		t.setDaemon(true);
		return t;
	});

	// ---- 以下字段只在主线程读写 ----
	private List<Entry> entries = null;
	private int index = 0;
	private long startMs = 0L;
	private boolean active = false;
	private long generation = 0L;

	private ImageOverlay() {}

	// --------------------------------------------------------------- 控制

	/** 开始播放一组图片（主线程） */
	public void play(List<Entry> list, long fadeMs) {
		play(list, fadeMs, false, 1.0f);
	}

	/**
	 * 开始播放一组图片（主线程）。
	 *
	 * @param fadeMs     淡入/淡出时长（毫秒），0 表示关闭
	 * @param bgEnabled  是否铺全屏背景遮罩（旧客户端行为为 true；默认 false）
	 * @param bgAlpha    背景遮罩不透明度 0.0~1.0
	 */
	public void play(List<Entry> list, long fadeMs, boolean bgEnabled, float bgAlpha) {
		this.fadeMs = Math.max(0L, fadeMs);
		this.bgEnabled = bgEnabled;
		this.bgAlpha = Math.max(0f, Math.min(1f, bgAlpha));
		generation++;
		releaseAll();
		this.entries = list;
		this.index = 0;
		this.startMs = 0L;
		this.active = list != null && !list.isEmpty();
		if (!active) {
			this.entries = null;
			return;
		}
		// 预加载第 1 张（立即）与第 2 张（预加载）
		preload(0);
		preload(1);
	}

	/** 立即清空并隐藏（主线程） */
	public void stop() {
		generation++;
		releaseAll();
		this.entries = null;
		this.index = 0;
		this.startMs = 0L;
		this.active = false;
	}

	// --------------------------------------------------------------- 渲染

	/**
	 * @deprecated 保留 Fabric 的 HUD 回调仅为兼容，
	 * 实际渲染走 {@link #renderTopmost(GuiGraphics)}（由 GuiMixin 在所有 HUD 之后调用）。
	 *
	 * <p>这里刻意不做任何绘制：HudRenderCallback 的时机早于聊天框，
	 * 在这里画会导致图片被聊天框遮挡。</p>
	 */
	@Override
	@Deprecated
	public void onHudRender(GuiGraphics g, float tickDelta) {
		// 空实现，见上面的说明
	}

	/**
	 * 在所有 HUD 元素（含聊天框、计分板、Tab 列表）之后渲染。
	 *
	 * <p>由 {@code GuiMixin} 注入 {@code Gui.render()} 的 TAIL 调用，
	 * 因此这里的绘制一定盖在最上层。</p>
	 *
	 * <p>注意：本方法只在主线程被调用，因此可以安全地读写播放状态。</p>
	 */
	public void renderTopmost(GuiGraphics g) {
		if (!active || entries == null || entries.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		if (mc.options.hideGui) return;
		// 打开任何界面（背包、菜单等）时不绘制，避免盖住 UI 导致玩家无法操作
		if (mc.screen != null) return;

		Entry cur = entries.get(index);
		if (startMs == 0L) {
			// 等待当前图片就绪后才开始计时（保证淡入可见）
			if (cur.state == ST_READY) {
				startMs = System.currentTimeMillis();
			} else if (cur.state == ST_FAILED) {
				advance();
				return;
			} else {
				return;
			}
		}

		long elapsed = System.currentTimeMillis() - startMs;
		if (elapsed >= cur.durationMs) {
			advance();
			return;
		}

		float alpha = computeAlpha(elapsed, cur.durationMs);
		int w = mc.getWindow().getGuiScaledWidth();
		int h = mc.getWindow().getGuiScaledHeight();

		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();

		// 背景遮罩（默认关闭）。
		// 早期版本无条件铺一层全屏黑底，导致不透明图片淡出时透出的是"同样在淡出的黑底"，
		// 视觉上几乎没有变化；关闭后图片的 alpha 才真正作用于游戏画面。
		if (bgEnabled && bgAlpha > 0f) {
			// 背景比图片先到达满不透明：淡入时更柔和，淡出时更早让位给游戏画面
			float bgA = Math.min(1f, alpha * 1.25f) * bgAlpha;
			RenderSystem.setShaderColor(0f, 0f, 0f, bgA);
			g.fill(0, 0, w, h, 0xFFFFFFFF);
		}

		// 图片：保持比例、铺满屏幕（cover）、居中、超出裁掉
		ResourceLocation tex = cur.texture;
		if (tex != null && cur.texW > 0 && cur.texH > 0) {
			float scale = Math.max(w / (float) cur.texW, h / (float) cur.texH);
			int dw = Math.max(1, Math.round(cur.texW * scale));
			int dh = Math.max(1, Math.round(cur.texH * scale));
			int dx = (w - dw) / 2;
			int dy = (h - dh) / 2;
			// blit 走 position_tex shader，顶点格式不含颜色，透明度完全由 ColorModulator
			// （即 setShaderColor 的 alpha）决定，且只在 alpha==0 时才 discard，
			// 因此这里传入的 alpha 会得到真正平滑的淡入淡出。
			RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
			g.enableScissor(0, 0, w, h);
			g.blit(tex, dx, dy, dw, dh, 0f, 0f, cur.texW, cur.texH, cur.texW, cur.texH);
			g.disableScissor();
		}

		// 恢复渲染状态
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		RenderSystem.disableBlend();
	}

	/** 进入下一张（跳过加载失败的）；没有下一张则结束（主线程） */
	private void advance() {
		if (entries == null) {
			active = false;
			return;
		}
		int next = index + 1;
		while (next < entries.size() && entries.get(next).state == ST_FAILED) {
			next++;
		}
		if (next >= entries.size()) {
			finish();
			return;
		}
		index = next;
		Entry e = entries.get(next);
		// 若下一张尚未就绪则先不计时，等它 READY 后由渲染循环启动计时
		startMs = e.state == ST_READY ? System.currentTimeMillis() : 0L;
		preload(next + 1);
	}

	/** 全部播完，清理并隐藏 */
	private void finish() {
		releaseAll();
		entries = null;
		index = 0;
		startMs = 0L;
		active = false;
	}

	private float computeAlpha(long elapsed, long durationMs) {
		if (durationMs <= 0) return 0f;
		if (fadeMs <= 0) return 1f;
		long fade = Math.min(fadeMs, Math.max(1L, durationMs / 2));
		float a;
		if (elapsed < fade) {
			a = elapsed / (float) fade;
		} else if (elapsed > durationMs - fade) {
			a = (durationMs - elapsed) / (float) fade;
		} else {
			a = 1f;
		}
		if (a < 0f) a = 0f;
		if (a > 1f) a = 1f;
		return a;
	}

	// --------------------------------------------------------------- 加载

	/** 提交后台加载（主线程调用） */
	private void preload(int i) {
		if (entries == null || i < 0 || i >= entries.size()) return;
		Entry e = entries.get(i);
		if (e.state != ST_PENDING || e.submitted) return;
		e.submitted = true;
		final long gen = generation;
		executor.submit(() -> load(gen, e));
	}

	private void load(long gen, Entry e) {
		try {
			byte[] bytes = download(e.url);
			if (bytes == null) {
				markFailed(gen, e);
				return;
			}
			NativeImage image = decode(bytes);
			if (image == null) {
				markFailed(gen, e);
				return;
			}
			Minecraft.getInstance().execute(() -> register(gen, e, image));
		} catch (Throwable t) {
			LOGGER.warn("图片加载失败 {}: {}", e.url, t.toString());
			markFailed(gen, e);
		}
	}

	private void markFailed(long gen, Entry e) {
		Minecraft.getInstance().execute(() -> {
			if (gen == generation) e.state = ST_FAILED;
		});
	}

	/** 注册纹理（主线程） */
	private void register(long gen, Entry e, NativeImage image) {
		if (gen != generation) {
			closeQuietly(image);
			return;
		}
		try {
			ResourceLocation id = new ResourceLocation("imagecover", "img_" + gen + "_" + e.slot);
			DynamicTexture tex = new DynamicTexture(image);
			tex.upload();
			Minecraft.getInstance().getTextureManager().register(id, tex);
			e.texW = image.getWidth();
			e.texH = image.getHeight();
			e.texture = id;
			e.state = ST_READY;
		} catch (Throwable t) {
			LOGGER.warn("纹理注册失败 {}: {}", e.url, t.toString());
			closeQuietly(image);
			e.state = ST_FAILED;
		}
	}

	/** 释放全部纹理（主线程） */
	private void releaseAll() {
		if (entries == null) return;
		TextureManager tm = Minecraft.getInstance().getTextureManager();
		for (Entry e : entries) {
			ResourceLocation id = e.texture;
			if (id != null) {
				try {
					tm.release(id);
				} catch (Throwable t) {
					LOGGER.warn("释放纹理失败 {}: {}", id, t.toString());
				}
				e.texture = null;
			}
		}
	}

	// --------------------------------------------------------------- 网络/解码

	private static byte[] download(String url) {
		HttpURLConnection conn = null;
		try {
			conn = (HttpURLConnection) new URL(url).openConnection();
			conn.setRequestMethod("GET");
			conn.setConnectTimeout(10_000);
			conn.setReadTimeout(15_000);
			conn.setInstanceFollowRedirects(true);
			conn.setRequestProperty("User-Agent", UA);
			conn.setRequestProperty("Accept", "image/*,*/*;q=0.8");
			int code = conn.getResponseCode();
			if (code / 100 != 2) {
				LOGGER.warn("图片 HTTP {} {}", code, url);
				return null;
			}
			try (InputStream in = conn.getInputStream()) {
				return in.readAllBytes();
			}
		} catch (Exception ex) {
			LOGGER.warn("图片下载失败 {}: {}", url, ex.toString());
			return null;
		} finally {
			if (conn != null) conn.disconnect();
		}
	}

	/**
	 * 解码图片：优先 NativeImage(stb)，失败回退 Java ImageIO
	 * （部分 JPEG 变体 stb 解不了但 ImageIO 可以）。
	 */
	private static NativeImage decode(byte[] bytes) {
		try {
			return NativeImage.read(new ByteArrayInputStream(bytes));
		} catch (Exception stbFail) {
			try {
				java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
				if (bi == null) return null;
				NativeImage out = new NativeImage(bi.getWidth(), bi.getHeight(), true);
				for (int y = 0; y < bi.getHeight(); y++) {
					for (int x = 0; x < bi.getWidth(); x++) {
						int argb = bi.getRGB(x, y);
						// NativeImage 内存按 R,G,B,A 字节序排列，int 需以 A 为最高字节、R 为最低字节
						int a = (argb >>> 24) & 0xff;
						int r = (argb >>> 16) & 0xff;
						int gg = (argb >>> 8) & 0xff;
						int b = argb & 0xff;
						out.setPixelRGBA(x, y, (a << 24) | (b << 16) | (gg << 8) | r);
					}
				}
				return out;
			} catch (Exception e) {
				LOGGER.warn("图片解码失败: {}", e.toString());
				return null;
			}
		}
	}

	private static void closeQuietly(NativeImage image) {
		try {
			image.close();
		} catch (Exception ignored) {
		}
	}
}
