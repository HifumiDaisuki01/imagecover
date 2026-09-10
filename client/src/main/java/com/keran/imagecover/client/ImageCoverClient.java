package com.keran.imagecover.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ImageCover 客户端入口：注册插件消息通道，收到服务端指令后全屏显示图片。
 *
 * <p><b>协议 v2（当前）</b>：icb:play -> writeInt(PROTOCOL_VERSION=2) + writeInt(count)
 * + writeInt(fadeMs) + writeBoolean(bgEnabled) + writeFloat(bgAlpha)，
 * 随后每条：writeInt(urlLen) + write(url utf8) + writeLong(时长毫秒)。</p>
 *
 * <p><b>协议 v1（旧版服务端）</b>：icb:play -> writeInt(count) + writeInt(fadeMs) + 各条记录。
 * 客户端靠首字段是否为 {@link #PROTOCOL_VERSION} 来区分：
 * v1 首个 int 是图片数量（合理范围 0~4096），v2 首个 int 固定为 2，两者不可能混淆。</p>
 *
 * <p>这样新客户端既能配合新服务端使用背景遮罩，也能在玩家还没升级服务端时正常工作
 * （此时等价于"背景遮罩关闭"，即修复后的默认行为）。</p>
 */
public class ImageCoverClient implements ClientModInitializer {
	/** 当前客户端支持的协议版本 */
	public static final int PROTOCOL_VERSION = 2;
	public static final ResourceLocation PLAY_CHANNEL = new ResourceLocation("icb", "play");
	public static final ResourceLocation STOP_CHANNEL = new ResourceLocation("icb", "stop");

	/**
	 * 自增清单号：每条 play/stop 指令在网络线程上分配一个 seq，
	 * 主线程执行时只接受"最新 seq"，从而保证同一 tick 内多条指令
	 * （例如先 play 后立刻 stop）按到达顺序生效，不会因 client.execute
	 * 排队而乱序。
	 */
	private static final AtomicLong SEQ = new AtomicLong();

	@Override
	public void onInitializeClient() {
		// 注意：这里刻意不注册 HudRenderCallback。
		// 它的触发时机早于聊天框渲染，会导致全屏图片被左下角聊天框遮挡；
		// 现在改由 GuiMixin 注入 Gui.render() 的末尾来绘制，
		// 保证图片盖在所有 HUD 元素之上（见 ImageOverlay#renderTopmost）。

		ClientPlayNetworking.registerGlobalReceiver(PLAY_CHANNEL, (client, handler, buf, responseSender) -> {
			List<ImageOverlay.Entry> list = new ArrayList<>();
			int fadeMs = 0;
			boolean bgEnabled = false;
			float bgAlpha = 1.0f;
			try {
				buf.markReaderIndex();
				int first = buf.readInt();
				int count;
				boolean v2 = first == PROTOCOL_VERSION;
				if (v2) {
					// 协议 v2：显式带版本号与背景字段
					count = buf.readInt();
					// count 合理 + 剩余字节足够容纳这些记录，才认定为真正的 v2；
					// 否则（极罕见的 v1 列表恰好有 2 张图）回退按 v1 解析。
					if (count < 0 || count > 4096 || buf.readableBytes() < count * 12L) {
						buf.resetReaderIndex();
						count = buf.readInt();
						fadeMs = buf.readInt();
						bgEnabled = false;
						bgAlpha = 1.0f;
					} else {
						fadeMs = buf.readInt();
						bgEnabled = buf.readBoolean();
						bgAlpha = buf.readFloat();
					}
				} else {
					// 协议 v1：首字段就是图片数量
					count = first;
					if (count < 0 || count > 4096) return;
					fadeMs = buf.readInt();
					bgEnabled = false; // 旧服务端没有背景概念，等价于关闭
					bgAlpha = 1.0f;
				}
				for (int i = 0; i < count; i++) {
					int len = buf.readInt();
					if (len < 0 || len > 65535) return;
					byte[] bytes = new byte[len];
					buf.readBytes(bytes);
					String url = new String(bytes, StandardCharsets.UTF_8);
					long durationMs = buf.readLong();
					list.add(new ImageOverlay.Entry(i, url, durationMs));
				}
			} catch (Exception e) {
				return;
			}
			final List<ImageOverlay.Entry> payload = list;
			final int fade = Math.max(0, fadeMs);
			final boolean bg = bgEnabled;
			final float bgA = bgAlpha;
			final long seq = SEQ.incrementAndGet();
			client.execute(() -> {
				if (seq != SEQ.get()) return; // 已被更晚的指令取代
				ImageOverlay.INSTANCE.play(payload, fade, bg, bgA);
			});
		});

		ClientPlayNetworking.registerGlobalReceiver(STOP_CHANNEL, (client, handler, buf, responseSender) -> {
			final long seq = SEQ.incrementAndGet();
			client.execute(() -> {
				if (seq != SEQ.get()) return;
				ImageOverlay.INSTANCE.stop();
			});
		});
	}
}
