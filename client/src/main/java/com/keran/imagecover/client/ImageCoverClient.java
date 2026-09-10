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
 *   icb:play -> writeInt(count) + writeInt(fadeMs)，随后每条：writeInt(urlLen) + write(url utf8) + writeLong(时长毫秒)
 *   icb:stop -> 空 payload
 */
public class ImageCoverClient implements ClientModInitializer {
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
			try {
				int count = buf.readInt();
				if (count < 0 || count > 4096) return;
				fadeMs = buf.readInt();
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
			final long seq = SEQ.incrementAndGet();
			client.execute(() -> {
				if (seq != SEQ.get()) return; // 已被更晚的指令取代
				ImageOverlay.INSTANCE.play(payload, fade);
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
