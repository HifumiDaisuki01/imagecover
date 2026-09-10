package com.keran.imagecover;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI 软依赖桥：装了 PAPI 就展开占位符，没装则原样返回。
 */
final class PlaceholderApiBridge {
	private PlaceholderApiBridge() {}

	static boolean hasPapi() {
		return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
	}

	/** 展开占位符；控制台执行时用首位在线玩家作为占位上下文（%player_name% 等需要玩家） */
	static String setPlaceholders(CommandSender sender, String text) {
		if (!hasPapi()) return text;
		try {
			Player ctx = sender instanceof Player p ? p : firstOnline();
			if (ctx != null) {
				return PlaceholderAPI.setPlaceholders(ctx, text);
			}
			// 无在线玩家时尝试离线占位（多数会原样返回）
			return PlaceholderAPI.setPlaceholders(null, text);
		} catch (Throwable t) {
			return text;
		}
	}

	private static Player firstOnline() {
		for (Player p : Bukkit.getOnlinePlayers()) return p;
		return null;
	}
}
