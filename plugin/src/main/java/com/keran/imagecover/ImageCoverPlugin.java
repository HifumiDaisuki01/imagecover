package com.keran.imagecover;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ImageCover - 服务端全屏图片演出插件（Paper 1.20.1）
 *
 * 通过 Fabric 插件消息通道通知装有配套客户端 Mod 的玩家全屏显示图片：
 *   icb:play  -> writeInt(count) + writeInt(fadeMs)，随后每条：writeInt(urlLen) + write(url utf8) + writeLong(时长毫秒)
 *   icb:stop  -> 空 payload
 *
 * 协议与客户端 imagecover 一致，两端必须同步修改。
 */
public class ImageCoverPlugin extends JavaPlugin {
	public static final String CHANNEL_PLAY = "icb:play";
	public static final String CHANNEL_STOP = "icb:stop";
	/** Bukkit 单条插件消息上限（字节），超出会被服务端丢弃 */
	public static final int MAX_PAYLOAD = 32767;

	private static ImageCoverPlugin instance;
	private SetManager setManager;

	@Override
	public void onEnable() {
		instance = this;

		// 首次启用自动生成示例 set.yml（已存在则不覆盖）
		saveResource("set.yml", false);
		setManager = new SetManager(this);
		saveDefaultConfig();
		setManager.reload();

		Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL_PLAY);
		Bukkit.getMessenger().registerOutgoingPluginChannel(this, CHANNEL_STOP);

		ImageCoverCommand cmd = new ImageCoverCommand(this);
		if (getCommand("icv") != null) {
			getCommand("icv").setExecutor(cmd);
			getCommand("icv").setTabCompleter(cmd);
		} else {
			getLogger().severe("命令 'icv' 未在 plugin.yml 中注册，命令不可用！");
		}

		getLogger().info("ImageCover 已启用，通道: " + CHANNEL_PLAY + " / " + CHANNEL_STOP);
	}

	@Override
	public void onDisable() {
		Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_PLAY);
		Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL_STOP);
	}

	public SetManager setManager() {
		return setManager;
	}

	/** 单张图片指令的淡入淡出时长（毫秒）；config.yml 里 fade:false 时为 0（关闭） */
	public int fadeMs() {
		if (!getConfig().getBoolean("fade", true)) return 0;
		double sec = getConfig().getDouble("fade-duration", 0.3);
		return (int) Math.max(0L, Math.round(sec * 1000.0));
	}

	/**
	 * 向单个客户端下发播放列表。
	 * @param fadeMs 淡入/淡出时长（毫秒），0 表示不做淡入淡出
	 * @return true 表示已成功发出（目标离线/列表过大/异常时返回 false）
	 */
	public boolean sendPlay(Player player, List<PlayItem> items, int fadeMs) {
		if (player == null || !player.isOnline()) return false;
		if (items == null || items.isEmpty()) return false;
		try {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(bos);
			dos.writeInt(items.size());
			dos.writeInt(Math.max(0, fadeMs));
			for (PlayItem item : items) {
				byte[] bytes = item.url().getBytes(StandardCharsets.UTF_8);
				dos.writeInt(bytes.length);
				dos.write(bytes);
				dos.writeLong(item.durationMs());
			}
			dos.flush();
			byte[] payload = bos.toByteArray();
			if (payload.length > MAX_PAYLOAD) {
				getLogger().warning("播放列表过大 (" + payload.length + " 字节 > " + MAX_PAYLOAD
						+ ")，无法发送给 " + player.getName() + "（可减少张数或缩短链接）");
				return false;
			}
			player.sendPluginMessage(this, CHANNEL_PLAY, payload);
			getLogger().info("→ 已向 " + player.getName() + " 下发 " + items.size() + " 张图片 (fade=" + fadeMs + "ms)");
			return true;
		} catch (Exception e) {
			getLogger().warning("发送播放指令失败 (" + player.getName() + "): " + e.getMessage());
			return false;
		}
	}

	/** 通知单个客户端立即清空并隐藏图片 */
	public void sendStop(Player player) {
		if (player == null || !player.isOnline()) return;
		try {
			player.sendPluginMessage(this, CHANNEL_STOP, new byte[0]);
		} catch (Exception e) {
			getLogger().warning("发送停止指令失败 (" + player.getName() + "): " + e.getMessage());
		}
	}

	/** 世界名解析：Bukkit 世界名 → 忽略大小写 → Multiverse-Core 别名（软依赖，反射避免硬依赖） */
	public World resolveWorld(String name) {
		World w = Bukkit.getWorld(name);
		if (w != null) return w;
		for (World world : Bukkit.getWorlds()) {
			if (world.getName().equalsIgnoreCase(name)) return world;
		}
		try {
			Object mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
			if (mv != null) {
				Object mgr = mv.getClass().getMethod("getMVWorldManager").invoke(mv);
				Object mvw = mgr.getClass().getMethod("getMVWorld", String.class).invoke(mgr, name);
				if (mvw != null) {
					String real = (String) mvw.getClass().getMethod("getName").invoke(mvw);
					World w2 = Bukkit.getWorld(real);
					if (w2 != null) return w2;
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	public static ImageCoverPlugin get() {
		return instance;
	}
}
