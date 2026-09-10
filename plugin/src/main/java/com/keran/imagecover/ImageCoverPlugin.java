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
 * 通过 Fabric 插件消息通道通知装有配套客户端 Mod 的玩家全屏显示图片。
 *
 * <p><b>协议 v2（当前）</b>：
 *   icb:play -> writeInt(2) + writeInt(count) + writeInt(fadeMs)
 *              + writeBoolean(bgEnabled) + writeFloat(bgAlpha)
 *              + 每条：writeInt(urlLen) + write(url utf8) + writeLong(时长毫秒)
 *   icb:stop -> 空 payload</p>
 *
 * <p>旧客户端（v1.0.3 及以前）只认 "count + fadeMs" 开头的旧格式，
 * 收到 v2 包会因为版本号被当成图片数量而丢弃。因此本插件只在
 * 目标是新版客户端时才发 v2 —— 由客户端握手/版本探测决定较复杂，
 * 这里采用更朴素的做法：协议版本由后台配置 {@code protocol} 控制，
 * 默认 2；若服务器上仍有大量旧客户端，可改回 1（此时背景遮罩不可用）。</p>
 */
public class ImageCoverPlugin extends JavaPlugin {
	public static final String CHANNEL_PLAY = "icb:play";
	public static final String CHANNEL_STOP = "icb:stop";
	/** 当前插件使用的协议版本；2 = 带背景参数，1 = 旧格式 */
	public static final int PROTOCOL_VERSION = 2;
	/** Bukkit 单条插件消息上限（字节），超出会被服务端丢弃 */
	public static final int MAX_PAYLOAD = 32767;

	private static ImageCoverPlugin instance;
	private SetManager setManager;

	/**
	 * 运行时背景开关覆盖值：null 表示沿用 config.yml。
	 * 由 /icv bg on|off 设置，/icv reload 会清空该覆盖。
	 */
	private Boolean bgEnabledOverride = null;
	private Float bgAlphaOverride = null;

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

	/** 是否铺全屏背景遮罩（默认关闭）。指令覆盖优先于 config.yml。 */
	public boolean backgroundEnabled() {
		if (bgEnabledOverride != null) return bgEnabledOverride;
		return getConfig().getBoolean("background.enabled", false);
	}

	/** 背景遮罩不透明度 0.0~1.0。指令覆盖优先于 config.yml。 */
	public float backgroundAlpha() {
		if (bgAlphaOverride != null) return bgAlphaOverride;
		double pct = getConfig().getDouble("background.alpha", 100.0);
		return (float) Math.max(0.0, Math.min(1.0, pct / 100.0));
	}

	/** /icv bg on|off：临时全局开关（内存生效，重启或 reload 后恢复配置文件值） */
	public void setBackgroundEnabled(boolean enabled) {
		this.bgEnabledOverride = enabled;
	}

	/** /icv bg alpha <0-100>：临时调整遮罩不透明度 */
	public void setBackgroundAlpha(float alpha) {
		this.bgAlphaOverride = Math.max(0f, Math.min(1f, alpha));
	}

	/** /icv reload：清空指令覆盖，完全回到 config.yml */
	public void clearBackgroundOverride() {
		this.bgEnabledOverride = null;
		this.bgAlphaOverride = null;
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
			int protocol = getConfig().getInt("protocol", PROTOCOL_VERSION);
			if (protocol >= 2) {
				dos.writeInt(PROTOCOL_VERSION);
			}
			dos.writeInt(items.size());
			dos.writeInt(Math.max(0, fadeMs));
			if (protocol >= 2) {
				dos.writeBoolean(backgroundEnabled());
				dos.writeFloat(backgroundAlpha());
			}
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
			getLogger().info("→ 已向 " + player.getName() + " 下发 " + items.size() + " 张图片 (fade=" + fadeMs
					+ "ms, bg=" + backgroundEnabled() + "/" + Math.round(backgroundAlpha() * 100) + "%, proto=v" + protocol + ")");
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
