package com.keran.imagecover.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ImageCover 客户端入口：注册插件消息通道，收到服务端指令后全屏显示图片。
 *   icb:play -> writeInt(count) + writeInt(fadeMs)，随后每条：writeInt(urlLen) + write(url utf8) + writeLong(时长毫秒)
 *   icb:stop -> 空 payload
 */
public class ImageCoverClient implements ClientModInitializer {
	public static final ResourceLocation PLAY_CHANNEL = new ResourceLocation("icb", "play");
	public static final ResourceLocation STOP_CHANNEL = new ResourceLocation("icb", "stop");

	@Override
	public void onInitializeClient() {
		HudRenderCallback.EVENT.register(ImageOverlay.INSTANCE);

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
			client.execute(() -> ImageOverlay.INSTANCE.play(payload, fade));
		});

		ClientPlayNetworking.registerGlobalReceiver(STOP_CHANNEL, (client, handler, buf, responseSender) ->
				client.execute(() -> ImageOverlay.INSTANCE.stop()));
	}
}
